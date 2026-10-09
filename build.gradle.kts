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
// The app executes a prebuilt qemu-system-arm packaged as a jniLibs entry
// (`app/src/main/jniLibs/<abi>/libqemu-system-arm.so`). These tasks drive
// scripts/build-qemu-android.sh to cross-compile it with the Android NDK.
// The binaries are far too large to commit, so this is the supported way to
// obtain a working engine.
// ---------------------------------------------------------------------------
val qemuAndroidScript = layout.projectDirectory.file("scripts/build-qemu-android.sh")
val qemuJniLibsDir = layout.projectDirectory.dir("app/src/main/jniLibs")

fun registerQemuBuildTask(name: String, abi: String, description: String) =
    tasks.register<Exec>(name) {
        group = "qemu"
        this.description = description
        val script = qemuAndroidScript.asFile
        workingDir = rootDir
        commandLine("bash", script.absolutePath, abi)
        // The cross-compile is heavy and already incremental; never treat it as
        // up-to-date or the engine could be silently skipped.
        outputs.upToDateWhen { false }
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
    description = "Cross-compiles qemu-system-arm for arm64-v8a into app/src/main/jniLibs.",
)

val buildQemuArmV7 = registerQemuBuildTask(
    name = "buildQemuArmV7",
    abi = "armeabi-v7a",
    description = "Cross-compiles qemu-system-arm for armeabi-v7a into app/src/main/jniLibs.",
)

val buildQemu = tasks.register("buildQemu") {
    group = "qemu"
    description = "Cross-compiles qemu-system-arm for every supported ABI."
    dependsOn(buildQemuArm64, buildQemuArmV7)
}

tasks.register<Delete>("cleanQemu") {
    group = "qemu"
    description = "Removes the generated QEMU engine binaries from jniLibs."
    delete(qemuJniLibsDir)
}

tasks.register("verifyQemu") {
    group = "qemu"
    description = "Fails unless the required QEMU engine binary (arm64-v8a) is present."

    val requiredAbis = listOf("arm64-v8a")
    val optionalAbis = listOf("armeabi-v7a")
    val jniLibs = qemuJniLibsDir.asFile
    val binaryName = "libqemu-system-arm.so"

    doLast {
        val missing = requiredAbis.filter { !File(jniLibs, "$it/$binaryName").isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "QEMU engine binary missing for: ${missing.joinToString()}. " +
                    "Run `./gradlew buildQemuArm64` first.",
            )
        }
        requiredAbis.forEach { abi ->
            val binary = File(jniLibs, "$abi/$binaryName")
            logger.lifecycle("QEMU engine OK: $abi (${binary.length() / (1024 * 1024)} MiB)")
        }
        optionalAbis
            .filter { File(jniLibs, "$it/$binaryName").isFile }
            .forEach { logger.lifecycle("QEMU engine OK (optional): $it") }
    }
}
