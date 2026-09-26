plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.elonn.androidxr"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.elonn.androidxr"
        // androidx.xr.compose currently requires minSdk 30 at build time even though
        // Jetpack XR APIs themselves only require API 34 at runtime.
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

configurations.all {
    resolutionStrategy {
        // androidx.xr.compose.material3 transitively pulls material3
        // 1.5.0-alpha17, which wins Gradle's default "highest version wins"
        // conflict resolution over the compose-bom's stable pin. That alpha
        // has a real, reproducible OutlinedTextField crash (confirmed
        // on-device: NoSuchElementException in
        // OutlinedTextFieldMeasurePolicy.measure, same crash regardless of
        // BOM version or layout changes -- the resolved jar was the actual
        // variable). Force the stable version everywhere.
        force("androidx.compose.material3:material3:1.4.0")
    }
}

dependencies {
    // Ordinary Android/Compose -- this is the "Android application layer" from the
    // decision record: the first APK is a plain 2D app, not an XR scene, on purpose.
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // Jetpack XR SDK -- not used by the first screen yet, wired in now so the
    // dependency setup itself is part of the spike, not a later surprise.
    // Session/Geospatial (androidx.xr.runtime, androidx.xr.arcore) were tried
    // and dropped: GeospatialMode requires a live Google Cloud API key/
    // account for VPS, an ongoing service dependency this platform
    // deliberately avoids (decision.native_android_xr_candidate_runtime_20260924's
    // "I am an open source guy from day one" -- see also the live session
    // where this was caught: "I don't want to be married to google"). Plain
    // ARCore's own local anchors need no such thing, and Geospatial was the
    // only reason this app needed the Jetpack XR Session wrapper at all, so
    // dropping it also let the earlier native HardwareBuffer bridge (worked
    // around Jetpack XR's own off-GL-thread update loop) come out entirely --
    // this app now owns a plain ARCore Session directly, on its own GL
    // thread, the standard way every ordinary ARCore app does.
    implementation("androidx.xr.scenecore:scenecore:1.0.0-rc01")
    implementation("androidx.xr.compose:compose:1.0.0-beta01")
    implementation("androidx.xr.compose.material3:material3:1.0.0-alpha17")

    // Plain ARCore: local motion tracking and local Anchors, entirely
    // on-device, no Google Cloud account/API key/network dependency. GPS and
    // compass (Geo.kt, FieldCamera.kt) are used only once, to compute each
    // Field object's initial position relative to the camera's own local
    // tracking origin at that moment -- ARCore's own tracked Anchor pose
    // drives everything after that, never per-frame heading/pitch/roll math.
    implementation("com.google.ar:core:1.56.0")

    // World/Conductor networking -- same real contract every other Runtime uses
    // (POST /world/call, api.elonn's generic auth-form Dataset). org.json is
    // used for the JSON envelopes since it ships in the Android platform already.
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Encrypted on-device token storage -- xreal.elonn.app persists its session
    // token via Unity PlayerPrefs (plaintext); Android's standard equivalent for
    // a real bearer credential is EncryptedSharedPreferences, not a plaintext file.
    implementation("androidx.security:security-crypto:1.1.0")
}
