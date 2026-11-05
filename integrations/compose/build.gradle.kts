plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
    namespace = "so.kontext.video.compose"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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

    testOptions {
        // Robolectric runs on JUnit 4: the vintage engine runs those tests on the JUnit Platform.
        unitTests.all {
            it.useJUnitPlatform()
        }
        // Robolectric reads the merged manifest and resources.
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

// The unit tests drive real ads without a server, as :video's own tests do: a loaded ad made from a payload,
// its page's messages sent by hand. That needs :video's internals, so the test compilation is its friend.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("UnitTest")) {
        val videoBuild = project(":video").layout.buildDirectory.get().asFile
        friendPaths.from(libraries.filter { it.startsWith(videoBuild) })
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
        artifactId = "video-compose",
        version = sdkVersion,
    )

    pom {
        name.set("Kontext Kotlin Video SDK: Compose")
        description.set("Jetpack Compose functions for the Kontext Video SDK.")
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
    // The lowest Compose the module compiles and works with. An app's own, newer Compose wins at resolution.
    api(libs.androidx.compose.ui.floor)
    api(libs.androidx.compose.foundation.layout.floor)

    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.androidx.test.core)
    // A payload parsed as the SDK parses the server's answer.
    testImplementation(libs.kotlinx.serialization.json)
}
