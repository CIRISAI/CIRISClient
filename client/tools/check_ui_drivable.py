#!/usr/bin/env python3
"""Every interactive control must be DRIVABLE in test mode, not merely tagged.

    python3 client/tools/check_ui_drivable.py            # fail on NEW offenders
    python3 client/tools/check_ui_drivable.py --list     # show every offender
    python3 client/tools/check_ui_drivable.py --baseline # re-record the baseline

THE INVARIANT
-------------
Downstream builds fully automated UI-level QA against this client. That only
works if every control the automation can SEE, it can also DRIVE. A `testTag`
proves visibility; it proves nothing about drivability:

    .testable("btn_x")                      tag only — NO handler
    .testableClickable("btn_x") { ... }     tag + handler + clickable
    .testableWithHandler("btn_x") { ... }   tag + handler (component clicks itself)

A `btn_*` tagged with plain `testable()` makes `/click` fall back to a Robot
click at the element's centre — luck about DPI, window size and platform
scaling. It worked on Linux and Windows and missed on macOS five times running,
and the miss produced no verdict, no client log line, and a screenshot identical
to a working screen (CIRISClient#28). `btn_test_connection` was one of 61.

Text is worse: entry is collected per screen by hand, so a field can carry an
`input_*` tag with nothing subscribed, and `/input` used to answer
`success: true` after a fixed delay regardless — reporting text that was never
typed.

WHY A BASELINE AND NOT A HARD ZERO
----------------------------------
The tree violates this ~185 times today. A check that fails the build on all of
them on day one gets switched off in a week, and then it protects nothing. So
this fails on NEW offenders only, and the baseline is a debt that can be paid
down file by file with the number visibly falling.

Runtime has the other half: GET /undrivable lists what is tagged-but-not-drivable
on the CURRENT screen, which catches what static analysis cannot see — tags built
at runtime, and fields that carry the right modifier but never subscribe.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "shared" / "src" / "commonMain"
BASELINE = pathlib.Path(__file__).parent / "ui_drivable_baseline.json"

#: Prefixes naming controls a person ACTS on. Everything else — txt_, text_,
#: screen_, card_, dialog_ — is display, and being readable is its whole job.
INTERACTIVE = ("btn_", "chip_", "menu_", "input_", "field_", "toggle_", "switch_", "tab_")

#: `.testable("tag")` — the tag-only modifier. The other two register handlers.
TAG_ONLY = re.compile(r'\.testable\(\s*"([a-zA-Z0-9_]+)"')


def offenders() -> dict[str, list[str]]:
    """{relative path: [tags]} for interactive controls carrying tag-only."""
    found: dict[str, list[str]] = {}
    for f in sorted(SRC.rglob("*.kt")):
        hits = [
            tag for tag in TAG_ONLY.findall(f.read_text(encoding="utf-8"))
            if tag.startswith(INTERACTIVE)
        ]
        if hits:
            found[str(f.relative_to(ROOT))] = sorted(set(hits))
    return found


def load_baseline() -> dict[str, list[str]]:
    if not BASELINE.exists():
        return {}
    return json.loads(BASELINE.read_text())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true", help="print every offender and exit 0")
    ap.add_argument("--baseline", action="store_true", help="re-record the baseline")
    args = ap.parse_args()

    now = offenders()
    total = sum(len(v) for v in now.values())

    if args.list:
        for path, tags in now.items():
            print(f"\n{path}")
            for t in tags:
                print(f"    {t}")
        print(f"\n{total} interactive control(s) tagged but not drivable, in {len(now)} file(s)")
        return 0

    if args.baseline:
        BASELINE.write_text(json.dumps(now, indent=2, sort_keys=True) + "\n")
        print(f"baseline re-recorded: {total} offender(s) in {len(now)} file(s)")
        return 0

    base = load_baseline()
    base_total = sum(len(v) for v in base.values())

    new: dict[str, list[str]] = {}
    for path, tags in now.items():
        added = sorted(set(tags) - set(base.get(path, [])))
        if added:
            new[path] = added

    print(f"  interactive controls tagged but not drivable: {total}")
    print(f"  baseline:                                     {base_total}")

    if new:
        print("\n::error::new undrivable interactive control(s) — a tag proves an element "
              "is VISIBLE, not that automation can drive it")
        for path, tags in new.items():
            print(f"\n  {path}")
            for t in tags:
                print(f"      {t}")
        print("\n  Use .testableClickable(tag) { ... } for a control you own the click of,")
        print("  or .testableWithHandler(tag) { ... } when the component already handles")
        print("  its own clicks (Button, DropdownMenuItem). For a text field, subscribe to")
        print("  TestAutomation.textInputRequests and call registerInputSink(tag).")
        return 1

    if total < base_total:
        print(f"\n  {base_total - total} fewer than the baseline. Re-record it:")
        print("    python3 client/tools/check_ui_drivable.py --baseline")
        # Not a failure: paying the debt down must never be what breaks a build.

    print("\n  no new undrivable controls")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
