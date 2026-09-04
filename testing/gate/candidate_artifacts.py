#!/usr/bin/env python3
"""Resolve the client artifacts the gate drives — from THIS tree, or from a release.

    python3 -m testing.gate.candidate_artifacts --platform android
    python3 -m testing.gate.candidate_artifacts --from-release 0.5.199

THIS IS THE SEAM (CIRISClient#31).

Vendored from CIRISAgent `tools/fetch_client_artifacts.py` at
gate-vendor-2026-09-03 (01233afd8). Upstream it has exactly one source: resolve
the `ciris-client==` pin from requirements.txt and download that GitHub
release's assets. That is right for a consumer and useless for us — by the time
a release exists, the defect is already published, which is the entire cost
#31 is asking us to stop paying:

    you publish -> we adopt -> our gate finds it -> you fix -> you publish again

So the resolution gains a second source, and it is the DEFAULT here: the build
outputs already sitting in this tree. Everything downstream still consumes files
on disk, unchanged, which is what makes this a seam rather than a fork.

`--from-release` is kept, and kept working, for two reasons that are not
nostalgia. It reproduces a downstream failure against the exact bytes they
adopted, and it is how we check that a candidate behaves like the last published
build rather than merely like itself.

WHY THE LOCAL PATHS ARE NOT GUESSES
Each one is read from the job that publishes it, so a Gradle output moving
breaks this loudly here rather than silently producing an empty run:

    desktop jar   client/desktopApp/build/compose/jars/*.jar   publish.yml:201
    AAR           client/shared/build/fataar/shared-release-fat.aar   publish.yml:578
    XCFramework   client/shared/build/XCFrameworks/release/*.xcframework   publish.yml:588
    APK           client/androidApp/build/outputs/apk/debug/androidApp-debug.apk

A MISSING ARTIFACT IS A FAILURE, NEVER A SKIP.
Upstream can afford "the release is not cut yet" as a diagnosis. Here, an absent
artifact means the build that was supposed to produce it did not run or did not
finish, and continuing would test nothing while reporting a platform green. The
gate's own rule -- a platform that cannot run must SKIP LOUDLY -- is enforced by
the caller deciding to skip, not by this quietly returning None.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPO = "CIRISAI/CIRISClient"

#: Where a local build leaves each artifact. Values are globs relative to ROOT;
#: the first match wins and no match is an error naming the Gradle task.
LOCAL = {
    "desktop": ("client/desktopApp/build/compose/jars/*.jar", ":desktopApp:packageUberJarForCurrentOS"),
    "android_aar": ("client/shared/build/fataar/shared-release-fat.aar", ":shared:fatAar"),
    "android_apk": ("client/androidApp/build/outputs/apk/debug/*.apk", ":androidApp:assembleDebug"),
    "ios": ("client/shared/build/XCFrameworks/release/*.xcframework", ":shared:assembleReleaseXCFramework"),
}


class Missing(SystemExit):
    """An artifact the caller asked for is not in the tree."""


class Stale(SystemExit):
    """An artifact exists but is not built from the tree as it stands."""


def expected_jar_version() -> str:
    """`max(major,1).minor.patch` — desktopApp/build.gradle.kts's nativePackageVersion.

    Compose Desktop's packaging format forbids a 0 major, so the jar reads
    1.5.201 where VERSION reads 0.5.201. Derived here rather than pattern-matched
    so the two cannot drift.
    """
    major, minor, patch = (ROOT / "VERSION").read_text().strip().split(".")[:3]
    return f"{max(int(major), 1)}.{minor}.{patch}"


def newest_source_mtime() -> float:
    """The most recent modification among the Kotlin sources an artifact is built from."""
    return max(
        (f.stat().st_mtime for f in (ROOT / "client").rglob("*.kt") if "/build/" not in str(f)),
        default=0.0,
    )


def local(kind: str) -> Path:
    """The freshest local build output for `kind`, verified to BE this tree's.

    "Freshest on disk" is not the same question as "built from this tree", and
    answering the first while meaning the second is how a gate reports a platform
    green for code that is not the candidate. This repo has already paid for that
    once from the other direction: a universal wheel shipped
    `CIRIS-linux-x64-1.5.188.jar`, two versions stale, and the size check passed
    because a stale jar is a perfectly plausible size (publish.yml's "CLEAN
    FIRST" comment).

    Writing this hit it immediately -- the newest jar in my own tree was
    1.5.195 against a VERSION of 0.5.201, and without these two checks the gate
    would have driven a six-release-old client and called it a pass.

    Two questions, because neither covers the other: does the name carry the
    version we expect (only the jar's does), and is the file older than the
    sources it is supposed to be built from (all four).
    """
    pattern, task = LOCAL[kind]
    hits = sorted(ROOT.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True)
    if not hits:
        raise Missing(
            f"no {kind} artifact at {pattern}\n"
            f"  Build it first:  ./gradlew {task}\n"
            f"  This is a FAILURE, not a skip: a run against a missing artifact tests\n"
            f"  nothing while reporting the platform green."
        )

    art = hits[0]
    want = expected_jar_version()
    if kind == "desktop" and want not in art.name:
        others = ", ".join(h.name for h in hits[:4])
        raise Stale(
            f"{art.name} is not this tree's build -- VERSION is "
            f"{(ROOT / 'VERSION').read_text().strip()}, so the jar should carry {want}.\n"
            f"  Found: {others}\n"
            f"  Rebuild:  ./gradlew {task}\n"
            f"  Driving a stale jar reports a platform green for code that is not the\n"
            f"  candidate, which is the one thing this gate exists to prevent."
        )

    newest_src = newest_source_mtime()
    if newest_src and art.stat().st_mtime < newest_src:
        # A WARNING, NOT A FAILURE, and the asymmetry is deliberate. A version
        # mismatch is proof; an mtime comparison is evidence -- a checkout, a
        # rebase or a touched file moves source mtimes without changing a byte
        # that matters, and failing on that would train people to pass a
        # --force flag habitually, which costs more than it saves.
        print(f"::warning::{art.name} is older than the newest Kotlin source; "
              f"rebuild with ./gradlew {task} if this is meant to be the candidate")
    return art


def asset_url(version: str, name: str) -> str:
    out = subprocess.run(
        ["gh", "release", "view", f"v{version}", "--repo", REPO, "--json", "assets"],
        capture_output=True,
        text=True,
        check=False,
    )
    if out.returncode != 0:
        # PyPI and GitHub Releases are SEPARATE publications: the wheels can be up
        # (carrying the desktop jar) while the .aar and .xcframework, which exist
        # only as release assets, are not. Say which channel is missing -- "release
        # not found" alone reads as "the version does not exist".
        raise SystemExit(
            f"{REPO} has no GitHub release v{version}.\n"
            f"  The PyPI wheels for {version} may already be published -- they are a\n"
            f"  DIFFERENT channel. The .aar and .xcframework exist only as release\n"
            f"  assets."
        )
    for asset in json.loads(out.stdout)["assets"]:
        if asset["name"] == name:
            return asset["url"]
    raise SystemExit(f"{REPO} v{version} has no asset named {name}")


def download(url: str, dest: Path) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        print(f"  {dest.name} already present, skipping")
        return dest
    print(f"  downloading {dest.name} ...")
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:  # noqa: S310 - github release URL
        digest = hashlib.sha256()
        while chunk := r.read(1 << 20):
            f.write(chunk)
            digest.update(chunk)
    tmp.replace(dest)
    print(f"  {dest.name}  {dest.stat().st_size / 1048576:.1f} MB  sha256={digest.hexdigest()[:16]}...")
    return dest


def from_release(version: str, kind: str, into: Path) -> Path:
    """The published artifact for `kind`, staged under `into`.

    The iOS archive unpacks to `shared.xcframework`, NOT the versioned name --
    that is what this repo publishes, and assuming otherwise is what made
    upstream re-extract 108MB every run while its stale-prune matched nothing.
    Kept as an assertion rather than a comment, because it is OUR publication
    contract and we are the ones who could break it.
    """
    into.mkdir(parents=True, exist_ok=True)
    if kind == "android_aar":
        name = f"ciris-client-{version}.aar"
        return download(asset_url(version, name), into / name)
    if kind == "ios":
        name = f"ciris-client-{version}.xcframework.zip"
        zip_path = download(asset_url(version, name), into / name)
        # EXTRACT FIRST, REPLACE SECOND -- a corrupt archive or a full disk must
        # not leave the tree with no framework at all.
        staging = into / ".shared.xcframework.incoming"
        shutil.rmtree(staging, ignore_errors=True)
        staging.mkdir(parents=True)
        target = into / "shared.xcframework"
        try:
            with zipfile.ZipFile(zip_path) as z:
                z.extractall(staging)
            staged = staging / "shared.xcframework"
            if not staged.is_dir():
                raise SystemExit(f"{name} did not contain shared.xcframework -- our layout changed")
            if not any(staged.glob("ios-arm64*")):
                raise SystemExit(f"{name} contains no ios-arm64* slice -- our layout changed")
            shutil.rmtree(target, ignore_errors=True)
            staged.rename(target)
        finally:
            shutil.rmtree(staging, ignore_errors=True)
        zip_path.unlink(missing_ok=True)
        return target
    raise SystemExit(f"{kind} is not published as a release asset; build it locally")


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--kind", choices=sorted(LOCAL), required=True)
    ap.add_argument(
        "--from-release",
        metavar="VERSION",
        help="resolve from a published release instead of this tree",
    )
    ap.add_argument("--into", type=Path, default=ROOT / "build" / "gate-artifacts")
    args = ap.parse_args()

    if args.from_release:
        path = from_release(args.from_release, args.kind, args.into)
        print(f"{args.kind}: {path}  (released {args.from_release})")
    else:
        path = local(args.kind)
        print(f"{args.kind}: {path}  (this tree)")
    # stdout's LAST line is the path, so a workflow step can capture it.
    print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
