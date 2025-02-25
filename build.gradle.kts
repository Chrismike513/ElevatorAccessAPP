buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // Required Gradle dependencies for your project
        classpath(libs.gradle)
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.kotlin.gradle.plugin.v1925)
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // Apply Android and Kotlin plugins for all modules
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    id("com.android.library") version "8.1.4" apply false

}

extra["compose_ui_version"] = "1.2.0"
