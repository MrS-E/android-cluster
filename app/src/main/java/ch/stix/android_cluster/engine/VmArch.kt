package ch.stix.android_cluster.engine

/**
 * Guest architecture family the VM engine can emulate. Each entry maps to one
 * prebuilt QEMU binary packaged in `jniLibs` (named `lib<binary>.so` so
 * Android extracts it into `nativeLibraryDir`, the only app-controlled
 * location where `exec()` is allowed on targetSdk 29+).
 *
 * Adding a new architecture later = add the enum entry, ship the matching
 * `jniLibs/<abi>/libqemu-system-*.so` binary and extend
 * [QemuEngine.buildCommand] — no redesign needed.
 */
enum class VmArch(val binaryName: String) {
    ARM("libqemu-system-aarch64.so"),
    // X86_64("libqemu-system-x86_64.so") — placeholder for a later release.
}
