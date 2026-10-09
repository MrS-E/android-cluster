# Android Cluster

Runs a small ARM Linux VM with an embedded QEMU inside an Android foreground
service, keeps it alive in the background with a partial wake lock, and exposes
guest services to the local LAN through user-mode port forwarding.

## Project layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/ch/stix/android_cluster/engine` | Architecture-agnostic VM engine (`VmEngine`, `VmArch`, `QemuEngine`). |
| `app/src/main/java/ch/stix/android_cluster/data` | Repositories: VM lifecycle, image import, settings, battery state. |
| `app/src/main/java/ch/stix/android_cluster/service` | `QemuForegroundService` — hosts the VM, holds the wake lock, shows the notification. |
| `app/src/main/java/ch/stix/android_cluster/ui` | Compose UI (`ClusterScreen`, `ConsolePanel`) and `ClusterViewModel`. |
| `scripts/build-qemu-android.sh` | Cross-compiles `qemu-system-aarch64` for Android. |
| `app/src/main/jniLibs/<abi>/libqemu-system-aarch64.so` | Build output (generated, not committed). |

## Building the QEMU engine

The engine is a prebuilt `qemu-system-aarch64` cross-compiled with the Android NDK.
Android only permits `exec()` from the app's `nativeLibraryDir`, so the QEMU
executable is packaged as a `jniLibs` entry named `libqemu-system-aarch64.so` and
extracted at install time (`android:extractNativeLibs` stays enabled via
`jniLibs.useLegacyPackaging = true` in `app/build.gradle.kts`).
The 64 MiB ARM64 UEFI images are gzip-compressed in APK assets and streamed
into private storage only for QCOW2 boots; `efi-virtio.rom` is staged whenever a
virtio PCI device is used.

**The engine is built automatically as part of the normal app build.** Every
`assemble<Variant>` / `bundle<Variant>` / run depends on `buildQemuArm64`, whose
output lands in `app/src/main/jniLibs/arm64-v8a/` right before the variant's
JNI folders are merged; the generated firmware assets are added before asset
merging, so the APK contains everything needed to boot an ARM64 disk.

```bash
./gradlew assembleDebug      # builds + packages the engine
./gradlew :app:installDebug  # same, via the task graph
```

The task is incremental: while the script, the version overrides, the binary,
and generated firmware assets are unchanged it reports `UP-TO-DATE` and costs
nothing. `./gradlew clean` removes the generated firmware assets and makes the
next app build rerun the QEMU task; use `cleanQemu` when intentionally removing
the engine as well.

Standalone engine tasks:

```bash
./gradlew buildQemuArm64     # arm64-v8a only (recommended)
./gradlew buildQemuArmV7     # armeabi-v7a (optional, doubles build time)
./gradlew buildQemu          # both ABIs
./gradlew verifyQemu         # builds the engine, then checks binary and firmware
./gradlew cleanQemu          # removes the generated binary and firmware assets
```

### Building without an NDK

The cross-compile needs a host toolchain, so builds on machines without an NDK
(or CI images that only check the UI) can opt out:

```bash
./gradlew assembleDebug -PskipQemu=true
```

The APK then ships without an engine and the app reports the "engine not
installed" state instead of starting the VM.

The task invokes `scripts/build-qemu-android.sh`, which downloads and
cross-compiles the full dependency chain — libffi, pcre2, GLib, pixman, libslirp
and finally QEMU — all statically linked so the result depends only on bionic
system libraries. Sources and static dependencies are cached in
`~/.cache/qemu-android`. When Gradle reruns the task, it configures QEMU from a
fresh out-of-tree build directory but reuses the cached sources and dependency
libraries; the first cold build takes about 3–5 minutes per ABI.

### Requirements

- `bash`, `curl`, `tar`, `git`, `python3`, `bzip2`, `gzip` and `pkg-config` on
  the host.
  (`git` is used by QEMU's build to fetch `libslirp` and `dtc`.)
- An Android NDK (r26+, tested with r30) discovered from `ANDROID_NDK_HOME`,
  `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `local.properties`'s `sdk.dir`.
- Meson and Ninja are installed automatically into a private virtualenv.

Override versions if needed, e.g.:

```bash
QEMU_VERSION=9.2.0 GLIB_VERSION=2.82.2 ./gradlew buildQemuArm64
```

### Notes on the Android port

- bionic has no POSIX shared-memory objects (`shm_open`/`shm_unlink` are not even
  declared), so the build injects a small header providing `ENOSYS` stubs; the
  affected QEMU features are not used by the `virt` machine.
- The native build targets API 28 (`API_LEVEL`) because that is the lowest level
  whose bionic exports the symbols a static GLib needs. The app's own `minSdk`
  may be lower — the engine reports a graceful "not installed" error there.
- The resulting ELF is 16 KB page-aligned, as required on Android 15+.

## Running

The VM needs a boot image. Import an AArch64 Linux kernel or a disk image through
the app's file picker; it is copied into app-private storage. Files ending in
`.qcow2` are attached as virtual disks and booted through bundled UEFI firmware.
Other files are treated as Linux kernels. The kernel command uses:

```
-M virt -cpu cortex-a57 -m 256 -nographic -serial mon:stdio \
-kernel <image> -append "console=ttyAMA0" \
-netdev user,id=n0,hostfwd=tcp::<hostPort>-:<guestPort> \
-device virtio-net-pci,netdev=n0
```

For a `.qcow2` disk, the app attaches it as `virtio-blk-pci` and uses the
generated `edk2-aarch64-code.fd` / `edk2-arm-vars.fd` firmware files. QEMU start,
stderr, and exit details are logged under `QemuEngine`; service and wake-lock
events are logged under `QemuForegroundService`.
QCOW2 boots allocate at least 1 GiB of guest memory because the Debian image's
initramfs panics at the 256 MiB kernel-image default.

Each configured port mapping becomes one `hostfwd` rule, so a mapping like
`31415 → 22` makes the guest SSH reachable from any LAN device at
`<phone-ip>:31415`. Intended operation is with the phone connected to power,
with battery optimization disabled for the app.
