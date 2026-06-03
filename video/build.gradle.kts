plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

// SDK version — single source of truth for both the published Maven
// artifact version AND the runtime `BuildConfig.SDK_VERSION` field.
// Defaults to the catalog value; CI patch releases override via
// `-PsdkVersion=X.Y.Z`.
val sdkVersion: String = providers.gradleProperty("sdkVersion")
    .orElse(libs.versions.sdk.kotlin.video)
    .get()

group = "so.kontext"
version = sdkVersion

android {
    // Java/Kotlin package prefix used to generate BuildConfig + R.
    // Must match the Kotlin source package of the public API
    // (`so.kontext.video.*`).
    namespace = "so.kontext.video"

    // SDK we compile against — Android 15. Matches the tvplus-test-player
    // reference app and the Samsung TV+ target host.
    compileSdk = 35

    defaultConfig {
        // Minimum runtime API. Android 11 — matches Samsung TV+'s floor
        // and the tvplus-test-player.
        minSdk = 30

        // ProGuard rules packaged into the .aar; auto-applied during
        // the consuming app's R8 pass.
        consumerProguardFiles("consumer-proguard-rules.pro")

        // SDK version baked in at build time. Read via
        // `BuildConfig.SDK_VERSION` from `SDKInfo.kt` so the version
        // sent to the ad server can never drift from the published
        // Maven artifact version.
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        // Enables the `androidx.compose.*` source sets and Compose
        // compiler integration, so the SDK can expose `@Composable` ad
        // surfaces. Samsung TV+ and other target hosts are Compose apps.
        // Compose compiler is configured by the
        // `org.jetbrains.kotlin.plugin.compose` plugin (applied above) —
        // no `composeOptions` block needed on Kotlin 2.x.
        compose = true
        // Generates `BuildConfig.java` so `buildConfigField` values are
        // reachable from Kotlin source.
        buildConfig = true
    }

    buildTypes {
        release {
            // SDK doesn't minify itself — the consuming app's R8 pass
            // does that with full visibility into what's used.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        unitTests.all {
            // JUnit 5 (Jupiter).
            it.useJUnitPlatform()
        }
        // Stubs Android framework methods called from unit tests
        // (e.g. `android.util.Log.d(...)`) to return defaults instead
        // of throwing `RuntimeException("Method ... not mocked")`.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    // Requires every top-level / member declaration to specify
    // `public` / `internal` / `private` explicitly. Catches the
    // case where someone forgets a visibility modifier and a class
    // accidentally leaks to consumers.
    explicitApi()

    // All Kotlin compilation + tests run against JDK 17 regardless of
    // the developer's locally installed JDK.
    jvmToolchain(17)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "video",
        version = sdkVersion,
    )

    pom {
        name.set("Kontext Kotlin Video SDK")
        description.set("Android SDK for integrating Kontext's AI-powered ads into video experiences.")
        url.set("https://www.kontext.so/advertisers")

        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("kontext")
                name.set("kontext")
            }
        }

        scm {
            url.set("https://github.com/kontextso/sdk-kotlin-video")
            connection.set("scm:git:https://github.com/kontextso/sdk-kotlin-video.git")
            developerConnection.set("scm:git:ssh://github.com/kontextso/sdk-kotlin-video.git")
        }
    }
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()

    val ktlintConfig = mapOf(
        // Package-concept filenames are intentional.
        "ktlint_standard_filename" to "disabled",
    )

    kotlin {
        target("src/**/*.kt")
        targetExclude("**/build/**/*.kt")
        ktlint(ktlintVersion).editorConfigOverride(ktlintConfig)
    }

    kotlinGradle {
        target("*.gradle.kts")
        targetExclude("**/build/**/*.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintConfig)
    }

    format("xml") {
        target("**/*.xml")
        targetExclude("**/build/**/*.xml")
    }
}

detekt {
    // Layer our config on top of detekt's bundled defaults.
    buildUponDefaultConfig = true
    config.from("${rootDir.absolutePath}/extras/detekt.yml")
    parallel = true
}

dependencies {
    // ---- Kotlin runtime libraries ----
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ---- AndroidX ----
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // ---- Jetpack Compose ----
    // BOM pins a version-aligned set; the deps below inherit versions
    // from it. Only ui / runtime / foundation are needed for SDK-side
    // Composable surfaces (material lives in the host app).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)

    // ---- Testing ----
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
