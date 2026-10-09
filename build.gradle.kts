import org.gradle.api.tasks.PathSensitivity
import org.gradle.internal.os.OperatingSystem

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// ---------------------------------------------------------------------------
// QEMU engine build.
//
// The app executes a prebuilt qemu-system-aarch64 packaged as a jniLibs entry
// (`app/src/main/jniLibs/<abi>/libqemu-system-aarch64.so`). These tasks drive
// scripts/build-qemu-android.sh to cross-compile it with the Android NDK.
// The binaries are far too large to commit, so this is the supported way to
// obtain a working engine.
// ---------------------------------------------------------------------------
val qemuAndroidScript = layout.projectDirectory.file("scripts/build-qemu-android.sh")
val qemuJniLibsDir = layout.projectDirectory.dir("app/src/main/jniLibs")
val qemuAssetsDir = layout.projectDirectory.dir("app/build/generated/qemuAssets")
val qemuBinaryName = "libqemu-system-aarch64.so"

fun qemuBinary(abi: String) = qemuJniLibsDir.file("$abi/$qemuBinaryName").asFile

// Environment overrides understood by the build script that change the produced
// binary. Declaring them as inputs means bumping e.g. QEMU_VERSION re-runs the
// cross-compile instead of silently reusing the previous engine.
val qemuBuildOverrides = listOf(
    "QEMU_VERSION",
    "GLIB_VERSION",
    "PIXMAN_VERSION",
    "FFI_VERSION",
    "PCRE2_VERSION",
    "SLIRP_VERSION",
    "API_LEVEL",
)

fun registerQemuBuildTask(name: String, abi: String, description: String) =
    tasks.register<Exec>(name) {
        group = "qemu"
        this.description = description
        val script = qemuAndroidScript.asFile
        workingDir = rootDir
        commandLine("bash", script.absolutePath, abi)

        // Incremental: the (slow) cross-compile is skipped while the script, the
        // version overrides and the produced binary are unchanged. `cleanQemu`
        // or deleting the binary forces a rebuild.
        inputs.file(qemuAndroidScript).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("abi", abi)
        qemuBuildOverrides.forEach { key ->
            inputs.property(key, providers.environmentVariable(key).orElse(""))
        }
        outputs.file(qemuBinary(abi))
        if (abi == "arm64-v8a") {
            environment("QEMU_ASSETS_DIR", qemuAssetsDir.asFile.absolutePath)
            outputs.dir(qemuAssetsDir)
        }

        standardOutput = System.out
        errorOutput = System.err
        doFirst {
            require(script.isFile) { "Missing build script: ${script.absolutePath}" }
            if (OperatingSystem.current().isWindows) {
                logger.warn("The QEMU cross-compile requires a Unix shell; run it from WSL/MSYS on Windows.")
            }
        }
    }

val buildQemuArm64 = registerQemuBuildTask(
    name = "buildQemuArm64",
    abi = "arm64-v8a",
    description = "Cross-compiles qemu-system-aarch64 for arm64-v8a into app/src/main/jniLibs.",
)

val buildQemuArmV7 = registerQemuBuildTask(
    name = "buildQemuArmV7",
    abi = "armeabi-v7a",
    description = "Cross-compiles qemu-system-aarch64 for armeabi-v7a into app/src/main/jniLibs.",
)

val buildQemu = tasks.register("buildQemu") {
    group = "qemu"
    description = "Cross-compiles qemu-system-aarch64 for every supported ABI."
    dependsOn(buildQemuArm64, buildQemuArmV7)
}

tasks.register<Delete>("cleanQemu") {
    group = "qemu"
    description = "Removes the generated QEMU engine binary and firmware assets."
    delete(qemuJniLibsDir, qemuAssetsDir)
}

tasks.register("verifyQemu") {
    group = "qemu"
    description = "Fails unless the AArch64 QEMU engine and required firmware assets are present."

    dependsOn(buildQemuArm64)
    val requiredAbis = listOf("arm64-v8a")
    val optionalAbis = listOf("armeabi-v7a")
    val jniLibs = qemuJniLibsDir.asFile
    val requiredRuntimeAssets = listOf("edk2-aarch64-code.fd.qemugz", "edk2-arm-vars.fd.qemugz", "efi-virtio.rom")
    val binaryName = qemuBinaryName

    outputs.upToDateWhen { false }
    doLast {
        val missing = requiredAbis.filter { !File(jniLibs, "$it/$binaryName").isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "QEMU engine binary missing for: ${missing.joinToString()}. " +
                    "Run `./gradlew buildQemuArm64` first.",
            )
        }
        val missingRuntimeAssets = requiredRuntimeAssets.filterNot { qemuAssetsDir.file("qemu/$it").asFile.isFile }
        if (missingRuntimeAssets.isNotEmpty()) {
            throw GradleException(
                "QEMU runtime assets missing: ${missingRuntimeAssets.joinToString()}. " +
                    "Run `./gradlew buildQemuArm64` first.",
            )
        }
        requiredAbis.forEach { abi ->
            val binary = File(jniLibs, "$abi/$binaryName")
            logger.lifecycle("QEMU engine OK: $abi (${binary.length() / (1024 * 1024)} MiB)")
        }
        requiredRuntimeAssets.forEach { asset ->
            val file = qemuAssetsDir.file("qemu/$asset").asFile
            logger.lifecycle("QEMU runtime asset OK: $asset (${file.length() / (1024 * 1024)} MiB)")
        }
        optionalAbis
            .filter { File(jniLibs, "$it/$binaryName").isFile }
            .forEach { logger.lifecycle("QEMU engine OK (optional): $it") }
    }
}
