buildscript {
    // AGP's built-in Kotlin defaults to an older compiler; supabase-kt needs 2.4.
    dependencies { classpath(libs.kotlin.gradle.plugin) }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
}
