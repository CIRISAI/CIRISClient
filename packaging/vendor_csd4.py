#!/usr/bin/env python3
"""Re-vendor the CSD/4 topology catalogue into `packaging/csd4/` (CIRISClient#179).

    # THE DEFAULT, once a release carries the catalogue (CIRISServer v0.5.232+):
    python3 packaging/vendor_csd4.py --from-release v0.5.232

    # pre-release only: a commit that no tag carries yet (runs the server's model)
    python3 packaging/vendor_csd4.py --ref 003871fd --note '<where the commit stands>'

What lands in `packaging/csd4/` either way:

* `requirements.yaml`, `topology_vocabulary.json`, `topology_vocabulary.pin`
  — byte copies of CIRISServer's `harness/native/topologies/catalog/`. The card
  is compared to the first; its atoms to the second; the third is the server's
  pin of the vocabulary to CIRISConstitution (the checker re-hashes it).
* `traces.json` — the server MODEL's step-by-step trace of T0–T19 (which atoms
  hold after each step), so "at one instant" is the server's answer rather than
  a client re-derivation of its rules.
* `PIN.md` — where the four came from and the sha256 of each. `check_csd4.py`
  refuses a file whose bytes disagree with it.

`--from-release <tag>` EXECUTES NO SERVER CODE. It downloads the release assets
`csd4-requirements.yaml`, `csd4-topology_vocabulary.json`,
`csd4-topology_vocabulary.pin`, `csd4-traces.json` (+ `SHA256SUMS` and each
file's cosign `.sig`/`.pem`) into an empty scratch directory, refuses on any
asset missing from `SHA256SUMS` or hashing differently, verifies each cosign
signature when `cosign` is on PATH — and says so, in the output AND in PIN.md,
when it is not — then validates the set with `check_csd4.load_catalogue` before
anything replaces `packaging/csd4/`. `--release-dir` takes an already-downloaded
set instead (no network; the tests use it).

`--ref` fetches a source tarball and runs `harness.native.model` from it in a
child `python3 -I` with the tree put on `sys.path` explicitly. It is for a pin
no release carries yet; prefer a tag.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

REPO = "CIRISAI/CIRISServer"
CATALOG = "harness/native/topologies/catalog"
BYTE_COPIES = ("requirements.yaml", "topology_vocabulary.json", "topology_vocabulary.pin")
FILES = BYTE_COPIES + ("traces.json",)
ASSET_PREFIX = "csd4-"
ASSETS = tuple(ASSET_PREFIX + f for f in FILES)
OUT = Path(__file__).resolve().parent / "csd4"
#: The Sigstore identity CIRISServer's release.yml signs as (keyless, GitHub OIDC).
COSIGN_IDENTITY = r"^https://github\.com/CIRISAI/CIRISServer/\.github/workflows/release\.yml@refs/tags/"
COSIGN_ISSUER = "https://token.actions.githubusercontent.com"
SUMS_LINE = re.compile(r"^([0-9a-f]{64})\s+\*?(\S+)\s*$")

#: Runs in the CHILD interpreter against the fetched tree (argv[1]). The same
#: shape CIRISServer's `python -m harness.native trace` publishes per release.
_DUMP = r"""
import hashlib, json, sys
sys.path.insert(0, sys.argv[1])
from harness.native import model as m
cat = m.catalogue()
rep = m.evaluate(cat)
if not rep.get("ok"):
    sys.exit("the server's own model does not evaluate clean at this ref: %r" % rep.get("problems"))
entries = {}
for cid, e in sorted(cat.items(), key=lambda kv: int(kv[0][1:])):
    t = e.topology
    initial = [] if t.get("canonicals") else ["root.none"]
    entries[cid] = {
        "file": e.path.name,
        "title": e.title,
        "promises": list(e.promises),
        "initial": initial,
        "steps": [{"id": s.id, "atoms": sorted(st)} for s, st in m.trace(t)],
        "buildable_prefix": m.buildable_prefix(t),
        "refused": m.refused_steps(t),
    }
