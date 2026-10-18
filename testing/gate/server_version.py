"""ONE server version per QA run, from ONE place (CIRISClient#166).

    python3 -m testing.gate.server_version                       # the default
    python3 -m testing.gate.server_version --override v0.5.231   # a dispatch input
    python3 -m testing.gate.server_version --github-output "$GITHUB_OUTPUT"

WHAT A GREEN MATRIX USED TO MEAN. Run 39023854089 — the last all-green five
platform run before this module — drove the Linux and Android legs against
CIRISServer v0.5.228 and the macOS, iOS and Windows legs against v0.5.229,
because a release landed while the Linux leg was already running. Each leg
picked its own node: the Linux leg and build.yml's e2e tried `v$(cat VERSION)`
— the CLIENT's version, which leads the server's by design and so never named
a release — and fell back to "latest"; the macOS and Windows legs took "latest"
outright. "Latest" is a moving target, and three legs reading it at three
different minutes is three servers.

THE ONE PLACE. The version is resolved ONCE, in one job the legs `needs:`,
and every leg reads that job's output: the binary it downloads, the
`ciris-server==` the Android build installs, the `--node-version` on its board
line and in the gallery. The default is the compat pin — the CIRISServer
commit `client/VENDORING.md` §1 records this tree as merged against
(`packaging/check_vendoring.py --merged-ref CIRISServer`) — mapped to the
release tag that points at it. A `workflow_dispatch` input overrides it, for a
run against a named release.

THE PIN IS NOT ALWAYS A RELEASE. §1 may record a branch commit (it does at the
time of writing: `fix/client-copy-0.5.232`, the copy the next server release
carries). No release tag points at such a commit, so there is no binary to
download for it, and the run falls back to the latest release — SAYING SO, as a
`::warning::` on the run, so a green matrix against a fallback is never read as
a green matrix against the pin.

THE iOS SIMULATOR EMBEDS THE AGENT'S PIN. Its app carries CIRISAgent's
`requirements.txt` ciris-server, not the version resolved here; the leg records
both (`--embedded-node-version`) rather than letting the board claim the run's
version for a node it did not run.

Stdlib plus `gh` for the three GitHub reads; `resolve()` takes the reads as
values so the decision is unit-tested without a network.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional, Sequence

REPO = Path(__file__).resolve().parents[2]
SERVER_REPO = "CIRISAI/CIRISServer"

#: A release tag, and nothing else: `v0.5.231`. A branch name or a bare sha is
#: not a version a leg can download.
TAG_RE = re.compile(r"^v\d+\.\d+\.\d+$")

PIN, INPUT, LATEST_FALLBACK = "pin", "input", "latest-fallback"


class ResolveError(Exception):
    """The run cannot name one server version. The workflow must FAIL on it —
    a leg that picks its own version is the defect this module removes."""


@dataclass(frozen=True)
class Resolution:
    version: str          # the release tag every leg uses: v0.5.231
    source: str           # PIN | INPUT | LATEST_FALLBACK
    pin_sha: str          # what VENDORING.md §1 records (empty for INPUT)
    pin_label: str        # the tag or branch §1 records beside it
    warning: str = ""     # non-empty when the run is NOT against the pin

    @property
    def pypi(self) -> str:
        """The PyPI spelling, for `ciris-server==`: the tag without its `v`."""
        return self.version[1:]


def resolve(*, pin_sha: str, pin_label: str, tags: Sequence[tuple[str, str]],
            is_release: Callable[[str], bool], latest: Callable[[], str],
            override: Optional[str] = None) -> Resolution:
    """Decide the run's server version from the facts, raising ResolveError
    when there is none.

    `tags` is CIRISServer's (name, commit sha) list; `is_release(tag)` says
    whether a release exists for a tag (a tag with no release has no binary);
    `latest()` is the latest release's tag, read ONLY when the pin cannot be
    used — so a run against the pin never consults a moving target at all.
    """
    if override:
        override = override.strip()
        if not TAG_RE.match(override):
            raise ResolveError(
                f"server_version {override!r} is not a release tag (want vX.Y.Z)")
        if not is_release(override):
            raise ResolveError(
                f"server_version {override!r} is not a {SERVER_REPO} release")
        return Resolution(override, INPUT, "", "")

    if not pin_sha:
        raise ResolveError("VENDORING.md §1 records no CIRISServer commit to pin to")
    at_pin = sorted(
        name for name, sha in tags
        if TAG_RE.match(name) and (sha.startswith(pin_sha) or pin_sha.startswith(sha))
    )
    released = [t for t in at_pin if is_release(t)]
    if released:
        # Several release tags on one commit would be a server-side oddity;
        # the highest is the one its users get.
        return Resolution(_highest(released), PIN, pin_sha, pin_label)

    fallback = latest().strip()
    if not TAG_RE.match(fallback):
        raise ResolveError(f"the latest {SERVER_REPO} release is {fallback!r}, not a vX.Y.Z tag")
    why = (f"tagged {', '.join(at_pin)} but no release carries a binary for it"
           if at_pin else "not a released tag")
    warning = (
        f"the compat pin (CIRISServer {pin_sha[:8]}, `{pin_label}`) is {why}; "
        f"every leg runs against the latest release {fallback} instead. "
        f"Re-pin client/VENDORING.md §1 to the server's tag once that release ships."
    )
    return Resolution(fallback, LATEST_FALLBACK, pin_sha, pin_label, warning)


def _highest(tags: Sequence[str]) -> str:
    return max(tags, key=lambda t: tuple(int(x) for x in t[1:].split(".")))


# ── the three GitHub reads ──────────────────────────────────────────────────

def _gh(*args: str) -> str:
    p = subprocess.run(["gh", *args], capture_output=True, text=True)
    if p.returncode != 0:
        raise ResolveError(f"gh {' '.join(args)} failed: {p.stderr.strip() or p.stdout.strip()}")
    return p.stdout


def gh_tags() -> list[tuple[str, str]]:
    """Every tag with the commit it points at (annotated tags peeled by the API)."""
    out = _gh("api", f"repos/{SERVER_REPO}/tags", "--paginate",
              "--jq", ".[] | [.name, .commit.sha] | @tsv")
    pairs = []
    for ln in out.splitlines():
        if "\t" in ln:
            name, sha = ln.split("\t", 1)
            pairs.append((name.strip(), sha.strip()))
    return pairs


def gh_is_release(tag: str) -> bool:
    p = subprocess.run(["gh", "api", f"repos/{SERVER_REPO}/releases/tags/{tag}", "--jq", ".tag_name"],
                       capture_output=True, text=True)
    return p.returncode == 0 and p.stdout.strip() == tag


def gh_latest() -> str:
    return _gh("api", f"repos/{SERVER_REPO}/releases/latest", "--jq", ".tag_name").strip()


def pinned() -> tuple[str, str]:
    """(label, sha) from VENDORING.md §1 — the same row `--merged-ref` reads."""
    # By path: the repo's `packaging/` directory is shadowed by the PyPI
    # `packaging` distribution on any interpreter that has pip.
    import importlib.util  # noqa: PLC0415
    spec = importlib.util.spec_from_file_location("check_vendoring", REPO / "packaging" / "check_vendoring.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.merged_ref("CIRISServer")


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--override", default="",
                    help="a release tag from the workflow_dispatch input; empty means the pin")
    ap.add_argument("--github-output", type=Path,
                    help="append version=/pypi=/source=/pin=/pin_label= here ($GITHUB_OUTPUT)")
    ap.add_argument("--json", action="store_true", help="print the resolution as JSON")
    args = ap.parse_args(argv)

    try:
        label, sha = ("", "")
        if not args.override.strip():
            label, sha = pinned()
        r = resolve(pin_sha=sha, pin_label=label, tags=gh_tags() if not args.override.strip() else (),
                    is_release=gh_is_release, latest=gh_latest, override=args.override)
    except ResolveError as e:
        print(f"::error::{e}", file=sys.stderr)
        return 1

    if r.warning:
        # stderr: the version is read from stdout by `$(…)` callers, and the
        # runner reads workflow commands from either stream.
        print(f"::warning::{r.warning}", file=sys.stderr)
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as fh:
            for k, v in (("version", r.version), ("pypi", r.pypi), ("source", r.source),
                         ("pin", r.pin_sha), ("pin_label", r.pin_label)):
                fh.write(f"{k}={v}\n")
    if args.json:
        print(json.dumps({"version": r.version, "pypi": r.pypi, "source": r.source,
                          "pin": r.pin_sha, "pin_label": r.pin_label, "warning": r.warning}))
    else:
        print(r.version)
    return 0


if __name__ == "__main__":
    sys.exit(main())
