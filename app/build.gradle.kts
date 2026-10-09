plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ch.stix.android_cluster"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "ch.stix.android_cluster"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // The QEMU engine ships as jniLibs/<abi>/libqemu-system-aarch64.so (Android
        // only allows exec() from nativeLibraryDir). Legacy packaging keeps the
        // file uncompressed in the APK and extracts it at install time, which is
        // what makes it executable at runtime.
        jniLibs {
            useLegacyPackaging = true
            // The engine is already stripped by the cross-compile; AGP stripping
            // it again only produces a "Unable to strip ..." warning.
            keepDebugSymbols += "**/libqemu-system-aarch64.so"
        }
    }
    sourceSets {
        getByName("main") {
            assets.directories.add(
                project.layout.buildDirectory.dir("generated/qemuAssets").get().asFile.absolutePath,
            )
        }
    }
}

// The app executes the engine from jniLibs/<abi>/libqemu-system-aarch64.so, so a
// normal build also builds it (see `buildQemuArm64` in the root build). This
// requires an Android NDK and network access on the first run; pass
// `-PskipQemu=true` to build without an engine (e.g. on a machine without an
// NDK, or for a UI-only build).
val skipQemu = providers.gradleProperty("skipQemu").orNull?.toBoolean() == true
if (!skipQemu) {
    val qemuEngine = rootProject.tasks.named("buildQemuArm64")
    tasks.matching {
        it.name.startsWith("merge") &&
            (it.name.endsWith("JniLibFolders") || it.name.endsWith("Assets"))
    }
        .configureEach {
            description = "$description (packages the QEMU engine and runtime assets built by :buildQemuArm64)"
            dependsOn(qemuEngine)
        }
    tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach {
        dependsOn(qemuEngine)
    }
} else {
    logger.lifecycle("QEMU engine build skipped (-PskipQemu=true); the VM will report 'engine not installed'.")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}