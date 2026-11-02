// A publisher's app, outside the SDK's build: it sees only the published artifacts, from mavenLocal
// (./gradlew publishToMavenLocal in the repo root first). The release build runs R8 over them with the
// consumer rules they ship. Run: ../../gradlew -p extras/publisher-app assembleRelease
// With -PsdkFromMavenCentral (and -PsdkVersion=X.Y.Z for another release than the catalog's), the artifacts
// come from Maven Central instead, as a publisher gets a release: .github/workflows/release-consumer.yml.
rootProject.name = "publisher-app"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val sdkFromMavenCentral = providers.gradleProperty("sdkFromMavenCentral").isPresent

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        if (!sdkFromMavenCentral) mavenLocal { content { includeGroup("so.kontext") } }
        google()
        mavenCentral()
    }
    // The SDK's own versions, so this app moves with it.
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
