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

    // MediaRouter pinned to 1.6.0 (stable) to match Samsung TV+ — overrides
    // the 1.6.0-beta01 that media3-cast would otherwise pull in.
    implementation(libs.androidx.mediarouter)

    // Coil — loads the film-list thumbnails.
    implementation(libs.coil.compose)

    // Media3 (ExoPlayer) — the module set Samsung TV+ ships: exoplayer +
    // HLS (HLS-only) + UI + session (MediaSession service) + OkHttp data
    // source + cast. No DASH — Samsung TV+ doesn't use it.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.cast)
    implementation(libs.okhttp)
}
