// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // AGP 9.0 provides built-in Kotlin support; the standalone org.jetbrains.kotlin.android
    // plugin is no longer applied. See https://kotl.in/gradle/agp-built-in-kotlin
    alias(libs.plugins.android.application) apply false
}
