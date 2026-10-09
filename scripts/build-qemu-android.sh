#!/usr/bin/env bash
#
# Cross-compiles an Android build of qemu-system-aarch64 (ARM `virt` machine,
# headless serial console, slirp user-mode networking with hostfwd) and installs
# it as app/src/main/jniLibs/<abi>/libqemu-system-aarch64.so, along with the
# required AArch64 UEFI and virtio ROM data files.
#
# Why a fake `.so`: Android only allows exec() from the app's nativeLibraryDir
# on targetSdk 29+, so the QEMU executable is packaged under jniLibs and
# extracted there at install time (see VmArch/QemuEngine). Extraction requires
# `jniLibs.useLegacyPackaging = true` in app/build.gradle.kts.
#
# The build is normally driven from Gradle (`./gradlew buildQemuArm64`); this
# script is the implementation and can also be run directly:
#
#   scripts/build-qemu-android.sh [ABI] [NDK_DIR]
#
#   ABI      arm64-v8a (default) or armeabi-v7a
#   NDK_DIR  Android NDK; defaults to $ANDROID_NDK_HOME, then the newest NDK
#            under $ANDROID_HOME / local.properties `sdk.dir`.
#
# Environment overrides: QEMU_VERSION, GLIB_VERSION, PIXMAN_VERSION,
# FFI_VERSION, PCRE2_VERSION, SLIRP_VERSION, API_LEVEL, JOBS, WORK_DIR,
# CACHE_DIR.
#
# Requirements: bash, curl, tar, git, python3, bzip2, gzip and an Android NDK (r26+
# recommended); network access is needed to download sources (QEMU fetches
# libslirp/dtc via git at configure time). Meson/Ninja are installed
# automatically into a private virtualenv.
set -euo pipefail

# ---------------------------------------------------------------------------
# Pinned component versions (override with environment variables).
# ---------------------------------------------------------------------------
QEMU_VERSION="${QEMU_VERSION:-9.2.0}"
GLIB_VERSION="${GLIB_VERSION:-2.82.2}"
PIXMAN_VERSION="${PIXMAN_VERSION:-0.44.2}"
FFI_VERSION="${FFI_VERSION:-3.4.6}"
PCRE2_VERSION="${PCRE2_VERSION:-10.44}"
SLIRP_VERSION="${SLIRP_VERSION:-4.8.0}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_LIBS="$PROJECT_ROOT/app/src/main/jniLibs"
QEMU_ASSETS_DIR="${QEMU_ASSETS_DIR:-$PROJECT_ROOT/app/build/generated/qemuAssets}"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Arguments / environment.
# ---------------------------------------------------------------------------
ABI="${1:-${ABI:-arm64-v8a}}"
NDK_DIR="${2:-${ANDROID_NDK_HOME:-}}"

# API level for the native build. API 28 is the lowest level whose bionic
# exports the strong libc symbols a static GLib needs (getrandom, posix_spawn,
# openpty, iconv, ...). The app's runtime minSdk may be lower — the engine is
# simply unavailable on older releases, which QemuEngine reports gracefully.
API_LEVEL="${API_LEVEL:-28}"

# Persistent cache for sources, dependencies and the build tree so repeated
# runs are incremental.
CACHE_DIR="${CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/qemu-android}"
WORK_DIR="${WORK_DIR:-$CACHE_DIR/build-$ABI}"
PREFIX="$WORK_DIR/prefix"
SRC="$CACHE_DIR/src"
VENV="$CACHE_DIR/venv"