print(json.dumps({
    "_meta": {
        "catalogue_version": m.CATALOGUE_VERSION,
        "catalog_ids": m.CATALOG_IDS,
        "sources": {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in sorted(m.CATALOG_DIR.glob("T*.yaml"))},
        "generator": "packaging/vendor_csd4.py (harness.native.model.trace at the pinned commit)",
    },
    "entries": entries,
}, indent=1, sort_keys=True))
"""


class ReleaseError(Exception):
    """A release asset set that is not what its SHA256SUMS (or signature) says."""


def _sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


# ── --from-release ───────────────────────────────────────────────────────────

def download_release(tag: str, dest: Path) -> None:
    """The four assets, their .sig/.pem, and SHA256SUMS(+.sig/.pem) into `dest`,
    which must be empty: nothing already there may be mistaken for an asset."""
    if any(dest.iterdir()):
        raise ReleaseError(f"{dest}: not empty — a release is downloaded into an empty directory only")
    subprocess.run(["gh", "release", "download", tag, "-R", REPO, "-D", str(dest),
                    "-p", f"{ASSET_PREFIX}*", "-p", "SHA256SUMS*"], check=True)


def verify_sums(rel: Path) -> dict[str, str]:
    """asset → sha256, each one listed in SHA256SUMS AND hashing to it."""
    sums_file = rel / "SHA256SUMS"
    if not sums_file.exists():
        raise ReleaseError(f"{sums_file}: missing — an unlisted asset is an unverified one")
    sums: dict[str, str] = {}
    for line in sums_file.read_text(encoding="utf-8").splitlines():
        m = SUMS_LINE.match(line)
        if m:
            sums[m.group(2)] = m.group(1)
    if not sums:
        raise ReleaseError(f"{sums_file}: parsed ZERO `<sha256>  <file>` lines — refusing rather than trusting it")
    out: dict[str, str] = {}
    for asset in ASSETS:
        if asset not in sums:
            raise ReleaseError(f"SHA256SUMS has no entry for {asset}")
        p = rel / asset
        if not p.exists():
            raise ReleaseError(f"{asset}: listed in SHA256SUMS but not in the release")
        got = _sha(p)
        if got != sums[asset]:
            raise ReleaseError(f"{asset}: sha256 {got[:12]}… but SHA256SUMS says {sums[asset][:12]}… — refused")
        out[asset] = got
    return out


def verify_cosign(rel: Path, tag: str) -> str:
    """One line for the output and PIN.md: verified, or WHY not. Never silent."""
    cosign = shutil.which("cosign")
    if cosign is None:
        return ("NOT verified: `cosign` is not on PATH. Every asset's sha256 matches the release's SHA256SUMS; "
                "the signatures (.sig/.pem) were not checked. Re-run with cosign installed to verify.")
    for f in (*ASSETS, "SHA256SUMS"):
        sig, pem = rel / f"{f}.sig", rel / f"{f}.pem"
        if not sig.exists() or not pem.exists():
            raise ReleaseError(f"{f}: no {sig.name}/{pem.name} in the release, and cosign is here to check them")
        r = subprocess.run([cosign, "verify-blob", "--signature", str(sig), "--certificate", str(pem),
                            "--certificate-identity-regexp", COSIGN_IDENTITY,
                            "--certificate-oidc-issuer", COSIGN_ISSUER, str(rel / f)],
                           capture_output=True, text=True)
        if r.returncode != 0:
            raise ReleaseError(f"{f}: cosign verify-blob refused it — {(r.stderr or r.stdout).strip()[:300]}")
    return (f"verified: cosign verify-blob on all four assets and SHA256SUMS "
            f"(identity `{COSIGN_IDENTITY}`, issuer `{COSIGN_ISSUER}`).")


def stage_from_release(rel: Path, stage: Path) -> dict[str, str]:
    """Copy the assets into `stage` under their vendored names; return file → sha256."""
    for f in FILES:
        shutil.copyfile(rel / (ASSET_PREFIX + f), stage / f)
    return {f: _sha(stage / f) for f in FILES}


# ── --ref (pre-release) ──────────────────────────────────────────────────────

def fetch(ref: str, work: Path) -> tuple[Path, str]:
    commit = resolve_commit(ref)
    tgz = work / "src.tgz"
    with tgz.open("wb") as fh:
        subprocess.run(["gh", "api", f"repos/{REPO}/tarball/{commit}"], check=True, stdout=fh)
    dest = work / "tree"
    dest.mkdir()
    with tarfile.open(tgz) as tf:
        tf.extractall(dest, filter="data")
    (root,) = list(dest.iterdir())
    return root, commit


def resolve_commit(ref: str) -> str:
    return subprocess.run(["gh", "api", f"repos/{REPO}/commits/{ref}", "--jq", ".sha"],
                          check=True, capture_output=True, text=True).stdout.strip()


def stage_from_ref(root: Path, stage: Path, cwd: str) -> dict[str, str]:
    for name in BYTE_COPIES:
        shutil.copyfile(root / CATALOG / name, stage / name)
    dump = subprocess.run([sys.executable, "-I", "-c", _DUMP, str(root)],
                          check=True, capture_output=True, text=True, cwd=cwd).stdout
    (stage / "traces.json").write_text(dump, encoding="utf-8")
    return {f: _sha(stage / f) for f in FILES}


# ── PIN.md, validation, install ──────────────────────────────────────────────

def write_pin(stage: Path, shas: dict[str, str], source_rows: list[tuple[str, str]], how: str) -> None:
    vocab = json.loads((stage / "topology_vocabulary.json").read_text(encoding="utf-8"))
    vpin = dict(line.split("=", 1) for line in (stage / "topology_vocabulary.pin").read_text().splitlines()
                if "=" in line and not line.startswith("#"))
    rows = "\n".join(f"| `{f}` | `{shas[f]}` |" for f in FILES)
    src = "\n".join(f"| {k} | {v} |" for k, v in source_rows)
    (stage / "PIN.md").write_text(f"""# `packaging/csd4/` — the CSD/4 topology catalogue, pinned

