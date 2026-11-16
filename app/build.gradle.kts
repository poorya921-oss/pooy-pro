plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ABIs to package. Default: arm64-v8a only (smallest APK, covers virtually all current phones).
// Override:  gradle assembleRelease -Ppooy.abis=arm64-v8a,armeabi-v7a,x86_64
val abis = (providers.gradleProperty("pooy.abis").orNull ?: "arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.pooy.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pooy.vpn"
        minSdk = 26
        targetSdk = 36
        // CI passes -Ppooy.versionCode=<run number>; Play requires a higher code for every upload.
        versionCode = providers.gradleProperty("pooy.versionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("pooy.versionName").orNull ?: "1.0.0"

        ndk { abiFilters += abis }
        externalNativeBuild {
            ndkBuild {
                arguments("NDK_APPLICATION_MK=${projectDir}/src/main/jni/Application.mk")
            }
        }
    }

    externalNativeBuild {
        ndkBuild { path = file("src/main/jni/Android.mk") }
    }

    // Release signing comes ONLY from the environment / Gradle properties (GitHub Actions secrets); nothing is committed.
    //   POOOY_KEYSTORE_FILE, POOOY_KEYSTORE_PASSWORD, POOOY_KEY_ALIAS, POOOY_KEY_PASSWORD
    fun signingValue(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
    val releaseKeystore = signingValue("POOOY_KEYSTORE_FILE")?.let { file(it) }
    val releaseSigningReady = releaseKeystore != null && releaseKeystore.exists() &&
        signingValue("POOOY_KEYSTORE_PASSWORD") != null &&
        signingValue("POOOY_KEY_ALIAS") != null &&
        signingValue("POOOY_KEY_PASSWORD") != null

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("POOOY_KEYSTORE_PASSWORD")
                keyAlias = signingValue("POOOY_KEY_ALIAS")
                keyPassword = signingValue("POOOY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            } else if (providers.gradleProperty("pooy.debugSign").isPresent) {
                // Personal-use convenience only: -Ppooy.debugSign=true signs with the debug key. Not valid for Play.
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    // Play uploads are AABs; ABI split lets each device download only its own native libraries.
    bundle { abi { enableSplit = true } }

    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        // Compressed native libs inside the APK => smaller download (larger install size).
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
        }
    }

    lint { abortOnError = false }
}

dependencies {
    // Xray-core (gomobile AAR from 2dust/AndroidLibXrayLite). Fetched by scripts/fetch-deps.*
    implementation(files("libs/libv2ray.aar"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // QR scanning: ZXing core (decoder only, ~0.5 MB) + CameraX
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Fail early if an external piece is missing, unpinned, or does not match scripts/deps.lock.
val checkNativeDeps = tasks.register("checkNativeDeps") {
    val aar = file("libs/libv2ray.aar")
    val hev = file("src/main/jni/hev-socks5-tunnel/Android.mk")
    val lock = rootProject.file("scripts/deps.lock")
    inputs.files(lock)
    doLast {
        val problems = ArrayList<String>()
        if (!aar.exists()) problems.add("app/libs/libv2ray.aar  (Xray-core AAR) is missing")
        if (!hev.exists()) problems.add("app/src/main/jni/hev-socks5-tunnel  (tun2socks sources) is missing")
        val pinned = lock.readLines()
            .filter { it.contains("=") && !it.trimStart().startsWith("#") }
            .associate { it.substringBefore("=").trim() to it.substringAfter("=").substringBefore("#").trim() }
        val want = pinned["XRAY_AAR_SHA256"].orEmpty()
        if (want.isEmpty()) {
            problems.add("XRAY_AAR_SHA256 is not pinned in scripts/deps.lock (see README: Dependencies)")
        } else if (aar.exists()) {
            val got = java.security.MessageDigest.getInstance("SHA-256").digest(aar.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (!got.equals(want, ignoreCase = true)) problems.add("libv2ray.aar SHA-256 mismatch (expected $want, got $got)")
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "POOOY external dependency check failed:\n  - " + problems.joinToString("\n  - ") +
                    "\nRun scripts/fetch-deps.sh (Linux/macOS) or scripts/fetch-deps.ps1 (Windows), or see README.md."
            )
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(checkNativeDeps) }