# ---------------------------------------------------------------------------
# Locate the NDK.
# ---------------------------------------------------------------------------
find_ndk() {
    if [[ -n "$NDK_DIR" ]]; then echo "$NDK_DIR"; return; fi

    local roots=()
    [[ -n "${ANDROID_HOME:-}" ]] && roots+=("$ANDROID_HOME")
    [[ -n "${ANDROID_SDK_ROOT:-}" ]] && roots+=("$ANDROID_SDK_ROOT")
    if [[ -f "$PROJECT_ROOT/local.properties" ]]; then
        local sdk_dir
        sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | head -n1)"
        [[ -n "$sdk_dir" ]] && roots+=("$sdk_dir")
    fi

    local root newest
    for root in "${roots[@]}"; do
        [[ -d "$root/ndk" ]] || continue
        newest="$(ls -1 "$root/ndk" 2>/dev/null | sort -V | tail -n1 || true)"
        [[ -n "$newest" ]] && { echo "$root/ndk/$newest"; return; }
    done

    die "NDK not found. Set ANDROID_NDK_HOME or pass the NDK path as the second argument."
}

NDK_DIR="$(find_ndk)"
NDK_DIR="${NDK_DIR/#\~/$HOME}"
[[ -d "$NDK_DIR" ]] || die "NDK directory does not exist: $NDK_DIR"

HOST_TAG=""
for tag in darwin-x86_64 linux-x86_64 darwin-arm64 windows-x86_64; do
    if [[ -x "$NDK_DIR/toolchains/llvm/prebuilt/$tag/bin/clang" ]]; then
        HOST_TAG="$tag"
        break
    fi
done
[[ -n "$HOST_TAG" ]] || die "No prebuilt toolchain under $NDK_DIR/toolchains/llvm/prebuilt"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
BIN="$TOOLCHAIN/bin"
SYSROOT="$TOOLCHAIN/sysroot"

# ---------------------------------------------------------------------------
# Per-ABI toolchain settings.
# ---------------------------------------------------------------------------
case "$ABI" in
    arm64-v8a)
        TRIPLE="aarch64-linux-android"
        CPU_FAMILY="aarch64"
        CPU="aarch64"
        ;;
    armeabi-v7a)
        TRIPLE="armv7a-linux-androideabi"
        CPU_FAMILY="arm"
        CPU="armv7"
        ;;
    *) die "Unsupported ABI '$ABI' (expected arm64-v8a or armeabi-v7a)" ;;
esac

CC="$BIN/${TRIPLE}${API_LEVEL}-clang"
CXX="$BIN/${TRIPLE}${API_LEVEL}-clang++"
AR="$BIN/llvm-ar"
RANLIB="$BIN/llvm-ranlib"
STRIP="$BIN/llvm-strip"
NM="$BIN/llvm-nm"
READELF="$BIN/llvm-readelf"
[[ -x "$CC" ]] || die "Compiler not found: $CC"

TARGET_CFLAGS="-fPIC -O2 -fstack-protector-strong --sysroot=$SYSROOT -DANDROID"

# ---------------------------------------------------------------------------
# Host prerequisites. Meson/Ninja come from the private virtualenv; pkg-config
# must be provided by the host (pkgconf is accepted).
# ---------------------------------------------------------------------------
for tool in curl tar python3 bzip2 gzip; do
    command -v "$tool" >/dev/null 2>&1 || die "Required tool '$tool' not found in PATH."
done
PYTHON="$(command -v python3)"
PKG_CONFIG_BIN="$(command -v pkg-config || command -v pkgconf || true)"
[[ -n "$PKG_CONFIG_BIN" ]] || die "pkg-config (or pkgconf) is required but was not found in PATH."

JOBS="${JOBS:-$( (sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4) )}"

mkdir -p "$SRC" "$WORK_DIR" "$PREFIX" "$JNI_LIBS/$ABI"

if [[ ! -x "$VENV/bin/meson" ]]; then
    log "Creating build virtualenv in $VENV"
    "$PYTHON" -m venv "$VENV"
    "$VENV/bin/pip" install --quiet --upgrade pip
fi
# QEMU's configure bootstraps its own build venv and needs distlib to do so;
# keep the dependency list idempotent so existing venvs stay usable.
if ! "$VENV/bin/python" -c "import distlib" >/dev/null 2>&1; then
    log "Installing build tools into $VENV"
    "$VENV/bin/pip" install --quiet meson ninja pyyaml packaging tomli distlib setuptools
