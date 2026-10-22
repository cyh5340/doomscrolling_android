plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.doomscroll.network"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        // Defaults target the local Supabase stack (`supabase start`) from the emulator.
        // Override in ~/.gradle/gradle.properties or with -P for a real project.
        val prop = { name: String, default: String -> providers.gradleProperty(name).getOrElse(default) }
        buildConfigField("String", "SUPABASE_URL", "\"${prop("supabaseUrl", "http://10.0.2.2:54321")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${prop("supabaseKey", "sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH")}\"")
        buildConfigField("String", "INVITE_HOST", "\"${prop("inviteHost", "doomscroll.example")}\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":core:data"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    api(libs.work.runtime)
    api(platform(libs.supabase.bom))
    api(libs.supabase.auth)
    api(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.okhttp)
    api(libs.serialization.json)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.coroutines.play.services)
}
