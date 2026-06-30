plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "so.kontext.video.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "so.kontext.video.example"
        minSdk = 30
        targetSdk = 35
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":video"))

    // Jetpack Compose + Material3 — mirrors Samsung TV+'s UI stack (exact
    // versions pinned in the catalog). The Media3 PlayerView is hosted via
    // AndroidView interop.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Coil — loads the film-list thumbnails.
    implementation(libs.coil.compose)

    // Media3 (ExoPlayer) — HLS + DASH adaptive streaming and progressive MP4
    // playback in the docked PlayerView.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)

    // Cast — CastPlayer + the Google Cast framework, to throw the docked video
    // onto a Chromecast / Cast-built-in TV (e.g. Sony Bravia).
    implementation(libs.androidx.media3.cast)
}
