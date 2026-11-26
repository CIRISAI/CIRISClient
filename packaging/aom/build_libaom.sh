#!/usr/bin/env bash
# libaom for the phones' call video (CSD-134): AV1 both ways on Android and iOS.
#
#     packaging/aom/build_libaom.sh <target> <out-dir> [src-tarball]
#     target: android-arm64-v8a | android-armeabi-v7a | android-x86_64 |
#             ios-arm64 | ios-sim-arm64 | ios-sim-x86_64
#
# Builds static libaom from the PINNED release tarball below: the signature is
# checked against AOMedia's release key, then the sha256. Real-time only and
# 8-bit, which is all a call uses, keeps it small. Installs include/ and lib/
# under <out-dir>. Android needs ANDROID_NDK_HOME; iOS needs Xcode.
set -euo pipefail

VERSION=3.15.3
SHA256=12c01dc731ed7b5a999e28120b45bf58a002ba3435a4ba2cfbc1e084a27c9c04
# "AOMedia release signing key <av1-discuss@aomedia.org>"
KEY_FPR=B002F08B74A148DAA01F7123A48E86DB0B830498
URL="https://storage.googleapis.com/aom-releases/libaom-$VERSION.tar.gz"

target="${1:?target}"; out="${2:?out-dir}"; tarball="${3:-}"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

if [ -z "$tarball" ]; then
    tarball="$work/libaom-$VERSION.tar.gz"
    curl -sfSL -o "$tarball" "$URL"
    curl -sfSL -o "$tarball.asc" "$URL.asc"
fi
echo "$SHA256  $tarball" | sha256sum -c - >/dev/null || { echo "libaom tarball sha256 mismatch" >&2; exit 1; }
if [ -f "$tarball.asc" ] && command -v gpg >/dev/null; then
    export GNUPGHOME="$work/gnupg"; mkdir -m 700 "$GNUPGHOME"
    gpg -q --keyserver hkps://keyserver.ubuntu.com --recv-keys "$KEY_FPR" 2>/dev/null || true
    gpg --verify --status-fd 1 "$tarball.asc" "$tarball" 2>/dev/null | grep -q "VALIDSIG $KEY_FPR" \
        || { echo "libaom signature does not verify against $KEY_FPR" >&2; exit 1; }
fi
tar -xzf "$tarball" -C "$work"
src="$work/libaom-$VERSION"

common=(
    -DCMAKE_BUILD_TYPE=MinSizeRel -DBUILD_SHARED_LIBS=0 -DCONFIG_PIC=1
    -DCONFIG_REALTIME_ONLY=1 -DCONFIG_AV1_HIGHBITDEPTH=0 -DCONFIG_MULTITHREAD=1
    -DENABLE_DOCS=0 -DENABLE_EXAMPLES=0 -DENABLE_TESTS=0 -DENABLE_TOOLS=0 -DENABLE_APPS=0
    -DCMAKE_INSTALL_PREFIX="$out" -DCMAKE_INSTALL_LIBDIR=lib
)
case "$target" in
    android-*)
        abi="${target#android-}"
        args=(-DCMAKE_TOOLCHAIN_FILE="$src/cmake/toolchains/android.cmake"
              -DAOM_ANDROID_NDK_PATH="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME}" -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24)
        # The toolchain names yasm for x86; nasm does the same job and is what CI has.
        if [ "$abi" = x86_64 ]; then
            if command -v nasm >/dev/null; then args+=(-DENABLE_NASM=ON)
            else echo "no nasm: x86_64 builds without assembly (emulator only)" >&2; args+=(-DAOM_TARGET_CPU=generic); fi
        fi
        ;;
    ios-arm64)      args=(-DCMAKE_TOOLCHAIN_FILE="$src/cmake/toolchains/arm64-ios.cmake") ;;
    ios-sim-arm64)  args=(-DCMAKE_TOOLCHAIN_FILE="$src/cmake/toolchains/arm64-ios.cmake" -DCMAKE_OSX_SYSROOT=iphonesimulator) ;;
    ios-sim-x86_64) args=(-DCMAKE_TOOLCHAIN_FILE="$src/cmake/toolchains/x86_64-ios-simulator.cmake" -DENABLE_NASM=ON) ;;
    *) echo "unknown target $target" >&2; exit 2 ;;
esac
cmake -S "$src" -B "$work/build" "${common[@]}" "${args[@]}" >/dev/null
cmake --build "$work/build" -j "${JOBS:-4}" >/dev/null
cmake --install "$work/build" >/dev/null
echo "libaom $VERSION for $target: $(du -b "$out/lib/libaom.a" | cut -f1) bytes static"
