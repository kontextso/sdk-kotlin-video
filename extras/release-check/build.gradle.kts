plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "so.kontext.video.releasecheck"
    compileSdk = 35

    defaultConfig {
        applicationId = "so.kontext.video.releasecheck"
        minSdk = 26
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

// The catalog's version, or -PsdkVersion: a release from Maven Central other than the one the catalog names.
val sdkVersion: String = providers.gradleProperty("sdkVersion").orElse(libs.versions.sdk.kotlin.video).get()

dependencies {
    implementation("so.kontext:video-media3:$sdkVersion")
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
}
