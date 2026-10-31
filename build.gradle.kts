// Top-level build file. Per-module config lives in each module's build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.detekt) apply false
    // apiDump / apiCheck: every public API change shows up in `*/api/*.api`, reviewed with the code.
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    nonPublicMarkers.add("androidx.annotation.RestrictTo")
}
