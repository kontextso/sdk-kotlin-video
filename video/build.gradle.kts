plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    namespace = "so.kontext.video"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-proguard-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
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
            it.useJUnitPlatform()
        }
        // Stubs Android framework methods called from unit tests
        // (e.g. `android.util.Log.d(...)`) to return defaults instead
        // of throwing `RuntimeException("Method ... not mocked")`.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    // Every public declaration must spell out its visibility, so nothing
    // leaks to consumers by accident.
    explicitApi()
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
        description.set("Android SDK for integrating Kontext video ads into short-drama and other video-first apps.")
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
    buildUponDefaultConfig = true
    config.from("${rootDir.absolutePath}/extras/detekt.yml")
    parallel = true
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ComponentActivity for the interstitial screen (back-press control).
    implementation(libs.androidx.activity)
    implementation(libs.androidx.annotation)
    // Chrome Custom Tabs for the click-through.
    implementation(libs.androidx.browser)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
