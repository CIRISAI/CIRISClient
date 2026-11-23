#!/usr/bin/env python3
"""Does the installed uber-jar find its call-video natives?

    python3 packaging/check_holon_classpath.py <desktop uber-jar>

The desktop uber-jar leaves the FFmpeg and JavaCPP natives out and names them in
its manifest `Class-Path`, relative to the jar, in the separate `ciris-holon`
distribution (client/desktopApp/build.gradle.kts). The JVM ignores a Class-Path
entry that does not exist, so a broken layout does not fail at launch: desktop
calls just go audio-only. This check makes that a red build instead. It asserts:

  1. the manifest has a Class-Path, and every entry is `../../ciris_holon/media/jars/<name>.jar`;
  2. each entry exists relative to the jar, as installed;
  3. each file's sha256 equals the line for it in ciris-holon's SHA256SUMS;
  4. the jar itself carries no bytedeco natives (the bytes the split removed).

Run it against the jar as INSTALLED (`ciris_client.artifact_path(...)`), or a
build-tree jar after `:desktopApp:packageUberJarForCurrentOS`, which stages the
same jars at the same relative place. Stdlib only.
"""

from __future__ import annotations

import hashlib
import re
import sys
import zipfile
from pathlib import Path

PREFIX = "../../ciris_holon/media/jars/"
NATIVE_RE = re.compile(r"^org/bytedeco/(ffmpeg|javacpp)/(linux|macosx|windows)-[a-z0-9_]+/")


def manifest_attr(raw: str, name: str) -> str | None:
    # MANIFEST.MF wraps at 72 bytes; a continuation line starts with one space.
    joined = re.sub(r"\r?\n ", "", raw)
    for line in joined.splitlines():
        if line.lower().startswith(name.lower() + ":"):
            return line.split(":", 1)[1].strip()
    return None


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print(__doc__)
        return 2
    jar = Path(argv[0]).resolve()
    with zipfile.ZipFile(jar) as z:
        raw = z.read("META-INF/MANIFEST.MF").decode("utf-8")
        natives = [n for n in z.namelist() if NATIVE_RE.match(n)]
    cp = manifest_attr(raw, "Class-Path")
    if not cp:
        print(f"[FAIL] {jar.name} has no Class-Path: nothing names the ciris-holon natives")
        return 1
    entries = cp.split()
    bad = [e for e in entries if not e.startswith(PREFIX) or not e.endswith(".jar")]
    if bad:
        print(f"[FAIL] Class-Path entries outside {PREFIX}: {bad}")
        return 1

    sums_path = (jar.parent / PREFIX / "SHA256SUMS").resolve()
    sums: dict[str, str] = {}
    if sums_path.is_file():
        for line in sums_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                digest, name = line.split(None, 1)
                sums[name.strip().lstrip("*")] = digest.lower()

    rc = 0
    for e in entries:
        target = (jar.parent / e).resolve()
        if not target.is_file():
            print(f"[FAIL] {e} -> {target}: missing (is ciris-holon installed beside ciris-client?)")
            rc = 1
            continue
        got = sha256(target)
        want = sums.get(target.name)
        if want is None:
            # The build-tree staging has no SHA256SUMS; an install always does.
            note = "no SHA256SUMS line (build tree)" if not sums_path.is_file() else None
            if note is None:
                print(f"[FAIL] {target.name} is not listed in {sums_path}")
                rc = 1
                continue
            print(f"[OK] {e} exists, sha256 {got} ({note})")
        elif got != want:
            print(f"[FAIL] {target.name} sha256 {got} != SHA256SUMS {want}")
            rc = 1
        else:
            print(f"[OK] {e} exists and matches SHA256SUMS")

    if natives:
        print(f"[FAIL] {jar.name} still carries {len(natives)} bytedeco native entries, e.g. {natives[0]}")
        rc = 1
    else:
        print(f"[OK] {jar.name} carries no bytedeco natives")
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
