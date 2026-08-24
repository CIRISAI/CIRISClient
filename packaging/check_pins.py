#!/usr/bin/env python3
"""Does the companion-distribution pin equal VERSION?

    python3 packaging/check_pins.py

`ciris-client[web]==X` must resolve the X bundle. The pin in pyproject.toml is
static text and VERSION is a file, so nothing structural keeps them equal —
this does. A release bump touches VERSION and the pin in the same commit, and
this check is why forgetting one fails CI instead of failing a user.

Stdlib only.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

_PIN_RE = re.compile(r'^web\s*=\s*\["ciris-client-wasm==([^"]+)"\]', re.M)


def main() -> int:
    version = (REPO / "VERSION").read_text(encoding="utf-8").strip()
    text = (REPO / "pyproject.toml").read_text(encoding="utf-8")

    m = _PIN_RE.search(text)
    if not m:
        # A parser that finds nothing where the construct plainly exists must
        # fail loudly (AGENTS.md, Gate Rules).
        print("[FAIL] parsed no pinned `web` extra from pyproject.toml")
        return 1

    if m.group(1) != version:
        print(f"[FAIL] [web] pins ciris-client-wasm=={m.group(1)} "
              f"but VERSION is {version}")
        print("  a release bump updates VERSION and the extra in the same commit")
        return 1

    print(f"[OK] the web extra pins =={version}, matching VERSION")
    return 0


if __name__ == "__main__":
    sys.exit(main())
