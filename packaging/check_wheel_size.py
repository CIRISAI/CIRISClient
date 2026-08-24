#!/usr/bin/env python3
"""Fail the build before PyPI does.

    python3 packaging/check_wheel_size.py dist/*.whl

PyPI's per-file limit is 100 MiB — 104,857,600 bytes, not 100,000,000. The gap
is 4.8 MiB and it has been the entire remaining margin before now.

What fills a client wheel:

  * the desktop uber-jar — 66.48 MiB, a 62.45 MiB wheel, 62.5% of the limit on
    its own (measured, CIRISClient#1). ProGuard would cut most of it and is
    blocked on ktor 3.x (CIRISServer#379), so treat this as fixed.
  * the localization bundles — 29 languages. **These are the product.** They are
    never cut to save size. If a wheel does not fit, split a flavor or a target;
    do not drop a language, because a language is an audience.

This is a check, not a policy: it prints the breakdown either way, so the number
is visible before it is a problem rather than after a failed upload.
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

LIMIT = 104_857_600  # 100 MiB, exactly
WARN_AT = 0.80


def report(path: Path) -> bool:
    size = path.stat().st_size
    pct = size / LIMIT
    headroom = LIMIT - size

    by_ext: dict[str, int] = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            ext = Path(info.filename).suffix or "(none)"
            by_ext[ext] = by_ext.get(ext, 0) + info.file_size

    print(f"\n{path.name}")
    print(f"  wheel: {size:,} bytes ({size / 1048576:.2f} MiB) — {pct:.1%} of the limit")
    print(f"  headroom: {headroom:,} bytes ({headroom / 1048576:.2f} MiB)")
    print("  uncompressed content by extension:")
    for ext, total in sorted(by_ext.items(), key=lambda kv: -kv[1])[:8]:
        print(f"    {ext:<10} {total / 1048576:8.2f} MiB")

    if size > LIMIT:
        print(f"  [FAIL] over PyPI's {LIMIT:,}-byte limit by {-headroom:,} bytes")
        return False
    if pct > WARN_AT:
        print(f"  [WARN] over {WARN_AT:.0%} of the limit — plan the split now")
    return True


def main(argv: list[str]) -> int:
    paths = [Path(a) for a in argv]
    if not paths:
        print("usage: check_wheel_size.py <wheel>...", file=sys.stderr)
        return 2
    missing = [p for p in paths if not p.is_file()]
    if missing:
        # A size check that silently examines zero wheels is a green light for
        # nothing at all.
        print(f"[FAIL] no such wheel: {', '.join(map(str, missing))}", file=sys.stderr)
        return 1
    return 0 if all([report(p) for p in paths]) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
