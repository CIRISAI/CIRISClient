#!/usr/bin/env bash
# Build the release XCFramework on a developer's Mac and publish it where CI
# will find it, so `publish.yml`'s iOS leg has nothing left to do.
#
#     packaging/build_xcframework_local.sh              # build, then upload
#     packaging/build_xcframework_local.sh --no-upload  # build only
#     packaging/build_xcframework_local.sh --key        # print the key and exit
#
# WHAT THIS BUYS. The iOS leg is two sequential Kotlin/Native release links and
# essentially nothing else — measured at ~161 minutes on the `macos-14` runner,
# which is a 3-core M1 with 7 GB. That is 3-4 hours of every release, and it is
# NOT parallelism-bound: release linking is whole-program LLVM optimisation, so
# it tracks single-core speed and memory headroom. A current Apple-silicon
# desktop has both, and it is not holding a runner while it works.
#
# WHY "CANCEL THE CI JOB" IS NOT THE MECHANISM. GitHub has no per-job cancel;
# `gh run cancel` takes the whole run, including the wheels and `release-assets`.
# So the iOS leg has to decline the work itself. It already knows how — the
# `XCFramework cache` step's hit path skips `Build` outright — and this script
# exists to give that path something a laptop can prime: a content-addressed
# asset on a rolling prerelease, named by `packaging/xcframework_key.py`.
#
# WHY A RELEASE ASSET AND NOT `actions/cache`. The cache is 10 GB repo-wide and
# evicts anything unread for 7 days. The framework is ~114 MB and its whole
# value is on the releases that did NOT touch Kotlin — which is exactly the
# stretch of quiet during which a 7-day eviction fires. A release asset does not
# expire and, unlike a cache entry, can be written from outside a runner.
#
# THE KEY INCLUDES `VERSION`, ON PURPOSE (see xcframework_key.py). So this is a
# per-release step, not a per-change one: bump VERSION, run this, THEN push the
# tag. Run it after the tag and you are racing the job you are trying to spare.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# The rolling prerelease that holds prebuilt frameworks. Its tag deliberately
# does not match `v*`, which is the only tag pattern publish.yml triggers on —
# creating it must not start a release.
PREBUILT_TAG="xcframework-prebuilt"
TASK=":shared:assembleSharedReleaseXCFramework"

upload=1
for arg in "$@"; do
  case "$arg" in
    --no-upload) upload=0 ;;
    --key) python3 packaging/xcframework_key.py; exit 0 ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

key="$(python3 packaging/xcframework_key.py)"
version="$(cat VERSION)"
asset="shared-${key}.xcframework.zip"

echo "version : $version"
echo "key     : $key"
echo "asset   : $asset"

# THE KEY MUST NAME A COMMIT. An upload built from a dirty tree is a framework
# whose sources exist nowhere but this machine, and the key would still look
# authoritative to CI. Building dirty is fine; publishing dirty is not.
dirty="$(git status --porcelain -- client VERSION)"
if [ -n "$dirty" ] && [ "$upload" -eq 1 ]; then
  echo >&2
  echo "::error:: refusing to upload from a dirty tree — the key would name a state no commit has:" >&2
  echo "$dirty" >&2
  echo "commit, or re-run with --no-upload" >&2
  exit 1
fi

echo
echo "==> $TASK"
# `--no-daemon` matches CI. The build is long and single-shot; a resident daemon
# holding 8 GB afterwards is not worth the configuration time it saves.
( cd client && ./gradlew --no-daemon "$TASK" --console=plain )

xcf="$(find client/shared/build/XCFrameworks/release -maxdepth 1 -name '*.xcframework' | head -1)"
test -n "$xcf" || { echo "::error:: no XCFramework produced" >&2; exit 1; }

# BOTH SLICES, OR IT IS NOT THE ARTIFACT CI SHIPS. A link that fails for one
# target still leaves a well-formed .xcframework containing the other, and the
# only place that shows up is a consumer's simulator build weeks later.
for slice in ios-arm64 ios-arm64-simulator; do
  test -d "$xcf/$slice" || {
    echo "::error:: $xcf is missing the $slice slice — the framework is incomplete" >&2
    ls -1 "$xcf" >&2
    exit 1
  }
done

mkdir -p dist-xcframework
out="$REPO_ROOT/dist-xcframework/$asset"
rm -f "$out"
# Zipped because an .xcframework is a directory and a release asset is a file —
# the same reason and the same shape as publish.yml's Collect step.
( cd "$(dirname "$xcf")" && zip -qr - "$(basename "$xcf")" ) > "$out"

echo
echo "built: $out ($(du -h "$out" | cut -f1))"

if [ "$upload" -eq 0 ]; then
  echo "--no-upload: stopping here"
  exit 0
fi

gh release view "$PREBUILT_TAG" >/dev/null 2>&1 || \
  gh release create "$PREBUILT_TAG" \
    --prerelease \
    --title "prebuilt XCFrameworks" \
    --notes "Content-addressed release XCFrameworks, keyed by packaging/xcframework_key.py. Not a release: publish.yml's iOS leg reads these to skip a ~161-minute rebuild. Assets here are named by key, never by version."

gh release upload "$PREBUILT_TAG" "$out" --clobber

echo
echo "uploaded $asset to $PREBUILT_TAG"
echo "publish.yml's ios-xcframework leg will now restore it instead of building."
