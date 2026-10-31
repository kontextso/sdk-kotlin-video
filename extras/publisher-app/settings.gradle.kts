// A publisher's app, outside the SDK's build: it sees only the published artifacts, from mavenLocal
// (./gradlew publishToMavenLocal in the repo root first). The release build runs R8 over them with the
// consumer rules they ship. Run: ../../gradlew -p extras/publisher-app assembleRelease
rootProject.name = "publisher-app"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenLocal { content { includeGroup("so.kontext") } }
        google()
        mavenCentral()
    }
    // The SDK's own versions, so this app moves with it.
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
