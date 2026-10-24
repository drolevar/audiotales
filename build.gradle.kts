buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP provides built-in Kotlin; align its compiler with the Compose and
        // serialization plugins without applying org.jetbrains.kotlin.android.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
