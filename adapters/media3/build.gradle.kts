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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // SDK doesn't minify itself — the consuming app's R8 pass
            // does that with full visibility into what's used.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    // Every public declaration must spell out its visibility, so nothing
    // leaks to consumers by accident.
    explicitApi()
    // Built with JDK 17; the bytecode is Java 11 and the metadata Kotlin 1.9, so apps on an older toolchain
    // (Kotlin 1.9 or later, Java 11 or later) can use the SDK.
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
        // Interface methods with a body compile to Java default methods: a Java listener implements
        // only the callbacks it needs.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    // The release passes the key; a local publish (publishToMavenLocal, the release check) has none.
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
    config.from("${rootDir.absolutePath}/detekt.yml")
    parallel = true
}

dependencies {
    api(project(":video"))
    // The lowest Media3 the adapter compiles and works with: it needs only the Player interface. An app's own,
    // newer Media3 wins at resolution; a 1.6.0 floor would raise all of the app's Media3 modules to 1.6.0.
    api(libs.androidx.media3.common.floor)

    // The adapter's unit tests run in :video (Robolectric, a mocked Player). Here: a real ExoPlayer on an emulator, playing a clip from the test APK's assets.
    androidTestImplementation(libs.androidx.media3.exoplayer)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