fi
MESON="$VENV/bin/meson"
NINJA="$VENV/bin/ninja"
VENV_PYTHON="$VENV/bin/python"
export PATH="$VENV/bin:$PATH"

# ---------------------------------------------------------------------------
# Helpers.
# ---------------------------------------------------------------------------
# `fetch` assigns to FETCHED_PATH instead of printing the path: Gradle's Exec
# task shares stdout/stderr on a single pty, so command substitution would also
# capture log lines.
FETCHED_PATH=""
fetch() {
    local url="$1" out="$SRC/$2"
    if [[ -s "$out" ]]; then
        log "Reusing $(basename "$out")"
    else
        log "Downloading $(basename "$out")"
        curl -fSL --retry 3 -o "$out.part" "$url"
        mv "$out.part" "$out"
    fi
    FETCHED_PATH="$out"
}

PKG_CONFIG_PATH_TARGET="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
export PKG_CONFIG_PATH="$PKG_CONFIG_PATH_TARGET"
export PKG_CONFIG_LIBDIR="$PKG_CONFIG_PATH_TARGET"

# pixman's Android ARM code path (USE_ARM_SIMD / USE_ARM_NEON, i.e. the
# 32-bit ARM build) includes <cpu-features.h>, which bionic does not ship.
# The NDK bundles the sources under sources/android/cpufeatures; pointing
# pixman at them keeps the ARM SIMD/NEON backends. On an NDK without them,
# fall back to disabling those backends — the headless serial console this
# app targets does not need them.
PIXMAN_EXTRA_ARGS=()
if [[ -f "$NDK_DIR/sources/android/cpufeatures/cpu-features.h" ]]; then
    PIXMAN_EXTRA_ARGS+=("-Dcpu-features-path=$NDK_DIR/sources/android/cpufeatures")
else
    log "NDK has no sources/android/cpufeatures; disabling pixman ARM SIMD/NEON"
    PIXMAN_EXTRA_ARGS+=("-Darm-simd=disabled" "-Dneon=disabled")
fi

# ---------------------------------------------------------------------------
# Meson cross file shared by pixman and GLib.
# ---------------------------------------------------------------------------
write_cross_file() {
    cat > "$WORK_DIR/cross-$ABI.txt" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
ranlib = '$RANLIB'
pkg-config = '$PKG_CONFIG_BIN'
python = '$VENV_PYTHON'

[properties]
needs_exe_wrapper = true

[host_machine]
system = 'android'
cpu_family = '$CPU_FAMILY'
cpu = '$CPU'
endian = 'little'

[built-in options]
c_args = ['-O2', '-fPIC']
cpp_args = ['-O2', '-fPIC']
EOF
}

