#!/usr/bin/env python3
"""Do the flavor-extra pins equal VERSION?

    python3 packaging/check_pins.py

`ciris-client[node]==X` must resolve the X payload. The pins in pyproject.toml
are static text and VERSION is a file, so nothing structural keeps them equal —
this does. A release bump touches VERSION and both pins in the same commit, and
this check is why forgetting one of them fails CI instead of failing a user.

Stdlib only.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

_PIN_RE = re.compile(r'^(node|agent)\s*=\s*\["ciris-client-\1==([^"]+)"\]', re.M)


def main() -> int:
    version = (REPO / "VERSION").read_text(encoding="utf-8").strip()
    text = (REPO / "pyproject.toml").read_text(encoding="utf-8")

    pins = {m.group(1): m.group(2) for m in _PIN_RE.finditer(text)}
    if set(pins) != {"node", "agent"}:
        # A parser that finds nothing where the construct plainly exists must
        # fail loudly (AGENTS.md, Gate Rules).
        print(f"[FAIL] parsed {sorted(pins) or 'no'} pinned flavor extras from "
              f"pyproject.toml; expected exactly node and agent, pinned")
        return 1

    bad = {f: v for f, v in pins.items() if v != version}
    if bad:
        for flavor, pinned in sorted(bad.items()):
            print(f"[FAIL] [{flavor}] pins ciris-client-{flavor}=={pinned} "
                  f"but VERSION is {version}")
        print("  a release bump updates VERSION and both extras in the same commit")
        return 1

    print(f"[OK] both flavor extras pin =={version}, matching VERSION")
    return 0


if __name__ == "__main__":
    sys.exit(main())
