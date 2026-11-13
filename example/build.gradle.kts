plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "so.kontext.video.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "so.kontext.video.example"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Your publisher token, never committed: `-PkontextPublisherToken=…` or `kontextPublisherToken=…` in
        // ~/.gradle/gradle.properties. Without it the app runs, and the ad server refuses its requests.
        val publisherToken = providers.gradleProperty("kontextPublisherToken").getOrElse("your-publisher-token")
        buildConfigField("String", "PUBLISHER_TOKEN", "\"$publisherToken\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The SDK with its Media3 adapter: ads.track(exoPlayer).
    implementation(project(":video-media3"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
}
