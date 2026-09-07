#!/usr/bin/env python3
"""A weighted child next to a fillMaxWidth() sibling measures to ZERO.

    python3 client/tools/check_row_layout.py          # fail on any offender
    python3 client/tools/check_row_layout.py --list   # show them

THE DEFECT (CIRISClient#42)
---------------------------
`Row` measures its UNWEIGHTED children first, against the full incoming
width, and distributes what REMAINS to the weighted ones. So a direct child
carrying `fillMaxWidth()` consumes the entire row, and every `weight()`
sibling is measured at zero width.

In the setup wizard's age question that meant BOTH age bands rendered at zero
width. Nobody saw it: they were still composed, still registered a click
handler, and `/click` fell back to a coordinate gamble at a zero-size rect --
so automation appeared to work and a person simply could not see the control.
It surfaced only when 0.5.206 started reporting visibility honestly, and it
had been shipping since 0.5.203.

WHY A SCRIPT AND NOT A CODE REVIEW
----------------------------------
It is invisible in the diff. The two modifiers are on different children,
often dozens of lines apart, and each is idiomatic alone. It is only wrong in
combination, which is exactly what a machine is better at noticing.

SCOPE: DIRECT children of a Row only. A `fillMaxWidth()` inside a child
Column -- including a weighted one -- is correct and common, so counting it
would make this noisy, and a noisy check gets switched off.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "shared" / "src"

ROW_RE = re.compile(r"\bRow\s*\(")


# Constructs whose braces do NOT introduce a layout level. A child written
# inside `options.forEach { ... }` is still measured by the Row, which is
# exactly how CIRISClient#42 hid: the two weighted bands were inside a forEach
# and the fillMaxWidth() sibling was written plainly, so no "direct child"
# reading of the source put them side by side.
TRANSPARENT = re.compile(
    r"^(?:\w+\.)?(?:forEach|forEachIndexed|for|if|else|when|repeat|map|let|also|apply|run|take\w*|filter\w*)\b"
)
# Composable calls that DO introduce a layout level: their children belong to
# them, not to the Row.
CONTAINER = re.compile(
    r"^(Row|Column|Box|Surface|Card|Button|OutlinedButton|TextButton|IconButton|LazyRow|LazyColumn|"
    r"FlowRow|Scaffold|Dialog|AlertDialog|ElevatedCard|OutlinedCard|Spacer|Text|Icon|Image|"
    r"RadioButton|Checkbox|Switch|TextField|OutlinedTextField|CircularProgressIndicator|Divider|HorizontalDivider)\b"
)


def layout_children(lines: list[str], row_open: int) -> list[tuple[int, str]]:
    """(line, head) for every child the Row MEASURES.

    Sees through control flow; stops at nested layout containers.
    `head` is the child's own argument list, which is where its modifier lives.
    """
    depth = 0
    started = False
    # Each open brace pushes whether it introduced a layout level.
    stack: list[bool] = []
    children: list[tuple[int, str]] = []
    # A container's `{` often opens SEVERAL LINES after its call, e.g.
    #     ExposedDropdownMenuBox(
    #         modifier = Modifier.weight(2f)
    #     ) {
    # so the flag has to survive until a brace consumes it. Dropping it at
    # end-of-line attributed that box's own children to the Row.
    pending_layout = False
    arm_line: int | None = None
    i = row_open
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        # Arm exactly on the line that opens the child's body. Lambda arguments
        # written inside the call head -- `onExpandedChange = { ... }` -- open
        # and close braces of their own, and arming earlier let one of those
        # swallow the flag, after which the container's real body read as
        # transparent and its children were attributed to the Row.
        if arm_line is not None and i == arm_line:
            pending_layout = True
            arm_line = None

        if started and not any(stack) and stripped and not stripped.startswith("//"):
            m = re.match(r"^([A-Za-z_][\w.]*)\s*\(", stripped)
            if m and not TRANSPARENT.match(stripped):
                if CONTAINER.match(stripped) or m.group(1)[0].isupper():
                    # Read forward until this call's parens balance: that text
                    # holds its modifier and nothing of its children's.
                    head, j, par = [], i, 0
                    while j < len(lines):
                        for ch in lines[j]:
                            if ch == "(":
                                par += 1
                            elif ch == ")":
                                par -= 1
                        head.append(lines[j])
                        if par <= 0:
                            break
                        j += 1
                    children.append((i, "\n".join(head)))
                    # Only a child that opens a body can contain anything, and
                    # when that body opens on the SAME line -- `Box(...) {` --
                    # the arm has to happen now, because this line's braces are
                    # processed below and the top-of-loop arming already ran.
                    if lines[j].rstrip().endswith("{"):
                        if j == i:
                            pending_layout = True
                        else:
                            arm_line = j
                    else:
                        arm_line = None

        for ch in line:
            if ch == "{":
                depth += 1
                if not started:
                    started = True
                    stack.append(False)  # the Row's own brace: transparent
                else:
                    stack.append(pending_layout)
                    pending_layout = False
            elif ch == "}":
                depth -= 1
                if stack:
                    stack.pop()
                if started and depth == 0:
                    return children
        i += 1
    return children


def offenders(path: pathlib.Path) -> list[tuple[int, str]]:
    lines = path.read_text(encoding="utf-8").split("\n")
    out: list[tuple[int, str]] = []
    for i, line in enumerate(lines):
        if not ROW_RE.search(line):
            continue
        weighted, filling = [], []
        for start, head in layout_children(lines, i):
            if ".weight(" in head:
                weighted.append(start + 1)
            elif ".fillMaxWidth()" in head:
                filling.append(start + 1)
        if weighted and filling:
            out.append(
                (
                    i + 1,
                    f"weighted child(ren) at {weighted} share this Row with a "
                    f"fillMaxWidth() sibling at {filling} — the weighted children measure to ZERO width",
                )
            )
    return out


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", action="store_true", help="print every offender")
    args = ap.parse_args(argv)

    found = []
    for path in sorted(SRC.rglob("*.kt")):
        for line_no, why in offenders(path):
            found.append((path.relative_to(ROOT), line_no, why))

    if not found:
        print("[OK] no Row mixes weight() and fillMaxWidth() on its direct children")
        return 0

    print(f"[FAIL] {len(found)} Row(s) would measure a weighted child at zero width:\n")
    for rel, line_no, why in found:
        print(f"  {rel}:{line_no}")
        print(f"    {why}")
    print("\n  Move the full-width child OUT of the Row — it belongs as a sibling")
    print("  in the enclosing Column, below it.")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
