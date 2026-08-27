#!/usr/bin/env python3
"""Does the published ciris-client offer the RIGHT five-wheel matrix?

    python3 packaging/check_universal_wheel.py            # check VERSION
    python3 packaging/check_universal_wheel.py 0.5.189    # check any version

pip resolves on the WHEEL TAG. Four OS-tagged wheels
(`manylinux_2_17_x86_64`, `macosx_11_0_arm64`, `macosx_10_9_x86_64`,
`win_amd64`) match nothing on Android, iOS, or any platform outside that set, so
a release without a `py3-none-any` wheel is not merely narrower — it is
UNRESOLVABLE there:

    pip download ciris-client==0.5.190 --platform android_21_arm64_v8a
    ERROR: Could not find a version that satisfies the requirement
           ciris-client==0.5.190 (from versions: 0.5.186)

That is CIRISServer#493, and the error names the last version that still carried
an any-wheel, which reads like a yanked release rather than a missing tag.

**Why nothing caught it for three releases.** `python -m build` emits
`py3-none-any`; the OS wheels are that wheel RETAGGED (`wheel tags
--platform-tag … --remove`). The 0.5.188 split therefore did not add four
wheels, it replaced one with four — and every check that installed the result
ran on a desktop host, matched manylinux, and passed. A verification that can
only succeed is not a verification.

**Fails closed on an unreachable index.** "I could not ask" is not "it is
fine" — that equivalence is what let this ship. Use `--offline` to skip
deliberately; the skip is then a decision someone made, not a network flake
deciding for them.

**Presence is not enough, and that distinction is not theoretical.** Building the
0.5.190 universal wheel produced a 66 MiB one on the first attempt: setuptools
had cached the desktop jar in `build/lib` from a previous staging and put it back
into a payload the staging had deleted. It installed. `check_wheel_size.py`
passed — 66 MiB is a plausible size for a client wheel. A presence-only check
passes it too, and Android then resolves a wheel carrying ~70 MB of desktop Java
it cannot run. So the sizes are checked in both directions: the universal wheel
must be small enough to be jar-free, and each platform wheel large enough to be
carrying the jar it exists for (CIRISClient#8).

Mutation-tested against versions that really shipped, not fixtures:
0.5.186 fails on a 62.98 MiB universal wheel, 0.5.189 fails on its absence,
0.5.190 passes, and a version nobody cut returns 2 — pending, not broken.

Stdlib only. One HTTPS GET.
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
#: The SIMPLE index — what pip actually resolves against, and what reflects an
#: upload first. The metadata API (/pypi/<name>/json) is a cache in front of it:
#: on 0.5.190 the wheel was live and resolvable by pip while that endpoint still
#: showed four files, so this check failed a release it had just verified. A
#: gate that reports red on a green release is how gates get switched off.
SIMPLE = "https://pypi.org/simple/ciris-client/"
SIMPLE_ACCEPT = "application/vnd.pypi.simple.v1+json"
UNIVERSAL_TAG = "py3-none-any"
TIMEOUT = 20

#: The universal wheel is pure Python and a locale bundle. 0.5.190's is 3.14 MiB.
#: A jar-carrying one is ~63 MiB — which is what 0.5.186 actually published, and
#: what a presence-only check happily passes.
#:
#: THIS EXISTS BECAUSE IT NEARLY HAPPENED. Building the 0.5.190 universal wheel
#: produced a 66 MiB one on the first attempt: setuptools had cached the desktop
#: jar in build/lib from a previous staging, and put it back into a payload the
#: staging had deleted. It installed, and check_wheel_size passed, because 66 MiB
#: is a perfectly plausible size for a client wheel. It was caught by looking, not
#: by a gate — and a fix that reintroduces 70 MB of unusable desktop Java into a
#: ~206 MiB APK is a regression wearing the shape of a fix (CIRISClient#8).
UNIVERSAL_MAX_BYTES = 20 * 1024 * 1024

#: And the inverse. A platform wheel exists to carry the jar; one that does not is
#: a desktop install with no client in it, which is the failure the placeholder
#: machinery was built to refuse. 0.5.190's are 61-68 MiB.
PLATFORM_MIN_BYTES = 40 * 1024 * 1024


def published_files(version: str) -> list[str]:
    req = urllib.request.Request(SIMPLE, headers={"Accept": SIMPLE_ACCEPT})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            data = json.loads(resp.read())
    except (urllib.error.URLError, TimeoutError, ValueError) as e:
        sys.exit(
            f"[FAIL] could not reach {SIMPLE}: {e}\n"
            f"  This check FAILS CLOSED. An index that did not answer has not "
            f"told you the wheel is there, and treating silence as a pass is how "
            f"the wheel went missing for three releases. Re-run, or pass "
            f"--offline to skip this deliberately."
        )
    prefix = f"ciris_client-{version}-"
    return [(f["filename"], f.get("size") or 0) for f in data.get("files", [])
            if f.get("filename", "").startswith(prefix)]


def main() -> int:
    if "--offline" in sys.argv:
        print("[SKIP] --offline: the index was not consulted. Nothing is asserted.")
        return 0

    argv = [a for a in sys.argv[1:] if not a.startswith("-")]
    version = argv[0] if argv else (REPO / "VERSION").read_text(encoding="utf-8").strip()

    files = published_files(version)
    if not files:
        # Distinguished from "published, but no any-wheel" on purpose: the
        # caller retries one of these and not the other. Nothing at all means
        # either the upload has not happened or the index has not caught up;
        # files-but-no-any-wheel is the defect, and it will never fix itself.
        print(
            f"[PENDING] ciris-client {version} has no files on the simple index yet.\n"
            f"  Either the upload has not landed or the index has not caught up."
        )
        return 2

    universal = [(n, s) for n, s in files if UNIVERSAL_TAG in n]
    platform = [(n, s) for n, s in files if UNIVERSAL_TAG not in n]
    print(f"  ciris-client {version}: {len(files)} file(s) on PyPI")
    for n, s in sorted(files):
        mark = "  <- universal" if UNIVERSAL_TAG in n else ""
        print(f"    {s / 1048576:7.2f} MiB  {n}{mark}")

    if not universal:
        print(
            f"\n[FAIL] no {UNIVERSAL_TAG} wheel. Every published wheel is OS-tagged, so\n"
            f"  pip cannot resolve this version on Android, iOS, or anything outside\n"
            f"  that tag set — it reports the requirement as unsatisfiable and names\n"
            f"  whichever older version still has one (CIRISServer#493).\n"
            f"  Build it with: python3 packaging/stage_artifacts.py --universal"
        )
        return 1

    # PRESENCE IS NOT ENOUGH. The wheel has to be the RIGHT wheel: a universal
    # one that carries the jar resolves on Android and then ships ~70 MB of
    # desktop Java that cannot run there.
    problems = []
    for n, s in universal:
        if s > UNIVERSAL_MAX_BYTES:
            problems.append(
                f"{n} is {s / 1048576:.2f} MiB — over the {UNIVERSAL_MAX_BYTES / 1048576:.0f} MiB "
                f"ceiling, so it is carrying a desktop jar. That is 0.5.186's shape "
                f"(62.98 MiB), not 0.5.190's (3.14 MiB): it resolves on Android and "
                f"ships unusable Java into the APK. Clean build/ before packaging — "
                f"setuptools caches the payload and puts back what staging deleted."
            )
    for n, s in platform:
        if s < PLATFORM_MIN_BYTES:
            problems.append(
                f"{n} is {s / 1048576:.2f} MiB — under the {PLATFORM_MIN_BYTES / 1048576:.0f} MiB "
                f"floor, so it is NOT carrying the desktop jar it exists to carry. "
                f"A desktop install would resolve this and find no client."
            )
    if problems:
        print("\n[FAIL] the matrix is present but wrong:")
        for p in problems:
            print(f"  - {p}")
        return 1

    print(f"\n[OK] {version}: {UNIVERSAL_TAG} at "
          f"{universal[0][1] / 1048576:.2f} MiB (jar-free) and {len(platform)} "
          f"platform wheel(s) carrying one — resolvable AND correct everywhere")
    return 0


if __name__ == "__main__":
    sys.exit(main())
