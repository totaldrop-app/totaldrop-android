// SPDX-FileCopyrightText: 2026 The TotalDrop Contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import java.util.Properties

plugins {
    id("com.android.application") version "8.9.1"
    id("org.jetbrains.kotlin.android") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

// Release signing mirrors ui-m3-expressive/android/app/build.gradle.kts:
// CI supplies the upload key via environment variables so passwords never touch disk.
val keyProperties = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val envStoreFile: String? = System.getenv("ANDROID_UPLOAD_STORE_FILE")
val signingFromEnv = !envStoreFile.isNullOrEmpty()
val hasReleaseKey = signingFromEnv || keyProperties.getProperty("storeFile") != null
if (!hasReleaseKey && !System.getenv("TOTALDROP_REQUIRE_RELEASE_KEY").isNullOrEmpty()) {
    throw GradleException("No upload key: set ANDROID_UPLOAD_STORE_FILE or ui-android-tv/key.properties")
}

// Optional single-ABI filter passed by target-*/build-*.sh (-PtargetAbi=arm64-v8a).
val requestedAbi: String? = (findProperty("targetAbi") as? String)?.takeIf { it.isNotBlank() }

val abiVersionMultipliers = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86_64" to 4,
)

android {
    namespace = "app.totaldrop.tv"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.totaldrop.tv"
        minSdk = 31
        targetSdk = 35
        versionCode = 15
        versionName = "0.4.0-beta.1"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                if (signingFromEnv) {
                    val password = System.getenv("ANDROID_UPLOAD_PASSWORD")
                    storeFile = file(envStoreFile!!)
                    storePassword = password
                    keyAlias = System.getenv("ANDROID_UPLOAD_ALIAS") ?: "upload"
                    keyPassword = password
                } else {
                    storeFile = file(keyProperties.getProperty("storeFile"))
                    storePassword = keyProperties.getProperty("storePassword")
                    keyAlias = keyProperties.getProperty("keyAlias")
                    keyPassword = keyProperties.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            if (requestedAbi != null) {
                include(requestedAbi)
            } else {
                include("arm64-v8a", "armeabi-v7a", "x86_64")
            }
            isUniversalApk = false
        }
    }

    // Store native .so libraries uncompressed and page-aligned for Android 15+ 16 KB page sizes.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            val multiplier = abiVersionMultipliers[abi] ?: 0
            if (multiplier > 0) {
                val baseCode = android.defaultConfig.versionCode ?: 1
                output.versionCode.set(multiplier * 1000 + baseCode)
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)

    // Jetpack Compose for TV (Material 3 for TV 10-foot UI & D-Pad focus primitives)
    implementation("androidx.tv:tv-material:1.0.1")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha12")

    // Compose core & Android 12+ (API 31+) dynamic color extraction
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Activity, lifecycle, and structured concurrency
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Pure-Java QR matrix encoder (Apache-2.0, zero Android permissions, offline QR generation)
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
