#!/usr/bin/env python3
"""Has anything under ``client/`` drifted from upstream without saying so?

    python3 packaging/check_vendoring.py

``client/`` is vendored from CIRISAgent (``client/VENDORING.md`` §1). Everything
in it is byte-identical to upstream **except** the files enumerated in §3, each
of which is there for a stated reason.

This asserts that, without a network fetch: hash every vendored file that §3
does not claim, and compare against the digest §3 records. An edit to a vendored
file is then two outcomes and no third — either the digest still matches, or the
edit is declared. What it makes impossible is the quiet middle: a change made
here, never pushed upstream, that the next re-vendor silently reverts.

That middle is not hypothetical. It is what "the same ~200k lines exist in two
repositories, kept aligned by hand" (MISSION §1) has meant in practice.

Stdlib only. No clone, no network, no build.
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
VENDORING = REPO / "client" / "VENDORING.md"

_DIGEST_RE = re.compile(r"post-delta digest:\**\s*`([0-9a-f]{64})`")
_ROW_RE = re.compile(r"^\|\s*`([^`]+)`\s*\|")


def declared_deltas() -> set[str]:
    """The file paths §3 claims, as repo-relative paths under client/."""
    text = VENDORING.read_text(encoding="utf-8")
    try:
        section = text.split("## 3. Local deltas", 1)[1].split("\n## ", 1)[0]
    except IndexError:
        sys.exit("[FAIL] client/VENDORING.md has no '## 3. Local deltas' section")

    paths = {f"client/{m.group(1)}" for line in section.splitlines() if (m := _ROW_RE.match(line))}
    if not paths:
        # AGENTS.md, Gate Rules: a parser that finds nothing where the construct
        # plainly exists must fail loudly. An empty delta set would make every
        # declared change look like undeclared drift, or — worse, if the table
        # were the only thing that moved — make drift look declared.
        sys.exit("[FAIL] parsed zero delta rows from VENDORING.md §3; the table is there")
    return paths


def recorded_digest() -> str:
    m = _DIGEST_RE.search(VENDORING.read_text(encoding="utf-8"))
    if not m:
        sys.exit("[FAIL] VENDORING.md §3 records no `post-delta digest:`")
    return m.group(1)


def compute(exclude: set[str]) -> tuple[str, int]:
    files = sorted(
        p for p in (REPO / "client").rglob("*")
        if p.is_file() and str(p.relative_to(REPO)) not in exclude
    )
    outer = hashlib.sha256()
    for path in files:
        inner = hashlib.sha256(path.read_bytes()).hexdigest()
        outer.update(f"{inner}  {path.relative_to(REPO)}\n".encode())
    return outer.hexdigest(), len(files)


def main() -> int:
    deltas = declared_deltas() | {"client/VENDORING.md"}
    digest, count = compute(deltas)
    expected = recorded_digest()

    print("  vendoring check — client/ against its recorded state")
    print(f"    declared deltas : {len(deltas)}")
    print(f"    files hashed    : {count}")
    print(f"    digest          : {digest}")

    if digest == expected:
        print("\n[OK] every vendored file is either untouched or declared in §3")
        return 0

    print(f"    expected        : {expected}")
    print(
        "\n[FAIL] a file under client/ changed without a row in VENDORING.md §3.\n"
        "  Either revert it, or add it to the §3 table with the reason and update\n"
        "  the recorded digest in the same commit:\n"
        "      python3 packaging/check_vendoring.py --print\n"
        "  A change worth keeping is usually a change worth pushing upstream — the\n"
        "  delta table is deliberately small so that staying small is a decision."
    )
    return 1


if __name__ == "__main__":
    if "--print" in sys.argv:
        print(compute(declared_deltas() | {"client/VENDORING.md"})[0])
        sys.exit(0)
    sys.exit(main())
