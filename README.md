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
| `scripts/build-qemu-android.sh` | Cross-compiles `qemu-system-arm` for Android. |
| `app/src/main/jniLibs/<abi>/libqemu-system-arm.so` | Build output (generated, not committed). |

## Building the QEMU engine

The engine is a prebuilt `qemu-system-arm` cross-compiled with the Android NDK.
Android only permits `exec()` from the app's `nativeLibraryDir`, so the QEMU
executable is packaged as a `jniLibs` entry named `libqemu-system-arm.so` and
extracted at install time (`android:extractNativeLibs` stays enabled via
`jniLibs.useLegacyPackaging = true` in `app/build.gradle.kts`).

```bash
./gradlew buildQemuArm64     # arm64-v8a only (recommended)
./gradlew buildQemuArmV7     # armeabi-v7a (optional, doubles build time)
./gradlew buildQemu          # both ABIs
./gradlew verifyQemu         # fails if the required binary is missing
./gradlew cleanQemu          # removes the generated binaries
```

The task invokes `scripts/build-qemu-android.sh`, which downloads and
cross-compiles the full dependency chain — libffi, pcre2, GLib, pixman, libslirp
and finally QEMU — all statically linked so the result depends only on bionic
system libraries. Sources, dependencies and object files are cached in
`~/.cache/qemu-android`, so only the first run is slow (~3–5 min per ABI);
re-runs reuse everything.

### Requirements

- `bash`, `curl`, `tar`, `git`, `python3` and `pkg-config` on the host.
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

The VM needs a boot image. Import one (a small ARM Linux kernel image for the
`virt` machine) through the app's file picker; it is copied into app-private
storage and booted with:

```
-M virt -cpu cortex-a15 -m 256 -nographic -serial mon:stdio \
-kernel <image> -append "console=ttyAMA0" \
-netdev user,id=n0,hostfwd=tcp::<hostPort>-:<guestPort> \
-device virtio-net-pci,netdev=n0
```

Each configured port mapping becomes one `hostfwd` rule, so a mapping like
`31415 → 22` makes the guest SSH reachable from any LAN device at
`<phone-ip>:31415`. Intended operation is with the phone connected to power,
with battery optimization disabled for the app.