# ---------------------------------------------------------------------------
# Dependencies: libffi -> pcre2, pixman, GLib (all static).
# ---------------------------------------------------------------------------
build_deps() {
    # --- libffi --------------------------------------------------------------
    if [[ ! -f "$PREFIX/lib/libffi.a" ]]; then
        log "Building libffi $FFI_VERSION ($ABI)"
        fetch "https://github.com/libffi/libffi/releases/download/v$FFI_VERSION/libffi-$FFI_VERSION.tar.gz" "libffi.tar.gz"
        rm -rf "$SRC/libffi-$FFI_VERSION"
        tar -xzf "$FETCHED_PATH" -C "$SRC"
        (
            cd "$SRC/libffi-$FFI_VERSION"
            CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
            CFLAGS="$TARGET_CFLAGS" LDFLAGS="--sysroot=$SYSROOT" \
                ./configure --host="$TRIPLE" --prefix="$PREFIX" \
                    --enable-static --disable-shared --disable-docs
            make -j"$JOBS"
            make install
        )
    else
        log "libffi already built"
    fi

    # --- pcre2 ---------------------------------------------------------------
    if [[ ! -f "$PREFIX/lib/libpcre2-8.a" ]]; then
        log "Building pcre2 $PCRE2_VERSION ($ABI)"
        fetch "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-$PCRE2_VERSION/pcre2-$PCRE2_VERSION.tar.gz" "pcre2.tar.gz"
        rm -rf "$SRC/pcre2-$PCRE2_VERSION"
        tar -xzf "$FETCHED_PATH" -C "$SRC"
        (
            cd "$SRC/pcre2-$PCRE2_VERSION"
            CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
            CFLAGS="$TARGET_CFLAGS" LDFLAGS="--sysroot=$SYSROOT" \
                ./configure --host="$TRIPLE" --prefix="$PREFIX" \
                    --enable-static --disable-shared --enable-jit
            # pcre2 has no --disable-pcre2grep/--disable-pcre2test options; the
            # command-line `bin_PROGRAMS=` override skips the standalone
            # pcre2grep/pcre2test tools (only the libraries are needed) without
            # the unrecognized-option warnings.
            make -j"$JOBS" bin_PROGRAMS=
            make install bin_PROGRAMS=
        )
    else
        log "pcre2 already built"
    fi

    # --- pixman --------------------------------------------------------------
    if [[ ! -f "$PREFIX/lib/libpixman-1.a" ]]; then
        log "Building pixman $PIXMAN_VERSION ($ABI)"
        fetch "https://cairographics.org/releases/pixman-$PIXMAN_VERSION.tar.gz" "pixman.tar.gz"
        rm -rf "$SRC/pixman-$PIXMAN_VERSION"
        tar -xzf "$FETCHED_PATH" -C "$SRC"
        (
            cd "$SRC/pixman-$PIXMAN_VERSION"
            "$MESON" setup build \
                --cross-file "$WORK_DIR/cross-$ABI.txt" \
                --prefix "$PREFIX" --buildtype release --default-library static \
                -Dtests=disabled -Ddemos=disabled -Dgtk=disabled -Dlibpng=disabled \
                "${PIXMAN_EXTRA_ARGS[@]}"
            "$NINJA" -C build -j"$JOBS"
            "$NINJA" -C build install
        )
    else
        log "pixman already built"
    fi

    # --- GLib ----------------------------------------------------------------
    if [[ ! -f "$PREFIX/lib/libglib-2.0.a" ]]; then
        log "Building GLib $GLIB_VERSION ($ABI)"
        local series; series="$(echo "$GLIB_VERSION" | cut -d. -f1-2)"
        fetch "https://download.gnome.org/sources/glib/$series/glib-$GLIB_VERSION.tar.xz" "glib.tar.xz"
        rm -rf "$SRC/glib-$GLIB_VERSION"
        tar -xJf "$FETCHED_PATH" -C "$SRC"
        (
            cd "$SRC/glib-$GLIB_VERSION"
            "$MESON" setup build \
                --cross-file "$WORK_DIR/cross-$ABI.txt" \
                --prefix "$PREFIX" --buildtype release --default-library static \
                -Dtests=false -Dinstalled_tests=false -Ddocumentation=false \
                -Dintrospection=disabled -Dnls=disabled -Dselinux=disabled \
                -Dlibmount=disabled -Dxattr=false -Dman-pages=disabled \
                -Ddtrace=disabled -Dsystemtap=disabled \
                -Dglib_assert=false -Dglib_checks=false
            "$NINJA" -C build -j"$JOBS"
            "$NINJA" -C build install
        )
    else
        log "GLib already built"
    fi
}

