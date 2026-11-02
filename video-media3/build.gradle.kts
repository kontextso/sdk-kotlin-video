plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

// SDK version — the published Maven artifact version, the same as the SDK's.
// The catalog value; `-PsdkVersion=X.Y.Z` overrides it for a local build.
// The release workflow publishes the catalog value, after checking the tag equals it.
val sdkVersion: String = providers.gradleProperty("sdkVersion")
    .orElse(libs.versions.sdk.kotlin.video)
    .get()

group = "so.kontext"
version = sdkVersion

android {
    namespace = "so.kontext.video.media3"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
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
    compilerOptions {
        // Interface methods with a body compile to Java default methods: a Java listener implements
        // only the callbacks it needs.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    // The release passes the key; a local publish (publishToMavenLocal, the publisher-app check) has none.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "video-media3",
        version = sdkVersion,
    )

    pom {
        name.set("Kontext Kotlin Video SDK: Media3")
        description.set("Follows a Media3 Player for the Kontext Video SDK.")
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
    api(project(":video"))
    // The app's own Media3 version wins: this adapter needs only the Player interface.
    api(libs.androidx.media3.common)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
}
