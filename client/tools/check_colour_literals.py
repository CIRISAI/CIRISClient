#!/usr/bin/env python3
"""No colour literal outside the theme file.

    python3 client/tools/check_colour_literals.py             # fail on NEW literals
    python3 client/tools/check_colour_literals.py --list      # show every literal
    python3 client/tools/check_colour_literals.py --baseline  # re-record the baseline
    python3 client/tools/check_colour_literals.py --self-test # prove it goes red

THE RULE (design handoff, "Design tokens"). Sixteen tokens, resolved per
ground, provided once and read by name: `CirisTheme.tokens.mute`. A component
never names a hex. The one file that may is
`ui/theme/CirisTokens.kt`, where the sixteen values live for both grounds.

WHY A SCRIPT. The design's author swept one `mute` value across 142
hand-written call sites while drawing the specs — that sweep is exactly what a
theme prevents, and without a lint the drift comes back one screen at a time
and dark mode fails in six places nobody checks. A literal is invisible in
review: `Color(0xFF6B7280)` looks like every other line.

WHAT COUNTS AS A LITERAL
  Color(0xFF…)                      hex constructor
  Color(red = …) / Color(0.2f, …)   component constructor
  Color.White / .Black / .Red …     the named constants
  Color.hsl( / Color.hsv(
  "#RRGGBB" / "#AARRGGBB"           a hex string (parsed at runtime somewhere)

WHAT DOES NOT
  Color.Transparent, Color.Unspecified   not colours — an absence
  token.copy(alpha = …)                  derived from a token
  the generated glyph table              path data; a `// lint: tint-anchor`
                                         marker exempts a line that must name
                                         the tint anchor a vector is painted through

WHY A BASELINE AND NOT A HARD ZERO. The tree holds ~1,500 of these in ~48
files today (icons, InteractScreen, TrustPage, the old theme objects). A check
that fails the build on all of them on day one gets switched off in a week,
and then it protects nothing. So this fails on NEW literals only — a file whose
count rose, or a file with literals that the baseline never listed — and the
baseline is a debt paid down file by file with the number visibly falling. The
theme, the primitives, the glyphs and the People screen start at zero and
cannot rise.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import shutil
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "shared" / "src" / "commonMain" / "kotlin"
BASELINE = pathlib.Path(__file__).parent / "colour_literal_baseline.json"
THEME_FILE = SRC / "ai" / "ciris" / "mobile" / "shared" / "ui" / "theme" / "CirisTokens.kt"
EXEMPT_MARKER = "// lint: tint-anchor"

PATTERNS = {
    "hex": re.compile(r"\bColor\(\s*0[xX][0-9A-Fa-f]+"),
    "components": re.compile(r"\bColor\(\s*(?:(?!0[xX])\d|red\s*=|green\s*=|blue\s*=|alpha\s*=)"),
    "named": re.compile(r"\bColor\.(?:White|Black|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|LightGray|DarkGray)\b"),
    "hsl": re.compile(r"\bColor\.(?:hsl|hsv)\("),
    "string": re.compile(r'"#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?"'),
}


def count_file(path: pathlib.Path) -> int:
    n = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        if EXEMPT_MARKER in line:
            continue
        for p in PATTERNS.values():
            n += len(p.findall(line))
    return n


def census(src: pathlib.Path, theme_file: pathlib.Path) -> dict[str, int]:
    """{relative path: count} for every file with at least one literal."""
    files = sorted(src.rglob("*.kt"))
    if not files:
        raise SystemExit(f"[FAIL] no Kotlin under {src} — a denominator of zero is not a pass")
    if not theme_file.exists():
        raise SystemExit(f"[FAIL] the theme file is missing: {theme_file}")
    out: dict[str, int] = {}
    for f in files:
        if f.resolve() == theme_file.resolve():
            continue
        n = count_file(f)
        if n:
            out[str(f.relative_to(src))] = n
    return out


def compare(now: dict[str, int], base: dict[str, int]) -> dict[str, tuple[int, int]]:
    """{path: (baseline, now)} for every file that rose or is new."""
    bad = {}
    for path, n in now.items():
        b = base.get(path, 0)
        if n > b:
            bad[path] = (b, n)
    return bad


def run(src: pathlib.Path, theme_file: pathlib.Path, baseline: pathlib.Path) -> int:
    now = census(src, theme_file)
    base = json.loads(baseline.read_text()) if baseline.exists() else {}
    total = sum(now.values())
    base_total = sum(base.values())
    bad = compare(now, base)
    print(f"  colour literals outside the theme file: {total} in {len(now)} file(s)")
    print(f"  baseline:                               {base_total} in {len(base)} file(s)")
    if bad:
        print("\n::error::new colour literal(s) — a component never names a hex; read a token "
              "by name (CirisTheme.tokens.…) and let the ground resolve it")
        for path, (b, n) in sorted(bad.items()):
            print(f"\n  {path}: {b} -> {n}")
        print("\n  Only ui/theme/CirisTokens.kt may hold a literal. Color.Transparent and")
        print("  token.copy(alpha = …) are fine. If a vector must name its tint anchor, mark")
        print(f"  that one line `{EXEMPT_MARKER}`.")
        return 1
    if total < base_total:
        print(f"\n  {base_total - total} fewer than the baseline. Re-record it:")
        print("    python3 client/tools/check_colour_literals.py --baseline")
    print("\n[OK] no new colour literals")
    return 0


def self_test() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp)
        src = t / "src"
        theme = src / "ui" / "theme" / "CirisTokens.kt"
        theme.parent.mkdir(parents=True)
        theme.write_text("val x = Color(0xFF123456)\n")  # allowed: the theme file
        clean = src / "Clean.kt"
        clean.write_text("val a = CirisTheme.tokens.ink.copy(alpha = 0.5f)\nval b = Color.Transparent\nval c = Color.Unspecified\n")
        glyph = src / "Glyph.kt"
        glyph.write_text(f"val anchor = SolidColor(Color.Black) {EXEMPT_MARKER}\n")
        baseline = t / "baseline.json"
        if run(src, theme, baseline) != 0:
            print("[FAIL] self-test: a clean tree did not pass"); return 1
        planted = src / "Planted.kt"
        planted.write_text('val a = Color(0xFF123456)\nval b = Color.White\nval c = Color(red = 1f, green = 0f, blue = 0f)\nval d = Color.hsl(1f, 1f, 1f)\nval e = "#ABCDEF"\n')
        if run(src, theme, baseline) == 0:
            print("[FAIL] self-test: five planted literals did not go red"); return 1
        assert count_file(planted) == 5, count_file(planted)
        baseline.write_text(json.dumps(census(src, theme)))
        if run(src, theme, baseline) != 0:
            print("[FAIL] self-test: a baselined tree did not pass"); return 1
        planted.write_text(planted.read_text() + "val f = Color(0xFF000000)\n")
        if run(src, theme, baseline) == 0:
            print("[FAIL] self-test: one literal above the baseline did not go red"); return 1
    print("[OK] self-test: the check goes red on a deliberate break")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", action="store_true", help="print every file's count and exit 0")
    ap.add_argument("--baseline", action="store_true", help="re-record the baseline")
    ap.add_argument("--self-test", action="store_true", help="prove the check goes red on a break")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    now = census(SRC, THEME_FILE)
    if args.list:
        for path, n in sorted(now.items(), key=lambda kv: -kv[1]):
            print(f"  {n:5d}  {path}")
        print(f"\n{sum(now.values())} colour literal(s) in {len(now)} file(s), outside the theme file")
        return 0
    if args.baseline:
        BASELINE.write_text(json.dumps(now, indent=2, sort_keys=True) + "\n")
        print(f"baseline re-recorded: {sum(now.values())} literal(s) in {len(now)} file(s)")
        return 0
    return run(SRC, THEME_FILE, BASELINE)


if __name__ == "__main__":
    sys.exit(main())
