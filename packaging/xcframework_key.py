#!/usr/bin/env python3
"""The identity of an XCFramework, computed the same way on a laptop and in CI.

    python3 packaging/xcframework_key.py            # the key
    python3 packaging/xcframework_key.py --explain  # the key and what went into it

WHY THIS EXISTS AND `hashFiles()` DOES NOT SUFFICE.

`publish.yml` already knows how to not spend four hours: its `XCFramework cache`
step keys `actions/cache` on the sources the framework is built from, and the
`Build` step is skipped outright on a hit. That mechanism is correct and this
script does not replace it. What it cannot do is be primed from outside the
runner — `hashFiles()` is a GitHub Actions expression function with no local
equivalent, so a framework built on a developer's Mac has no way to name itself
in terms the workflow would recognise. The four hours were therefore only ever
avoidable by a *previous CI run*, and a version bump invalidates the key (see
below), so in practice they were paid on every release.

So: the same question, asked in a way both sides can ask.

THE INPUT SET IS THE ONE `publish.yml` ALREADY CHOSE. Do not widen it casually.
Two of its exclusions are load-bearing:

  * The locale bundles are NOT here, and neither is the vendoring digest that
    would drag them in. `composeResources` is NO-SOURCE for this target — the
    framework does not embed a single string — so a localization pass that
    changed nothing the linker sees would otherwise throw away hours of work.
  * `VERSION` *is* here and stays here. `:shared:generateBuildFlavor` compiles
    CLIENT_VERSION into commonMain, so a new version genuinely is a different
    binary. A key that ignored it would ship the previous version's string into
    the VERSION-MISMATCH banner, which is the exact defect CLIENT_VERSION
    generation exists to prevent. The cost is real and is stated plainly: this
    key does NOT survive a version bump, so the local build is per release, not
    per Kotlin change.

A missing input is hashed as absent rather than skipped. `iosArm64Main` and
`iosSimulatorArm64Main` have no files today (both are `dependsOn(iosMain)` and
nothing else), and the day someone adds one the key must move.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Mirrors the `hashFiles(...)` argument list in publish.yml's `XCFramework
# cache` step, in the same order. Changing one without the other reintroduces
# exactly the drift this repo exists to measure — so if you edit this list, edit
# that step in the same commit.
TREES = (
    "client/shared/src/commonMain",
    "client/shared/src/iosMain",
    "client/shared/src/iosArm64Main",
    "client/shared/src/iosSimulatorArm64Main",
    "client/generated-api/src",
)
FILES = (
    "client/shared/build.gradle.kts",
    "client/generated-api/build.gradle.kts",
    "client/build.gradle.kts",
    "client/gradle.properties",
    "VERSION",
)

KEY_CHARS = 16  # of a sha256; collision risk here is not the threat model


def _inputs() -> list[Path]:
    """Every input path, relative to the repo root, in a stable order."""
    found: list[Path] = []
    for tree in TREES:
        root = REPO_ROOT / tree
        if root.is_dir():
            found.extend(p for p in root.rglob("*") if p.is_file())
    for name in FILES:
        path = REPO_ROOT / name
        if path.is_file():
            found.append(path)
    # Sort on the RELATIVE path: an absolute sort would order by checkout
    # location, and CI's checkout is not at /Users/anyone.
    return sorted(found, key=lambda p: p.relative_to(REPO_ROOT).as_posix())


def key(explain: bool = False) -> str:
    digest = hashlib.sha256()
    paths = _inputs()

    # The declared set, hashed first. Without this an input that DISAPPEARS
    # would produce the same key as one that was never declared.
    for name in (*TREES, *FILES):
        digest.update(name.encode())
        digest.update(b"\0")

    for path in paths:
        rel = path.relative_to(REPO_ROOT).as_posix()
        body = path.read_bytes()
        # Path and length are hashed alongside the bytes so that moving a file,
        # or splitting one into two, cannot leave the key unchanged.
        digest.update(rel.encode())
        digest.update(b"\0")
        digest.update(str(len(body)).encode())
        digest.update(b"\0")
        digest.update(body)
        if explain:
            print(f"  {rel:<70} {len(body):>9,}", file=sys.stderr)

    if explain:
        print(f"\n  {len(paths)} files", file=sys.stderr)

    return digest.hexdigest()[:KEY_CHARS]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--explain",
        action="store_true",
        help="list the inputs on stderr; the key still goes to stdout alone",
    )
    args = parser.parse_args(argv)

    # stdout carries the key and nothing else, so `$(xcframework_key.py)` is
    # safe to interpolate into a filename in both the shell script and the
    # workflow.
    print(key(explain=args.explain))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
