#!/usr/bin/env python3
"""Cards against the mesh's formal model: the states a card draws and the acts it offers.

    python3 packaging/check_csd_model.py                 # gate: every csd:model block agrees with the pin
    python3 packaging/check_csd_model.py --verify-pin    # the vendored model files match PIN.tsv
    python3 packaging/check_csd_model.py --upstream      # is the pin behind CIRISConstitution's branch?
    python3 packaging/check_csd_model.py --vendor <sha>  # re-pin from a pushed CIRISConstitution commit
    python3 packaging/check_csd_model.py --self-test

WHY. The life of a mesh is modelled in CIRISConstitution (`formal/`, CC 4.5.13):
every object's states (`formal/STATE_TABLE.tsv`) and every transition, with
who may sign it, under which policy, and whether it needs a confirm
(`manifests/transitions.json`). A card that restates those by hand drifts:
the v62 audit (2026-11-21) found "2 of 3" on nine cards and four halt kinds
where the model has five. So a card CITES the model and this check holds it
to the pinned copy, both ways:

    ```yaml csd:model
    - var: root.halt
      states: 'not_halted latched_lone confirmed'
      code: client/shared/src/commonMain/kotlin/.../Accord.kt
      code.not_halted: 'val halted: Boolean'
    - act: 'accord:invoke:constitutional:{halt_id} attest'
      roles: 'holder'
      policy: 'mirror'
      confirm: 'true'
    ```

- `var` must be a variable of STATE_TABLE.tsv, and `states` must be EXACTLY its
  states: a card that leaves a modelled state out draws a world the model does
  not have, and one that adds a state invents one.
- `code` + `code.<state>`: for every modelled state, either a regex that
  matches in `code` (the client represents it) or a `csd:code_gaps` entry in
  the same card whose `model:` names `<var>.<state>`. A state the code cannot
  represent and nobody declared is the failure this exists to catch.
- `act` is `<family> <verb>` of transitions.json. `roles`, `policy` and
  `confirm`, when given, must equal the manifest's values. The card's "who
  signs" and "is it confirmed" are then the model's, not a retyping of it.

Re-pinning (`--vendor`) is how the model moves the cards: every claim that no
longer holds fails in the same commit as the new pin. Stdlib only (AGENTS.md);
the block grammar is the strict YAML subset csd:code_gaps uses.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tempfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_csd_code_gaps as code_gaps  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
CSD_DIR = Path("FSD/CSD")
MODEL_DIR = Path("packaging/model")
PIN = "PIN.tsv"
REPO = "CIRISAI/CIRISConstitution"
BRANCH = "rc22"
FILES = {"STATE_TABLE.tsv": "formal/STATE_TABLE.tsv", "transitions.json": "manifests/transitions.json"}
BLOCK_RE = re.compile(r"^```yaml csd:model[ \t]*\n(.*?)^```", re.S | re.M)
ENTRY = re.compile(r"^- (var|act):\s*(.+?)\s*$")
FIELD = re.compile(r"^  ([a-z_]+(?:\.[A-Za-z0-9_]+)?):\s*(.*?)\s*$")
VAR_KEYS = {"states", "code"}
ACT_KEYS = {"roles", "policy", "confirm"}


class ModelError(Exception):
    pass


def _unq(v: str) -> str:
    return code_gaps._unquote(v)


# ---- the pinned model -------------------------------------------------------

def load_model(root: Path) -> tuple[dict[str, list[str]], dict[tuple[str, str], dict]]:
    d = root / MODEL_DIR
    states: dict[str, list[str]] = {}
    lines = (d / "STATE_TABLE.tsv").read_text(encoding="utf-8").splitlines()
    head = lines[0].split("\t")
    if "variable" not in head or "state" not in head:
        raise ModelError("STATE_TABLE.tsv: no variable/state columns — the table's shape moved")
    vi, si = head.index("variable"), head.index("state")
    for ln in lines[1:]:
        c = ln.split("\t")
        if len(c) > max(vi, si) and c[vi] and not ln.startswith("#"):
            states.setdefault(c[vi], [])
            if c[si] not in states[c[vi]]:
                states[c[vi]].append(c[si])
    acts: dict[tuple[str, str], dict] = {}
    for fam in json.loads((d / "transitions.json").read_text(encoding="utf-8"))["rows"]:
        for t in fam.get("transitions", []):
            acts[(fam["family"], t["verb"])] = t
    if not states or not acts:
        raise ModelError(f"model parsed {len(states)} variables and {len(acts)} transitions — a broken parser, not an empty model")
    return states, acts


def read_pin(root: Path) -> dict:
    rows = [l.split("\t") for l in (root / MODEL_DIR / PIN).read_text().splitlines() if l and not l.startswith("#")]
    pin = {r[0]: r[1] for r in rows if r[0] in ("repo", "commit")}
    pin["files"] = {r[1]: r[2] for r in rows if r[0] == "file"}
    return pin


def verify_pin(root: Path) -> int:
    pin = read_pin(root)
    bad = 0
    for name in FILES:
        have = hashlib.sha256((root / MODEL_DIR / name).read_bytes()).hexdigest()
        if pin["files"].get(name) != have:
            print(f"[FAIL] {MODEL_DIR / name}: sha256 {have[:16]}… is not the pin's {pin['files'].get(name, '?')[:16]}…")
            bad += 1
    if bad:
        return 1
    print(f"[OK] model pin: {', '.join(FILES)} at {pin['repo']}@{pin['commit'][:9]}")
    return 0


def _fetch(ref: str, path: str) -> bytes:
    with urllib.request.urlopen(f"https://raw.githubusercontent.com/{REPO}/{ref}/{path}", timeout=60) as r:
        return r.read()


def vendor(root: Path, commit: str) -> int:
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        print("[FAIL] --vendor takes a full 40-hex commit of a PUSHED CIRISConstitution branch")
        return 1
    rows = [f"repo\t{REPO}", f"commit\t{commit}"]
    for name, path in FILES.items():
        b = _fetch(commit, path)
        (root / MODEL_DIR / name).write_bytes(b)
        rows.append(f"file\t{name}\t{hashlib.sha256(b).hexdigest()}\t{path}")
    (root / MODEL_DIR / PIN).write_text("# GENERATED by check_csd_model.py --vendor. Do not edit.\n" + "\n".join(rows) + "\n")
    print(f"vendored {REPO}@{commit[:9]}; now run the gate and fix every card it names in this commit")
    return 0


def upstream(root: Path) -> int:
    pin = read_pin(root)
    behind = [n for n, p in FILES.items()
              if hashlib.sha256(_fetch(BRANCH, p)).hexdigest() != pin["files"].get(n)]
    if behind:
        print(f"[FAIL] the model pin ({pin['commit'][:9]}) is behind {REPO}@{BRANCH}: {', '.join(behind)} changed. "
              f"Re-pin with --vendor <commit> and fix the cards it names.")
        return 1
    print(f"[OK] model pin matches {REPO}@{BRANCH}")
    return 0


# ---- the cards --------------------------------------------------------------

def parse_card(text: str, name: str) -> list[dict]:
    out: list[dict] = []
    blocks = BLOCK_RE.findall(text)
    if "csd:model" in text and not blocks:
        raise ModelError(f"{name}: mentions a csd:model fence but none parses")
    for body in blocks:
        cur = None
        for ln, line in enumerate(body.splitlines(), 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            m = ENTRY.match(line)
            if m:
                cur = {"_kind": m.group(1), "_id": _unq(m.group(2))}
                out.append(cur)
                continue
            f = FIELD.match(line)
            if not f or cur is None:
                raise ModelError(f"{name}: csd:model line {ln} is not `- var|act: …` or `  key: value`: {line!r}")
            k = f.group(1)
            allowed = VAR_KEYS if cur["_kind"] == "var" else ACT_KEYS
            if k not in allowed and not (cur["_kind"] == "var" and k.startswith("code.")):
                raise ModelError(f"{name}: {cur['_kind']} {cur['_id']!r}: unknown key {k!r}")
            if k in cur:
                raise ModelError(f"{name}: {cur['_kind']} {cur['_id']!r}: {k} twice")
            cur[k] = _unq(f.group(2))
        if not out:
            raise ModelError(f"{name}: an empty csd:model block")
    return out


def check_card(root: Path, name: str, text: str, states: dict, acts: dict) -> list[str]:
    p: list[str] = []
    entries = parse_card(text, name)
    if not entries:
        return p
    declared = {a for g in code_gaps.parse_card(text, name) for a in g.get("model", "").split()}
    for e in entries:
        if e["_kind"] == "var":
            v = e["_id"]
            if v not in states:
                p.append(f"{name}: var {v!r} is not in the model's STATE_TABLE")
                continue
            want = states[v]
            have = e.get("states", "").split()
            if set(have) != set(want):
                miss, extra = [s for s in want if s not in have], [s for s in have if s not in want]
                p.append(f"{name}: {v} draws {have}; the model's states are {want}"
                         + (f" — missing {miss}" if miss else "") + (f" — not in the model {extra}" if extra else ""))
            if "code" in e:
                files = [f for f in sorted(root.glob(e["code"])) if f.is_file()]
                if not files:
                    p.append(f"{name}: {v}: code {e['code']!r} matches no file")
                    continue
                src = "\n".join(f.read_text(encoding="utf-8", errors="replace") for f in files)
                for s in want:
                    rx = e.get(f"code.{s}")
                    ok = False
                    if rx:
                        try:
                            ok = re.search(rx, src, re.M) is not None
                        except re.error as err:
                            p.append(f"{name}: {v}.{s}: bad regex {rx!r}: {err}")
                            continue
                    if ok and f"{v}.{s}" in declared:
                        p.append(f"{name}: {v}.{s} is represented in code ({rx!r}) but a code gap still declares it — delete the gap")
                    elif not ok and f"{v}.{s}" not in declared:
                        why = f"code.{s} {rx!r} no longer matches" if rx else f"no code.{s}"
                        p.append(f"{name}: model state {v}.{s} has no representation in {e['code']} ({why}) "
                                 f"and no csd:code_gaps entry with model: {v}.{s}")
        else:
            parts = e["_id"].rsplit(" ", 1)
            if len(parts) != 2 or tuple(parts) not in acts:
                p.append(f"{name}: act {e['_id']!r} is not a (family, verb) of the model's transitions")
                continue
            t = acts[tuple(parts)]
            if "roles" in e and set(e["roles"].split()) != set(t["roles"]["value"]):
                p.append(f"{name}: act {e['_id']!r} says roles {e['roles'].split()}; the model says {t['roles']['value']}")
            if "policy" in e and e["policy"] != t["policy"]["value"]["policy"]:
                p.append(f"{name}: act {e['_id']!r} says policy {e['policy']!r}; the model says {t['policy']['value']['policy']!r}")
            if "confirm" in e and (e["confirm"] == "true") != bool(t["confirm"]["value"]):
                p.append(f"{name}: act {e['_id']!r} says confirm {e['confirm']}; the model says {t['confirm']['value']}")
    return p


def check(root: Path) -> int:
    try:
        states, acts = load_model(root)
        problems, n_cards, n = [], 0, 0
        for f in sorted((root / CSD_DIR).glob("CSD-*.md")):
            text = f.read_text(encoding="utf-8")
            entries = parse_card(text, f.name)
            if entries:
                n_cards += 1
                n += len(entries)
                problems += check_card(root, f.name, text, states, acts)
    except (ModelError, code_gaps.GapError) as err:
        print(f"[FAIL] csd:model: {err}")
        return 1
    for x in problems:
        print(f"[FAIL] {x}")
    if problems:
        print(f"[FAIL] csd:model: {len(problems)} disagreement(s) with the pinned model")
        return 1
    print(f"[OK] csd:model: {n} citation(s) on {n_cards} card(s) agree with the pinned model "
          f"({len(states)} variables, {len(acts)} transitions)")
    return 0


def self_test() -> int:
    def tree(model: str, gaps: str = "", code: str = "val halted: Boolean\n") -> Path:
        d = Path(tempfile.mkdtemp())
        (d / CSD_DIR).mkdir(parents=True)
        (d / MODEL_DIR).mkdir(parents=True)
        (d / "src").mkdir()
        (d / "src/A.kt").write_text(code)
        (d / MODEL_DIR / "STATE_TABLE.tsv").write_text(
            "plane\tentity\tvariable\tstate\n" + "".join(f"root\taccord\troot.halt\t{s}\n" for s in ("not_halted", "latched_lone", "confirmed")))
        (d / MODEL_DIR / "transitions.json").write_text(json.dumps({"rows": [{"family": "accord:x", "transitions": [
            {"verb": "attest", "roles": {"value": ["holder"]}, "policy": {"value": {"policy": "mirror"}}, "confirm": {"value": True}}]}]}))
        (d / CSD_DIR / "CSD-001-x.md").write_text(
            "# CSD-001 — X\n\n" + (f"```yaml csd:model\n{model}```\n" if model else "")
            + (f"```yaml csd:code_gaps\n{gaps}```\n" if gaps else ""))
        return d

    var = ("- var: root.halt\n  states: 'not_halted latched_lone confirmed'\n  code: src/*.kt\n"
           "  code.not_halted: 'val halted'\n")
    gap = lambda s: (f"- id: g{s}\n  card: 'c'\n  code: 'k'\n  where: src/*.kt\n  open_until: 'NEVER'\n  model: root.halt.{s}\n")
    clean_var = var
    clean_gaps = gap("latched_lone") + gap("confirmed")
    act = "- act: 'accord:x attest'\n  roles: 'holder'\n  policy: 'mirror'\n  confirm: 'true'\n"
    plants = {
        "a card missing a modelled state": tree(var.replace(" confirmed'", "'"), clean_gaps),
        "a card inventing a state": tree(var.replace("confirmed'", "confirmed lapsed'"), clean_gaps),
        "a var the model lacks": tree("- var: root.nope\n  states: 'a'\n"),
        "a state with no code and no gap": tree(clean_var, gap("latched_lone")),
        "a gap declared for a state the code has": tree(clean_var, clean_gaps + gap("not_halted").replace("gnot_halted", "gx")),
        "code that no longer matches": tree(clean_var, clean_gaps, code="val other = 1\n"),
        "an act the model lacks": tree("- act: 'accord:y attest'\n"),
        "wrong roles": tree(act.replace("'holder'", "'owner'")),
        "wrong policy": tree(act.replace("'mirror'", "'selfAct'")),
        "wrong confirm": tree(act.replace("'true'", "'false'")),
        "an unknown key": tree(act + "  note: 'x'\n"),
        "an empty block": tree(" \n"),
    }
    ok = check(tree(clean_var + act, clean_gaps)) == 0 and check(tree("")) == 0
    if not ok:
        print("[self-test FAIL] a clean tree did not pass")
    for what, d in plants.items():
        rc = check(d)
        print(f"[self-test] {what}: {'red, as it must be' if rc else 'GREEN — the check missed it'}")
        ok &= rc != 0
    print("[OK] self-test: every planted defect went red" if ok else "[FAIL] self-test")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--verify-pin", action="store_true")
    ap.add_argument("--upstream", action="store_true")
    ap.add_argument("--vendor", metavar="COMMIT")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.vendor:
        return vendor(ROOT, a.vendor)
    if a.verify_pin:
        return verify_pin(ROOT)
    if a.upstream:
        return upstream(ROOT)
    return check(ROOT)


if __name__ == "__main__":
    sys.exit(main())
