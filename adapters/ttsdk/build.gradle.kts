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
    namespace = "so.kontext.video.ttsdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    buildTypes {
        release {
            // SDK doesn't minify itself — the consuming app's R8 pass
            // does that with full visibility into what's used.
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
        // Looper and Handler return defaults: the adapter's callbacks run inline, as on the main thread.
        unitTests.isReturnDefaultValues = true
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
    // Built with JDK 17; the bytecode is Java 11, so a Java 11 app reads it. A Kotlin app needs Kotlin 2.0 or later:
    // the dependencies (KontextKit, coroutines, serialization) bring kotlin-stdlib 2.x, whose metadata a 1.9
    // compiler refuses, so the SDK's own metadata is the compiler's (2.1, which Kotlin 2.0 reads).
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
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
        artifactId = "video-ttsdk",
        version = sdkVersion,
    )

    pom {
        name.set("Kontext Kotlin Video SDK: TTSDK")
        description.set("Follows a TTSDK TTVideoEngine for the Kontext Video SDK.")
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
    // Not shipped: the app has its own, licensed TTSDK (1.40 or later). Compiled against the oldest it supports.
    // Only its own classes: TTVideoEngine and VideoEngineCallback are all the adapter compiles against.
    compileOnly(libs.ttsdk.ttplayer.floor) { isTransitive = false }

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
}