# ---------------------------------------------------------------------------
# libslirp (static): QEMU's user-mode networking (hostfwd) backend. Building it
# into the prefix means `dependency('slirp')` resolves to a static archive, so
# qemu-system-aarch64 does not depend on a libslirp.so that does not exist on
# Android.
# ---------------------------------------------------------------------------
build_slirp() {
    [[ -f "$PREFIX/lib/libslirp.a" ]] && { log "libslirp already built"; return; }

    log "Building libslirp $SLIRP_VERSION ($ABI)"
    fetch \
        "https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v$SLIRP_VERSION/libslirp-v$SLIRP_VERSION.tar.gz" \
        "libslirp.tar.gz"
    rm -rf "$SRC/libslirp-v$SLIRP_VERSION"
    tar -xzf "$FETCHED_PATH" -C "$SRC"
    (
        cd "$SRC/libslirp-v$SLIRP_VERSION"
        "$MESON" setup build \
            --cross-file "$WORK_DIR/cross-$ABI.txt" \
            --prefix "$PREFIX" --buildtype release --default-library static \
            -Doss-fuzz=false
        "$NINJA" -C build -j"$JOBS"
        "$NINJA" -C build install
    )
}

# ---------------------------------------------------------------------------
# QEMU.
# ---------------------------------------------------------------------------
build_qemu() {
    # bionic has no POSIX shared-memory objects (shm_open/shm_unlink are not
    # even declared in its headers). QEMU's memory-backend-shm and
    # vhost-user-server reference them unconditionally, so provide ENOSYS
    # stubs through an injected header. Neither feature is used by the
    # single-VM `virt` setup this app targets.
    cat > "$WORK_DIR/android-shim.h" <<'EOF'
#ifndef QEMU_ANDROID_SHIM_H
#define QEMU_ANDROID_SHIM_H
#include <errno.h>
#include <sys/types.h>
static inline int shm_open(const char *name, int oflag, mode_t mode)
{
    (void)name; (void)oflag; (void)mode;
    errno = ENOSYS;
    return -1;
}
static inline int shm_unlink(const char *name)
{
    (void)name;
    errno = ENOSYS;
    return -1;
}
#endif
EOF

    log "Building qemu-system-aarch64 $QEMU_VERSION ($ABI, API $API_LEVEL)"
    fetch "https://download.qemu.org/qemu-$QEMU_VERSION.tar.xz" "qemu.tar.xz"
    if [[ ! -d "$SRC/qemu-$QEMU_VERSION" ]]; then
        tar -xJf "$FETCHED_PATH" -C "$SRC"
    else
        log "Reusing extracted QEMU sources"
    fi

    local qemu_dir="$SRC/qemu-$QEMU_VERSION"
    # Use a fresh out-of-tree build to avoid stale Meson configuration. Gradle's
    # declared outputs skip this script on unchanged normal app builds.
    local build_dir="$WORK_DIR/qemu-build"
    rm -rf "$build_dir"
    mkdir -p "$build_dir"

    # Static GLib resolves these against the bionic system libraries.
    export LIBRARY_PATH="$SYSROOT/usr/lib/$TRIPLE/$API_LEVEL:$SYSROOT/usr/lib/$TRIPLE:${LIBRARY_PATH:-}"

    # Inject the bionic compatibility shim (-include applies it to C and C++).
    local qemu_cflags="$TARGET_CFLAGS -include $WORK_DIR/android-shim.h"

    # Minimal headless configuration that still provides the `virt` machine,
    # the serial console and slirp user networking (hostfwd).
    (
        cd "$build_dir"
        # QEMU's configure derives its pkg-config from `${cross_prefix}pkg-config`
        # (i.e. <ndk>/toolchains/llvm/prebuilt/<tag>/bin/<triple>-pkg-config),
        # which the NDK does not ship — the build then fails to find glib-2.0.
        # PKG_CONFIG takes precedence over that default, so point it at the host
        # pkg-config (pkgconf); the cross-file wiring of PKG_CONFIG_LIBDIR makes
        # it report the target GLib/pixman/slirp we just built into $PREFIX.
        CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" NM="$NM" \
        CFLAGS="$qemu_cflags" CXXFLAGS="$qemu_cflags" PKG_CONFIG="$PKG_CONFIG_BIN" \
        "$qemu_dir/configure" \
            --cross-prefix="$BIN/${TRIPLE}${API_LEVEL}-" \
            --python="$VENV_PYTHON" \
            --target-list=aarch64-softmmu \
            --prefix="$PREFIX" \
            --enable-slirp --enable-iconv \
            --disable-docs --disable-tools --disable-guest-agent --disable-werror \
            --disable-gtk --disable-sdl --disable-vnc --disable-curses --disable-spice \
            --disable-opengl --disable-libudev --disable-libusb --disable-brlapi \
            --disable-smartcard --disable-libnfs --disable-glusterfs --disable-rdma \
            --disable-fuse --disable-fuse-lseek --disable-bpf --disable-af-xdp \
            --disable-keyring --disable-attr --disable-cap-ng --disable-libssh \
            --disable-curl --disable-multiprocess \
            --disable-libvduse --disable-vduse-blk-export --disable-vfio-user-server \
            --disable-vhost-user --disable-vhost-kernel --disable-vhost-net \
            --disable-vhost-crypto --disable-vhost-vdpa --disable-vhost-user-blk-server
        "$NINJA" -C "$build_dir" -j"$JOBS" qemu-system-aarch64
        [[ -f "$build_dir/qemu-system-aarch64" ]] || die "qemu-system-aarch64 was not produced"
    )
}

