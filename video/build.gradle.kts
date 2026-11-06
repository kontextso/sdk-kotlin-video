plugins {
    // Registers JaCoCo on every test task, so the Robolectric setting below has somewhere to go.
    jacoco
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

// SDK version — single source of truth for both the published Maven
// artifact version AND the runtime `KontextVideo.VERSION` (an internal generated source, not a public BuildConfig).
// The catalog value; `-PsdkVersion=X.Y.Z` overrides it for a local build.
// The release workflow publishes the catalog value, after checking the tag equals it.
val sdkVersion: String = providers.gradleProperty("sdkVersion")
    .orElse(libs.versions.sdk.kotlin.video)
    .get()

group = "so.kontext"
version = sdkVersion

android {
    namespace = "so.kontext.video"
    compileSdk = 35

    // Every resource of the SDK starts with this, so none collides with an app's.
    resourcePrefix = "kontext_video_"

    defaultConfig {
        minSdk = 26
        // Emulator tests: what needs a real WebView (`./gradlew :video:connectedDebugAndroidTest`).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-proguard-rules.pro")
    }

    buildTypes {
        debug {
            // `./gradlew :video:createDebugUnitTestCoverageReport`: JaCoCo over the unit tests.
            enableUnitTestCoverage = true
        }
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
        unitTests.all {
            it.useJUnitPlatform()
        }
        // Robolectric reads the merged manifest and resources.
        unitTests.isIncludeAndroidResources = true
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
    config.from("${rootDir.absolutePath}/detekt.yml")
    parallel = true
}

/** Writes the SDK version into an internal Kotlin source: read at run time, and not part of the public API. */
abstract class GenerateSdkVersion : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val file = outputDir.file("so/kontext/video/internal/SdkVersion.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package so.kontext.video.internal\n\n" +
                "/** The SDK version, generated from the build. */\n" +
                "internal const val SDK_VERSION: String = \"${version.get()}\"\n",
        )
    }
}

val generateSdkVersion = tasks.register<GenerateSdkVersion>("generateSdkVersion") {
    version.set(sdkVersion)
    outputDir.set(layout.buildDirectory.dir("generated/sdkVersion/kotlin"))
}

androidComponents {
    onVariants { variant ->
        // Through the Java sources: the Kotlin plugin compiles the .kt files there; its own set is not wired from here.
        variant.sources.java?.addGeneratedSourceDirectory(generateSdkVersion, GenerateSdkVersion::outputDir)
    }
}

dependencies {
    // `suspend` in the public API: kotlinx.coroutines is part of it.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Device info, install id, advertising id, TCF consent (no Open Measurement since KontextKit 0.1.0).
    implementation(libs.kontext.kit)

    // ComponentActivity for the linear ad's screen (back-press control).
    implementation(libs.androidx.activity)
    // The frame page's messages, with the frame and origin they came from.
    implementation(libs.androidx.webkit)
    // Insets in show(container, insets), @MainThread.
    api(libs.androidx.core.ktx)
    api(libs.androidx.annotation)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.androidx.recyclerview) // a container show must refuse
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    // Robolectric runs on JUnit 4: the vintage engine runs those tests on the JUnit Platform.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.androidx.test.core)
    // A real HTTP server on localhost: the SDK's own HttpURLConnection calls run unchanged.
    testImplementation(libs.mockwebserver)
    // Robolectric tests drive the Media3 adapter with a mocked Player.
    testImplementation(project(":video-media3"))
    testImplementation(libs.androidx.media3.common)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// Robolectric loads the SDK's classes through its own class loader; JaCoCo counts them only with this.
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}
