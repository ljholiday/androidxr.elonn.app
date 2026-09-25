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
    implementation("androidx.xr.runtime:runtime:1.0.0-rc01")
    implementation("androidx.xr.scenecore:scenecore:1.0.0-rc01")
    implementation("androidx.xr.compose:compose:1.0.0-beta01")
    implementation("androidx.xr.compose.material3:material3:1.0.0-alpha17")

    // World/Conductor networking -- same real contract every other Runtime uses
    // (POST /world/call, api.elonn's generic auth-form Dataset). org.json is
    // used for the JSON envelopes since it ships in the Android platform already.
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
