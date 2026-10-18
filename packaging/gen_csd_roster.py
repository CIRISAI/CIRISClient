#!/usr/bin/env python3
"""The CSD roster: every card number, whether it is a card, reserved, or retired.

    python3 packaging/gen_csd_roster.py            # write FSD/CSD/README.md
    python3 packaging/gen_csd_roster.py --check    # gate: README current, no number reused
    python3 packaging/gen_csd_roster.py --self-test

WHY. Card numbers were being reserved in issue text (CIRISClient#179 holds
111-119 and 127-128) and in another repo (CIRISRegistry drafted 120-126 for the
CIRISPortal fold, CIRISClient#206), and retired inside the card that absorbed
them (CSD-034 lives on only as a sentence in CSD-046). Nothing in the tree said
which numbers were taken, so the next card could take one.

So the roster is GENERATED: the cards on disk say what is adopted (number,
title, stage, flow), and `FSD/CSD/numbers.tsv` — hand-maintained, one row per
number — says what is reserved, retired, or kept for test fixtures. `--check`
fails when the README is stale, when two files share a number, when a file's
heading disagrees with its filename, and when a card lands on a reserved or
retired number (adopting a reserved card means deleting its row in the same
commit; a retired number is never reused).

Stdlib only, like every check in packaging/ (AGENTS.md).
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CSD_DIR = Path("FSD/CSD")
NUMBERS = "numbers.tsv"
README = "README.md"

FILE_RE = re.compile(r"^CSD-(\d{3})-[a-z0-9-]+\.md$")
HEAD_RE = re.compile(r"^# CSD-(\d{3}) — (.+)$")
STAGE_RE = re.compile(r"^```yaml csd:stage\s*\n(?:.*\n)*?stage:\s*([a-z]+)\s*$", re.M)
FLOW_RE = re.compile(r"^\*\*Flow\*\*:\s*(.*)$", re.M)
STATUSES = ("reserved", "retired", "test")
# Must match check_csd_v3.STAGES; cross-checked below rather than imported,
# because check_csd_v3 needs PyYAML and this check must run before installs.
STAGES = ("envisioned", "sketched", "building", "testable", "verified", "shipped")


class RosterError(Exception):
    pass


def _v3_stages(root: Path) -> tuple[str, ...]:
    text = (root / "packaging/check_csd_v3.py").read_text()
    m = re.search(r"^STAGES\s*=\s*\(([^)]*)\)", text, re.M) or re.search(
        r"^STAGES\s*=\s*\[([^\]]*)\]", text, re.M)
    if not m:
        raise RosterError("packaging/check_csd_v3.py: no STAGES tuple found — the stage list this "
                          "roster mirrors has moved; update _v3_stages")
    return tuple(re.findall(r"[\"']([a-z]+)[\"']", m.group(1)))


def read_cards(csd: Path) -> dict[int, dict]:
    cards: dict[int, dict] = {}
    problems = []
    for f in sorted(csd.glob("CSD-*.md")):
        m = FILE_RE.match(f.name)
        if not m:
            problems.append(f"{f.name}: not CSD-NNN-slug.md")
            continue
        n = int(m.group(1))
        text = f.read_text()
        h = HEAD_RE.match(text.splitlines()[0] if text else "")
        if not h:
            problems.append(f"{f.name}: first line is not '# CSD-NNN — Title'")
            continue
        if int(h.group(1)) != n:
            problems.append(f"{f.name}: heading says CSD-{h.group(1)}, filename says CSD-{n:03d}")
        s = STAGE_RE.search(text)
        if not s:
            problems.append(f"{f.name}: no `yaml csd:stage` block with a stage")
            continue
        if s.group(1) not in STAGES:
            problems.append(f"{f.name}: stage {s.group(1)!r} is not one of {STAGES}")
        flow = "—"
        fm = FLOW_RE.search(text)
        if fm:
            p = re.search(r"`(testing/flows/[^`]+)`", fm.group(1))
            flow = f"`{p.group(1)}`" if p else "none yet"
        if n in cards:
            problems.append(f"{f.name}: CSD-{n:03d} is also {cards[n]['file']}")
            continue
        cards[n] = {"file": f.name, "title": h.group(2).strip(), "stage": s.group(1), "flow": flow}
    if not cards and not problems:
        raise RosterError(f"{csd}: no CSD-*.md parsed — a roster of nothing is a broken parser, not an empty set")
    if problems:
        raise RosterError("\n".join(problems))
    return cards


def read_numbers(csd: Path) -> list[dict]:
    path = csd / NUMBERS
    lines = path.read_text().splitlines()
    if not lines or lines[0].split("\t") != ["number", "status", "title", "source", "note"]:
        raise RosterError(f"{path}: header must be number/status/title/source/note")
    rows, seen, problems = [], set(), []
    for i, line in enumerate(lines[1:], 2):
        if not line.strip():
            continue
        cols = line.split("\t")
        if len(cols) != 5:
            problems.append(f"{NUMBERS}:{i}: {len(cols)} columns, expected 5")
            continue
        num, status, title, source, note = cols
        m = re.fullmatch(r"(\d{3})(?:-(\d{3}))?", num)
        if not m:
            problems.append(f"{NUMBERS}:{i}: number {num!r} is not NNN or NNN-NNN")
            continue
        lo, hi = int(m.group(1)), int(m.group(2) or m.group(1))
        if status not in STATUSES:
            problems.append(f"{NUMBERS}:{i}: status {status!r} is not one of {STATUSES}")
        if status == "test" and lo < 900:
            problems.append(f"{NUMBERS}:{i}: test numbers live at 900 and above")
        span = set(range(lo, hi + 1))
        if span & seen:
            problems.append(f"{NUMBERS}:{i}: {num} overlaps an earlier row")
        seen |= span
        rows.append({"lo": lo, "hi": hi, "num": num, "status": status, "title": title,
                     "source": source, "note": note})
    if problems:
        raise RosterError("\n".join(problems))
    return rows


def cross_check(cards: dict[int, dict], rows: list[dict]) -> None:
    problems = []
    for r in rows:
        for n in range(r["lo"], r["hi"] + 1):
            if n not in cards:
                continue
            if r["status"] == "reserved":
                problems.append(f"{cards[n]['file']}: CSD-{n:03d} is reserved in {NUMBERS} ({r['source']}) — "
                                f"if this IS that card, delete its row in the same commit; if not, pick a free number")
            else:
                problems.append(f"{cards[n]['file']}: CSD-{n:03d} is {r['status']} in {NUMBERS} — "
                                f"a retired number is never reused; pick a free number")
    if problems:
        raise RosterError("\n".join(problems))


def _ranges(nums: list[int]) -> str:
    out, start = [], None
    for i, n in enumerate(nums):
        if start is None:
            start = n
        if i + 1 == len(nums) or nums[i + 1] != n + 1:
            out.append(f"{start:03d}" if start == n else f"{start:03d}–{n:03d}")
            start = None
    return ", ".join(out) or "none"


ISSUES = "https://github.com/CIRISAI/CIRISClient/issues/"


def _link(source: str) -> str:
    """`#179` -> a link; a file in this directory -> a relative link; else as written."""
    return re.sub(r"(?<![\w/])#(\d+)\b", lambda m: f"[#{m.group(1)}]({ISSUES}{m.group(1)})", source)


def render(cards: dict[int, dict], rows: list[dict]) -> str:
    by_stage = {s: sum(1 for c in cards.values() if c["stage"] == s) for s in STAGES}
    reserved = [r for r in rows if r["status"] == "reserved"]
    retired = [r for r in rows if r["status"] == "retired"]
    test = [r for r in rows if r["status"] == "test"]
    taken = set(cards) | {n for r in rows for n in range(r["lo"], r["hi"] + 1)}
    top = max([n for n in taken if n < 900] or [0])
    unassigned = [n for n in range(1, top + 1) if n not in taken]

    L = [
        "# CSD roster",
        "",
        "<!-- GENERATED by packaging/gen_csd_roster.py from the cards in this directory and",
        "     numbers.tsv. Do not edit by hand: edit numbers.tsv or the card, then run",
        "     `python3 packaging/gen_csd_roster.py`. CI runs it with --check. -->",
        "",
        "Every card number: the cards in this directory, the numbers reserved for cards not",
        "written here yet, and the numbers retired into other cards. The standard every card",
        "follows is [`CSD.md`](../../CSD.md).",
        "",
        "**To reserve a number**, add a `reserved` row to [`numbers.tsv`](numbers.tsv) naming",
        "where the card is drafted or asked for. **To adopt a reserved card**, add its file and",
        "delete its row in the same commit; `--check` refuses a card on a number that is still",
        "reserved. **A retired number is never reused.**",
        "",
        f"**{len(cards)} cards** — " + " · ".join(f"{by_stage[s]} {s}" for s in STAGES if by_stage[s]) +
        f". **{sum(r['hi'] - r['lo'] + 1 for r in reserved)} reserved**, "
        f"**{sum(r['hi'] - r['lo'] + 1 for r in retired)} retired**.",
        "",
        "## Cards",
        "",
        "| CSD | Card | Stage | Flow |",
        "|---|---|---|---|",
    ]
    for n in sorted(cards):
        c = cards[n]
        title = c["title"].replace("|", "\\|")
        L.append(f"| [CSD-{n:03d}]({c['file']}) | {title} | `{c['stage']}` | {c['flow']} |")
    L += ["", "## Reserved", "",
          "Numbers held for cards that are drafted elsewhere or asked for in an issue, and not in",
          "this directory yet.", "",
          "| CSD | Card | Reserved by | Note |", "|---|---|---|---|"]
    for r in reserved:
        L.append(f"| CSD-{r['num']} | {r['title']} | {_link(r['source'])} | {r['note']} |")
    L += ["", "## Retired", "", "| CSD | Was | Folded into | Note |", "|---|---|---|---|"]
    for r in retired:
        L.append(f"| CSD-{r['num']} | {r['title']} | {r['source']} | {r['note']} |")
    L += ["", "## Kept for tests", ""]
    for r in test:
        L.append(f"- CSD-{r['num'].replace('-', '–')}: {r['title']} — {r['note']}")
    L += ["", "## Unassigned", "",
          f"Below CSD-{top:03d}, these numbers are free: {_ranges(unassigned)}. Numbers above "
          f"CSD-{top:03d} are free too, up to the test range.", ""]
    return "\n".join(L)


def build(root: Path) -> str:
    v3 = _v3_stages(root)
    if v3 != STAGES:
        raise RosterError(f"stage list drifted from check_csd_v3.py: {v3} != {STAGES}")
    csd = root / CSD_DIR
    cards, rows = read_cards(csd), read_numbers(csd)
    cross_check(cards, rows)
    return render(cards, rows)


def check(root: Path) -> int:
    try:
        want = build(root)
    except RosterError as e:
        print(f"[FAIL] CSD roster:\n{e}")
        return 1
    have_path = root / CSD_DIR / README
    have = have_path.read_text() if have_path.exists() else ""
    if have != want:
        print(f"[FAIL] {CSD_DIR / README} is stale — run `python3 packaging/gen_csd_roster.py`")
        return 1
    print(f"[OK] CSD roster current: every card listed, no reserved or retired number reused")
    return 0


def self_test() -> int:
    """Plant each defect in a temp copy; each must go red, and the clean copy green."""
    def copy() -> Path:
        d = Path(tempfile.mkdtemp())
        (d / "packaging").mkdir()
        shutil.copy(ROOT / "packaging/check_csd_v3.py", d / "packaging/check_csd_v3.py")
        shutil.copytree(ROOT / CSD_DIR, d / CSD_DIR)
        (d / CSD_DIR / README).write_text(build(d))
        return d

    def card(d: Path, name: str, num: str, stage: str = "envisioned") -> None:
        (d / CSD_DIR / name).write_text(
            f"# CSD-{num} — Planted\n\n```yaml csd:stage\nstage: {stage}\nowner: test\n```\n")

    plants = {
        "a card on a reserved number": lambda d: card(d, "CSD-120-planted.md", "120"),
        "a card on a retired number": lambda d: card(d, "CSD-034-planted.md", "034"),
        "two files, one number": lambda d: card(d, "CSD-005-planted.md", "005"),
        "a heading that disagrees with its filename": lambda d: card(d, "CSD-197-planted.md", "198"),
        "a card with no stage": lambda d: (d / CSD_DIR / "CSD-197-planted.md").write_text("# CSD-197 — Planted\n"),
        "a stale README": lambda d: card(d, "CSD-197-planted.md", "197"),
        "an overlapping numbers row": lambda d: (d / CSD_DIR / NUMBERS).write_text(
            (d / CSD_DIR / NUMBERS).read_text() + "111\treserved\tdup\t#0\tdup\n"),
    }
    ok = True
    d = copy()
    if check(d) != 0:
        print("[self-test FAIL] the clean copy did not pass")
        ok = False
    for what, plant in plants.items():
        d = copy()
        plant(d)
        rc = check(d)
        print(f"[self-test] {what}: {'red, as it must be' if rc else 'GREEN — the check missed it'}")
        ok &= rc != 0
    print("[OK] self-test: every planted defect went red" if ok else "[FAIL] self-test")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.check:
        return check(ROOT)
    try:
        text = build(ROOT)
    except RosterError as e:
        print(f"[FAIL] CSD roster:\n{e}")
        return 1
    (ROOT / CSD_DIR / README).write_text(text)
    print(f"wrote {CSD_DIR / README}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