Checker input for `packaging/check_csd4.py` (and, through it,
`packaging/check_csd_v3.py`). GENERATED by `packaging/vendor_csd4.py`; do not
hand-edit — the checker refuses a file whose sha256 is not the one below.

| pin | value |
|---|---|
| repo | `{REPO}` |
{src}
| catalogue_version | `{vocab['_meta']['catalogue_version']}` |
| atoms | {len(vocab['atoms'])} |
| vocabulary owner | `{vpin.get('repo', '?')}` @ `{vpin.get('commit', '?')}` (`{vpin.get('path', '?')}`) |

## Files

{how}

| file | sha256 |
|---|---|
{rows}

## Refetch

The default, from a CIRISServer release that carries the catalogue (v0.5.232 on):

```
python3 packaging/vendor_csd4.py --from-release <tag>
```

Executes no server code: the `csd4-*` assets are checked against the release's
`SHA256SUMS`, and their cosign signatures when `cosign` is on PATH. For a commit
no release carries yet (it runs the server's model from a source tarball):
`python3 packaging/vendor_csd4.py --ref <commit> --note '<where it stands>'`.

Then `python3 packaging/check_csd4.py --self-test` and every card:
`for f in FSD/CSD/CSD-*.md; do python3 packaging/check_csd_v3.py "$f" --registry client/ceg/namespace_registry.json; done`.
""", encoding="utf-8")


def install(stage: Path, out: Path) -> None:
    """Validate the staged set as the checker will read it, THEN replace `out`."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import check_csd4  # noqa: PLC0415
    try:
        check_csd4.load_catalogue(stage)
    except check_csd4.CatalogueError as e:
        raise ReleaseError(f"the staged catalogue does not load: {e}") from e
    out.mkdir(parents=True, exist_ok=True)
    for f in (*FILES, "PIN.md"):
        shutil.copyfile(stage / f, out / f)


def from_release(tag: str, out: Path = OUT, release_dir: Path | None = None, commit: str | None = None) -> str:
    """Vendor `tag`'s csd4-* assets into `out`. Returns the cosign status line."""
    with tempfile.TemporaryDirectory(prefix="csd4-release-") as tmp:
        rel = release_dir
        if rel is None:
            rel = Path(tmp) / "release"
            rel.mkdir()
            download_release(tag, rel)
        verify_sums(rel)
        cosign = verify_cosign(rel, tag)
        stage = Path(tmp) / "stage"
        stage.mkdir()
        shas = stage_from_release(rel, stage)
        write_pin(stage, shas, [
            ("release tag", f"`{tag}`"),
            ("commit", f"`{commit}`" if commit else "not resolved (vendored from a local release directory)"),
            ("assets", ", ".join(f"`{a}`" for a in ASSETS) + " (+ `SHA256SUMS`)"),
            ("SHA256SUMS", "every asset listed and matching (the sha256s below are the release's)"),
            ("cosign", cosign),
        ], f"Byte copies of the release assets `{ASSET_PREFIX}<file>`; no server code was run to produce them.")
        install(stage, out)
    return cosign


def from_ref(ref: str, out: Path = OUT, src: Path | None = None, commit: str | None = None, note: str = "") -> None:
    with tempfile.TemporaryDirectory(prefix="csd4-vendor-") as tmp:
        if src is not None:
            root = src.resolve()
            commit = commit or resolve_commit(ref)
        else:
            root, commit = fetch(ref, Path(tmp))
        stage = Path(tmp) / "stage"
        stage.mkdir()
        shas = stage_from_ref(root, stage, tmp)
        write_pin(stage, shas, [
            ("ref", f"`{ref}`"),
            ("commit", f"`{commit}`"),
            ("status", note or "a pre-release pin: no CIRISServer release carries it"),
        ], f"The first three are byte copies of `{CATALOG}/` at the commit; `traces.json` is the server "
           f"model's step-by-step trace of T0–T19 at the same commit, produced by running it (sources' "
           f"sha256 in its `_meta.sources`). A release pin (`--from-release`) replaces this with assets.")
        install(stage, out)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    how = ap.add_mutually_exclusive_group(required=True)
    how.add_argument("--from-release", metavar="TAG", help="a CIRISServer release carrying the csd4-* assets (the default)")
    how.add_argument("--ref", help="pre-release: a CIRISServer commit/branch to run the model at")
    ap.add_argument("--release-dir", type=Path, help="with --from-release: an already-downloaded asset set (no network)")
    ap.add_argument("--src", type=Path, help="with --ref: an already-extracted tree at --ref (skips the fetch)")
    ap.add_argument("--commit", help="the full commit sha (default: resolved via gh)")
    ap.add_argument("--note", default="", help="with --ref: a status line for PIN.md")
    ap.add_argument("--out", type=Path, default=OUT, help=argparse.SUPPRESS)
    args = ap.parse_args(argv)
    try:
        if args.from_release:
            commit = args.commit
            if commit is None and args.release_dir is None:
                commit = resolve_commit(args.from_release)
            cosign = from_release(args.from_release, args.out, args.release_dir, commit)
            print(f"cosign: {cosign}")
        else:
            from_ref(args.ref, args.out, args.src, args.commit, args.note)
    except ReleaseError as e:
        print(f"[FAIL] {e}", file=sys.stderr)
        return 1
    print(f"vendored into {args.out}:")
    print((args.out / "PIN.md").read_text(encoding="utf-8").split("## Files", 1)[1].split("## Refetch", 1)[0].strip())
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
