#!/usr/bin/env python3
"""Where a card says one thing and the code does another: every known gap, checked.

    python3 packaging/check_csd_code_gaps.py             # gate: every declared gap is still real
    python3 packaging/check_csd_code_gaps.py --list      # the gaps, card by card
    python3 packaging/check_csd_code_gaps.py --self-test

WHY. A card is written ahead of the code on purpose: the v62 trust-root audit
(2026-11-21) rewrote sixteen cards to say what v62 does, while the client still
offers the 0.5.x menu, reads `charter_quorum` and calls routes v62 retires.
Prose in the card ("the built menu still offers…") is read by nobody who is not
already reading that paragraph, and it goes stale silently the day the code
changes. So each card that is ahead of (or behind) its code says so in a typed
block, and this check proves every entry against the tree:

    ```yaml csd:code_gaps
    - id: halt-kinds
      card: 'five kinds; lifecycle:confirmed is the confirmation of a halt in force'
      code: 'InvocationKind has four kinds and no LIFECYCLE_CONFIRMED'
      where: client/shared/src/commonMain/kotlin/ai/ciris/mobile/shared/models/federation/Accord.kt
      open_until: 'LIFECYCLE_CONFIRMED'
      blocked_on: 'O1 (0.6 route shapes)'
    ```

- `where` is a path or glob from the repo root (`**` crosses directories) and
  must match at least one file. A pointer at nothing is a broken entry, not an
  open gap.
- Exactly one of `open_while` (a regex found in `where` while the gap is open:
  the old behaviour) or `open_until` (a regex NOT found while it is open: the
  new behaviour's marker).
- The gate FAILS when a gap has closed and the entry is still there. Closing a
  gap means deleting its entry in the same commit, so the list is always the
  list of real mismatches and never a history of old ones.
- `blocked_on` and `owner` are optional and only rendered.

What this does NOT do: find a mismatch nobody declared. It makes a declared
mismatch visible (the roster's Code gaps column, `--list`) and keeps it honest.

The value grammar is a strict subset of YAML (one `key: value` per line, values
plain or single-quoted) so this stays stdlib-only, like every check in
packaging/ (AGENTS.md), and check_csd_v3's PyYAML reads the same block.
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CSD_DIR = Path("FSD/CSD")
BLOCK_RE = re.compile(r"^```yaml csd:code_gaps[ \t]*\n(.*?)^```", re.S | re.M)
ENTRY_START = re.compile(r"^- id:\s*(.+?)\s*$")
FIELD = re.compile(r"^  ([a-z_]+):\s*(.*?)\s*$")
REQUIRED = ("id", "card", "code", "where")
OPTIONAL = ("open_while", "open_until", "blocked_on", "owner")


class GapError(Exception):
    pass


def _unquote(v: str) -> str:
    if len(v) >= 2 and v[0] == v[-1] == "'":
        return v[1:-1].replace("''", "'")
    if len(v) >= 2 and v[0] == v[-1] == '"':
        raise GapError(f"double-quoted value {v!r}: use single quotes (no escapes to misread)")
    return v


def parse_card(text: str, name: str) -> list[dict]:
    """The entries of a card's `csd:code_gaps` blocks. Malformed is an error, never 'no gaps'."""
    entries: list[dict] = []
    blocks = BLOCK_RE.findall(text)
    if "csd:code_gaps" in text and not blocks:
        raise GapError(f"{name}: mentions a csd:code_gaps fence but none parses (```yaml csd:code_gaps … ```)")
    for body in blocks:
        cur: dict | None = None
        found = 0
        for ln, line in enumerate(body.splitlines(), 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            m = ENTRY_START.match(line)
            if m:
                cur = {"id": _unquote(m.group(1)), "_card": name}
                entries.append(cur)
                found += 1
                continue
            f = FIELD.match(line)
            if not f or cur is None:
                raise GapError(f"{name}: csd:code_gaps line {ln} is not `- id: …` or `  key: value`: {line!r}")
            k, v = f.group(1), _unquote(f.group(2))
            if k not in REQUIRED + OPTIONAL:
                raise GapError(f"{name}: gap {cur['id']!r}: unknown key {k!r} (known: {REQUIRED + OPTIONAL})")
            if k in cur:
                raise GapError(f"{name}: gap {cur['id']!r}: {k} given twice")
            cur[k] = v
        if not found:
            raise GapError(f"{name}: an empty csd:code_gaps block — delete it, or declare a gap")
    seen = set()
    for e in entries:
        for k in REQUIRED:
            if not e.get(k):
                raise GapError(f"{name}: gap {e.get('id')!r}: missing {k}")
        if ("open_while" in e) == ("open_until" in e):
            raise GapError(f"{name}: gap {e['id']!r}: give exactly one of open_while / open_until")
        if e["id"] in seen:
            raise GapError(f"{name}: gap id {e['id']!r} twice")
        seen.add(e["id"])
    return entries


def read_all(root: Path) -> list[dict]:
    files = sorted((root / CSD_DIR).glob("CSD-*.md"))
    if not files:
        raise GapError(f"{root / CSD_DIR}: no CSD-*.md — a check of nothing is a broken path, not a pass")
    out: list[dict] = []
    for f in files:
        out += parse_card(f.read_text(encoding="utf-8"), f.name)
    return out


def count_by_card(csd: Path) -> dict[str, int]:
    """For the roster: entries per card file. Parse errors surface in this gate, not there."""
    out: dict[str, int] = {}
    for f in sorted(csd.glob("CSD-*.md")):
        try:
            out[f.name] = len(parse_card(f.read_text(encoding="utf-8"), f.name))
        except GapError:
            out[f.name] = -1
    return out


def status(root: Path, e: dict) -> tuple[str, str]:
    """('open' | 'closed' | 'broken', why)."""
    files = [p for p in sorted(root.glob(e["where"])) if p.is_file()]
    if not files:
        return "broken", f"where {e['where']!r} matches no file"
    pat = e.get("open_while") or e.get("open_until")
    try:
        rx = re.compile(pat, re.M)
    except re.error as err:
        return "broken", f"regex {pat!r}: {err}"
    hits = [str(p.relative_to(root)) for p in files if rx.search(p.read_text(encoding="utf-8", errors="replace"))]
    if "open_while" in e:
        return ("open", f"{len(hits)} file(s) still match") if hits else (
            "closed", f"open_while {pat!r} no longer matches {e['where']}")
    return ("closed", f"open_until {pat!r} now matches {', '.join(hits[:3])}") if hits else (
        "open", "the new behaviour's marker is absent")


def check(root: Path, listing: bool = False) -> int:
    try:
        gaps = read_all(root)
    except GapError as err:
        print(f"[FAIL] csd:code_gaps: {err}")
        return 1
    bad = 0
    for e in gaps:
        st, why = status(root, e)
        if listing:
            print(f"{e['_card']}  [{st}] {e['id']}\n    card: {e['card']}\n    code: {e['code']}"
                  + (f"\n    blocked on: {e['blocked_on']}" if e.get("blocked_on") else ""))
        if st == "closed":
            print(f"[FAIL] {e['_card']}: gap {e['id']!r} has CLOSED ({why}). Delete the entry "
                  f"(and the card's prose that says the code lags) in the same commit.")
            bad += 1
        elif st == "broken":
            print(f"[FAIL] {e['_card']}: gap {e['id']!r} is broken: {why}")
            bad += 1
    cards = len({e["_card"] for e in gaps})
    if bad:
        print(f"[FAIL] csd:code_gaps: {bad} of {len(gaps)} entries need attention")
        return 1
    print(f"[OK] csd:code_gaps: {len(gaps)} open gap(s) on {cards} card(s), each still true of the tree")
    return 0


def self_test() -> int:
    """Each planted defect goes red; a clean tree goes green."""
    def tree(gaps: str, code: str = "enum class K { A, B }\n") -> Path:
        d = Path(tempfile.mkdtemp())
        (d / CSD_DIR).mkdir(parents=True)
        (d / "src").mkdir()
        (d / "src/K.kt").write_text(code)
        (d / CSD_DIR / "CSD-001-x.md").write_text(
            "# CSD-001 — X\n\n```yaml csd:stage\nstage: sketched\nowner: t\n```\n\n"
            + (f"```yaml csd:code_gaps\n{gaps}```\n" if gaps is not None else ""))
        return d

    good = "- id: g\n  card: 'C has three'\n  code: 'K has two'\n  where: src/*.kt\n  open_until: '\\bC\\b'\n"
    plants = {
        "a gap whose new marker appeared (open_until matches)": tree(good, "enum class K { A, B, C }\n"),
        "a gap whose old behaviour is gone (open_while no match)":
            tree("- id: g\n  card: 'c'\n  code: 'k'\n  where: src/*.kt\n  open_while: 'GONE'\n"),
        "where matches no file": tree(good.replace("src/*.kt", "nope/*.kt")),
        "both open_while and open_until": tree(good + "  open_while: 'A'\n"),
        "neither open_while nor open_until": tree("- id: g\n  card: 'c'\n  code: 'k'\n  where: src/*.kt\n"),
        "a missing required key": tree("- id: g\n  card: 'c'\n  where: src/*.kt\n  open_while: 'A'\n"),
        "an unknown key": tree(good + "  note: 'x'\n"),
        "a duplicate id": tree(good + good),
        "an empty block": tree(""),
        "a line that is not key: value": tree(good + "  just words\n"),
        "a bad regex": tree(good.replace("'\\bC\\b'", "'(['")),
    }
    ok = check(tree(good)) == 0 and check(tree(None)) == 0
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
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    return check(ROOT, listing=a.list)


if __name__ == "__main__":
    sys.exit(main())
