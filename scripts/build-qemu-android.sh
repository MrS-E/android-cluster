#!/usr/bin/env bash
#
# Cross-compiles a static qemu-system-arm with the Android NDK and copies it
# into app/src/main/jniLibs/<abi>/libqemu-system-arm.so.
#
# Android only allows exec() from the app's nativeLibraryDir (targetSdk 29+),
# so the binary is packaged as a fake shared library under jniLibs and gets
# extracted there at install time.
#
# Usage:
#   scripts/build-qemu-android.sh [QEMU_VERSION] [NDK_DIR]
#
# Examples:
#   scripts/build-qemu-android.sh                      # defaults: QEMU 9.2.0, NDK from $ANDROID_NDK_HOME
#   scripts/build-qemu-android.sh 9.2.0 ~/Android/Sdk/ndk/27.2.12479018
#
# Requirements: bash, curl (or wget), tar, python3, meson, ninja, pkg-config,
#               a working Android NDK (r26+ recommended).
set -euo pipefail

QEMU_VERSION="${1:-9.2.0}"
NDK_DIR="${2:-${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_NDK_HOME or pass the NDK dir as the second argument}}}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_LIBS="$PROJECT_ROOT/app/src/main/jniLibs"
WORK_DIR="$(mktemp -d /tmp/qemu-android-build.XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "==> Downloading QEMU $QEMU_VERSION"
curl -sSL "https://download.qemu.org/qemu-${QEMU_VERSION}.tar.xz" | tar -xJ -C "$WORK_DIR"
cd "$WORK_DIR/qemu-${QEMU_VERSION}"

# Build a minimal static qemu-system-arm: virt machine, no GUI, user networking.
# Static linking avoids any runtime dependency on a libc++_shared.so.
COMMON_OPTS=(
    --static
    --target-list=arm-softmmu
    --disable-tools
    --disable-docs
    --disable-guest-agent
    --disable-opengl
    --disable-sdl
    --disable-gtk
    --disable-vnc
    --disable-curses
    --disable-spice
    --disable-xkbcommon
    --disable-brlapi
    --disable-libusb
    --disable-libnfs
    --disable-smartcard
    --disable-tpm
    --disable-capstone
    --disable-fdt
    --disable-slirp-smbd
    --enable-slirp
)

for ABI in arm64-v8a armeabi-v7a; do
    echo "==> Building qemu-system-arm for $ABI"
    case "$ABI" in
        arm64-v8a)   TRIPLE=aarch64-linux-android; API=21 ;;
        armeabi-v7a) TRIPLE=arm-linux-androideabi; API=21 ;;
    esac

    # NDK standalone toolchain via env vars for QEMU's configure.
    export AR="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar"
    export AS="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/$TRIPLE$API-clang"
    export CC="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/$TRIPLE$API-clang"
    export CXX="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/$TRIPLE$API-clang++"
    export LD="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/$TRIPLE$API-clang"
    export STRIP="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
    export RANLIB="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ranlib"

    BUILD_DIR="$WORK_DIR/build-$ABI"
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"
    ../configure "${COMMON_OPTS[@]}" \
        --prefix=/usr \
        --cross-prefix="" \
        --extra-cflags="-O2" \
        --extra-ldflags="-static-libstdc++"
    make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)" qemu-system-arm

    DEST="$JNI_LIBS/$ABI"
    mkdir -p "$DEST"
    cp "$BUILD_DIR/qemu-system-arm" "$DEST/libqemu-system-arm.so"
    echo "==> Installed $DEST/libqemu-system-arm.so"
    cd "$WORK_DIR/qemu-${QEMU_VERSION}"
done

echo "==> Done. Binaries in $JNI_LIBS"
