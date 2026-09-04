#!/usr/bin/env python3
"""Does your Compose version satisfy what this AAR needs? (CIRISClient#31, item 4)

    python3 packaging/check_aar_compat.py ciris-client-0.5.203.aar --compose 1.6.1
    python3 packaging/check_aar_compat.py ciris-client-0.5.203.aar        # just print

WHY THIS EXISTS RATHER THAN A POM

The published AAR carries no POM and no `.module`, so nothing at build time can
learn it needs Compose >= 1.7. A consumer hand-pinned
`androidx.compose.ui:ui:1.6.1`, which compiled clean and then died on the first
recomposition, on the device:

    NoSuchMethodError: Composer.startReplaceGroup
    at CIRISApp.kt:355

The obvious fix is "publish a POM", and it would not have worked. This AAR is
consumed as a FILE dropped into `apps/android/libs/` and resolved through
Gradle's **flatDir**, which does not read metadata at all -- that is exactly why
`implementation(project(":generated-api"))` could never reach a consumer and why
0.5.195 shipped an AAR whose APKs died in onCreate (CIRISClient#25). Publishing
metadata into a repository nobody resolves from is a fix in appearance only.

So the requirement travels INSIDE the artifact, at
`META-INF/ciris-client-requirements.json`, where it can be read from the file a
consumer already has -- and this turns it into a BUILD failure instead of a
crash on a device.

A POM is still published as a release asset for anyone consuming from a real
repository, where it does resolve. It is not what protects the flatDir path.
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

ENTRY = "META-INF/ciris-client-requirements.json"


def parse_version(v: str) -> tuple[int, ...]:
    """`1.7.1`, `1.7.0-beta02`, `1.7` -> a comparable tuple.

    A pre-release suffix is DROPPED rather than ranked. Ordering betas correctly
    is a rabbit hole, and the interesting comparison here is major.minor: this
    check exists to catch 1.6 against 1.7, not 1.7.0-beta01 against -beta02.
    """
    core = v.strip().split("-")[0].split("+")[0]
    parts = []
    for chunk in core.split("."):
        try:
            parts.append(int(chunk))
        except ValueError:
            break
    if not parts:
        raise SystemExit(f"cannot read a version out of {v!r}")
    return tuple(parts)


def requirements_of(aar: Path) -> dict:
    if not aar.exists():
        raise SystemExit(f"{aar} does not exist")
    with zipfile.ZipFile(aar) as z:
        if ENTRY not in z.namelist():
            raise SystemExit(
                f"{aar.name} carries no {ENTRY}.\n"
                f"  It predates CIRISClient 0.5.203. Every release before that ships a bare\n"
                f"  archive with no statement of what it needs, which is the whole reason this\n"
                f"  check exists — so there is nothing here to verify against, and the Compose\n"
                f"  floor for those builds is 1.7.0 (they are built with org.jetbrains.compose\n"
                f"  1.7.x accessors)."
            )
        return json.loads(z.read(ENTRY))


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("aar", type=Path)
    ap.add_argument("--compose", metavar="VERSION",
                    help="your androidx.compose.ui version; omit to just print the requirements")
    args = ap.parse_args()

    req = requirements_of(args.aar)
    floor = req["jetpack_compose_runtime_min"]
    print(f"  {args.aar.name}")
    print(f"    client version            {req.get('client_version', '?')}")
    print(f"    built with Compose MP     {req.get('compose_multiplatform', '?')}")
    print(f"    Jetpack Compose runtime   >= {floor}")
    print(f"    because                   {req.get('why', '')}")

    if not args.compose:
        print("\n  (pass --compose <version> to check yours against it)")
        return 0

    yours = parse_version(args.compose)
    need = parse_version(floor)
    print(f"\n    yours                     {args.compose}")
    if yours >= need:
        print(f"\n  OK — {args.compose} satisfies >= {floor}")
        return 0

    print(
        f"\n::error::androidx.compose {args.compose} is below the {floor} this AAR needs.\n"
        f"  This compiles CLEANLY and fails at RUNTIME, on the device, on the first\n"
        f"  recomposition:\n"
        f"      NoSuchMethodError: Composer.startReplaceGroup\n"
        f"  {req.get('remedy', '')}"
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