# ---------------------------------------------------------------------------
# Install into jniLibs and sanity-check the result.
# ---------------------------------------------------------------------------
install_binary() {
    local src_bin="$WORK_DIR/qemu-build/qemu-system-aarch64"
    local dest_dir="$JNI_LIBS/$ABI"
    local dest="$dest_dir/libqemu-system-aarch64.so"

    mkdir -p "$dest_dir"
    for legacy_abi in arm64-v8a armeabi-v7a; do
        rm -f "$JNI_LIBS/$legacy_abi/libqemu-system-arm.so"
    done
    cp "$src_bin" "$dest"
    "$STRIP" --strip-unneeded "$dest" 2>/dev/null || true
    chmod 755 "$dest"

    local machine
    machine="$("$READELF" -h "$dest" | sed -n 's/^ *Machine: *//p' | head -n1)"
    [[ -n "$machine" ]] || die "Installed binary is not a readable ELF: $dest"

    log "Installed $dest ($machine, $(du -h "$dest" | cut -f1))"
    log "Runtime dependencies:"
    "$READELF" -d "$dest" | sed -n 's/.*Shared library: \[\(.*\)\]/    \1/p'
}

install_runtime_assets() {
    [[ "$ABI" == "arm64-v8a" ]] || return

    local source_dir="$SRC/qemu-$QEMU_VERSION/pc-bios"
    local dest_dir="$QEMU_ASSETS_DIR/qemu"
    local firmware source temporary asset
    rm -rf "$dest_dir"
    mkdir -p "$dest_dir"

    for firmware in edk2-aarch64-code.fd edk2-arm-vars.fd; do
        source="$source_dir/$firmware.bz2"
        temporary="$dest_dir/$firmware.qemugz.tmp"
        [[ -s "$source" ]] || die "Missing QEMU firmware source: $source"
        bzip2 -dc "$source" | gzip -c > "$temporary"
        [[ -s "$temporary" ]] || die "Failed to unpack QEMU firmware: $source"
        mv "$temporary" "$dest_dir/$firmware.qemugz"
    done

    for asset in efi-virtio.rom edk2-licenses.txt README; do
        source="$source_dir/$asset"
        [[ -s "$source" ]] || die "Missing QEMU runtime asset: $source"
        cp "$source" "$dest_dir/$asset"
    done

    log "Installed AArch64 firmware and virtio ROM assets in $dest_dir"
}

write_cross_file
build_deps
build_slirp
build_qemu
install_binary
install_runtime_assets

log "Done. Engine binary for $ABI is in $JNI_LIBS/$ABI"
