rootProject.name = "sdk-kotlin-video"

// ---------------------------------------------------------------------------
// pluginManagement — repositories Gradle searches for *plugins*
// (com.android.library, kotlin("android"), vanniktech maven-publish,
// spotless, detekt) applied via the `plugins { ... }` block in
// build.gradle.kts files. Plugin *versions* live in
// gradle/libs.versions.toml so this block only declares repositories.
// ---------------------------------------------------------------------------
pluginManagement {
    repositories {
        // AGP (Android Gradle Plugin) lives here. The content filter
        // says "only ever ask Google's repo for these groups" — Gradle
        // skips a redundant HTTP probe when resolving Kotlin / kotlinx
        // plugins (which live on Maven Central).
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Kotlin Gradle Plugin + most JetBrains-published plugins.
        mavenCentral()
        // Community plugins (vanniktech maven-publish, spotless, detekt).
        gradlePluginPortal()
    }
}

// ---------------------------------------------------------------------------
// dependencyResolutionManagement — repositories Gradle searches for
// *libraries* referenced by `dependencies { implementation("...") }`
// blocks in build.gradle.kts.
// ---------------------------------------------------------------------------
dependencyResolutionManagement {
    // Forbids individual modules from declaring their own
    // `repositories { ... }` blocks. Centralises the list here so a
    // future module can't quietly add a sketchy repo via a stray PR.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS

    repositories {
        // AndroidX, Material, Media3.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // TTSDK, only for :video-ttsdk to compile against (compileOnly); nothing from it ships.
        maven("https://artifact.byteplus.com/repository/public/") {
            content { includeGroup("com.bytedanceapi") }
        }
    }
}

include(":video", ":video-media3", ":video-ttsdk", ":video-compose", ":example")

// Player adapters live under adapters/, UI integrations under integrations/; each keeps its published name
// (so.kontext:video-<name>).
project(":video-media3").projectDir = file("adapters/media3")
project(":video-ttsdk").projectDir = file("adapters/ttsdk")
project(":video-compose").projectDir = file("integrations/compose")
