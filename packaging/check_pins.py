#!/usr/bin/env python3
"""Does the companion-distribution pin equal VERSION, and does the ciris-holon
range carry the FFmpeg the build names?

    python3 packaging/check_pins.py

`ciris-client[web]==X` must resolve the X bundle. The pin in pyproject.toml is
static text and VERSION is a file, so nothing structural keeps them equal —
this does. A release bump touches VERSION and the pin in the same commit, and
this check is why forgetting one fails CI instead of failing a user.

The second check: the desktop uber-jar's Class-Path names the bytedeco jars
by file name (`ffmpeg-<version>-<classifier>.jar`), and those files come from
ciris-holon. A bytedeco bump in client/shared/build.gradle.kts without a
ciris-holon range that carries the new jars would install cleanly and leave
every desktop call audio-only. HOLON_FFMPEG is what each ciris-holon minor
carries (`ciris_holon.media.FFMPEG`), copied from CIRISHolon's release.

Stdlib only.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

_PIN_RE = re.compile(r'^web\s*=\s*\["ciris-client-wasm==([^"]+)"\]', re.M)
_HOLON_RE = re.compile(r"""['"]ciris-holon>=(\d+)\.(\d+),<(\d+)\.(\d+)\s*;""")
_GRADLE_RE = {
    "ffmpeg": re.compile(r'^val bytedecoFfmpegVersion = "([^"]+)"', re.M),
    "javacpp": re.compile(r'^val bytedecoJavacppVersion = "([^"]+)"', re.M),
}

#: ciris-holon minor -> the bytedeco (ffmpeg, javacpp) versions its media/jars carry.
HOLON_FFMPEG = {
    (0, 1): ("7.1-1.5.11", "1.5.11"),
}


def check_holon(text: str) -> int:
    m = _HOLON_RE.search(text)
    if not m:
        print("[FAIL] parsed no `ciris-holon>=X.Y,<X.Z; <markers>` dependency from pyproject.toml")
        return 1
    lo = (int(m.group(1)), int(m.group(2)))
    hi = (int(m.group(3)), int(m.group(4)))
    if hi != (lo[0], lo[1] + 1):
        print(f"[FAIL] ciris-holon range >={lo[0]}.{lo[1]},<{hi[0]}.{hi[1]} spans more than one minor")
        return 1
    gradle = (REPO / "client/shared/build.gradle.kts").read_text(encoding="utf-8")
    found = {}
    for k, rx in _GRADLE_RE.items():
        g = rx.search(gradle)
        if not g:
            print(f"[FAIL] parsed no bytedeco {k} version from client/shared/build.gradle.kts")
            return 1
        found[k] = g.group(1)
    carried = HOLON_FFMPEG.get(lo)
    if carried is None:
        print(f"[FAIL] no record of what ciris-holon {lo[0]}.{lo[1]} carries; add it to HOLON_FFMPEG")
        return 1
    if (found["ffmpeg"], found["javacpp"]) != carried:
        print(f"[FAIL] the build names ffmpeg {found['ffmpeg']} / javacpp {found['javacpp']}, "
              f"but ciris-holon {lo[0]}.{lo[1]} carries ffmpeg {carried[0]} / javacpp {carried[1]}")
        return 1
    print(f"[OK] ciris-holon {lo[0]}.{lo[1]} carries ffmpeg {carried[0]} / javacpp {carried[1]}, as the build names")
    return 0


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
    return check_holon(text)


if __name__ == "__main__":
    sys.exit(main())
