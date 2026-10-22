plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.doomscroll.data"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:usage"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
}
