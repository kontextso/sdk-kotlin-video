plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "so.kontext.video.publisher"
    compileSdk = 35

    defaultConfig {
        applicationId = "so.kontext.video.publisher"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Installable without a keystore; this app is never shipped.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("so.kontext:video-media3:${libs.versions.sdk.kotlin.video.get()}")
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
}
