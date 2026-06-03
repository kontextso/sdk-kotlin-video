plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "so.kontext.video.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "so.kontext.video.example"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":video"))

    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Media3 (ExoPlayer) — plays the sample clip. The video SDK will
    // later hook into a player like this one.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
}
