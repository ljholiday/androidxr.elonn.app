plugins {
    // AGP 9's built-in Kotlin support means the org.jetbrains.kotlin.android
    // plugin is no longer needed, but the Compose Compiler plugin still is.
    // Version pinned to match the Kotlin Gradle Plugin AGP 9's built-in Kotlin
    // uses by default (2.2.10).
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
