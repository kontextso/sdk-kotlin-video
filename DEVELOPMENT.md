# Development

This guide is for contributors working on the Kontext Kotlin Video SDK locally.

## Prerequisites

- **JDK 17** (Temurin recommended) — `brew install --cask temurin@17`
- **Android SDK** with platform 35 installed (Android Studio, or `sdkmanager "platforms;android-35"`)
- **An Android emulator or physical device** running API 24+ for the example app

Gradle 8.11 is downloaded by the wrapper on first build.

## Repository layout

```
sdk-kotlin-video/
├── video/src/main/kotlin/so/kontext/video/   # the public API only (KontextVideo, VideoAds, VideoAd, …)
│   └── internal/                             #   Sdk.kt, then ad/ rendering/ playback/ wire/ network/ device/ support/
├── video/src/test/                           # JUnit 5 and Robolectric tests
├── video/api/video.api                       # the public API, checked by apiCheck
├── adapters/media3/                          # :video-media3, VideoAds.track(Player) for a Media3 player
├── example/                                  # classic-View demo app: the four formats
├── extras/release-check/                     # a publisher's app built from the published SDK (see its README)
├── detekt.yml                                # lint rules
├── AGENTS.md                                 # guidance for coding agents (CLAUDE.md imports it)
└── RELEASING.md                              # how to cut a release
```

## Building and testing

```bash
./gradlew assembleDebug                       # the SDK, the Media3 adapter and the example
./gradlew :video:testDebugUnitTest            # unit tests (JUnit 5 and Robolectric)
./gradlew spotlessApply detekt                # format + static analysis
./gradlew apiCheck                            # the public API against video/api and adapters/media3/api
./gradlew :video:createDebugUnitTestCoverageReport   # JaCoCo over the unit tests: video/build/reports/coverage/
./gradlew :video:connectedDebugAndroidTest    # emulator tests (a real WebView), on a running emulator
./gradlew spotlessCheck detekt :video:lintRelease :video-media3:lintRelease assembleDebug apiCheck :video:testDebugUnitTest :video-media3:testDebugUnitTest   # what CI runs, with the two below
```

`internal/CorpusTest` runs the malformed-answer corpus the Swift SDK runs too: `video/src/test/resources/corpus/`
is a copy of the `video-sdk-corpus` repo (internal), written by its `sync.sh` and never edited here; its README
has the format and how to add a case.

The release check (`extras/release-check`, see its README) is a separate build that sees only the published
artifacts, as a publisher's app does, and minifies them with R8. Its log (tag `ReleaseCheck`) shows an overlay requested,
loaded and shown:

```bash
./gradlew :video:publishToMavenLocal :video-media3:publishToMavenLocal
./gradlew -p extras/release-check assembleRelease   # or installRelease, on a running emulator
```

Once a version is on Maven Central, the same app builds against it instead of mavenLocal:

```bash
./gradlew -p extras/release-check assembleRelease -PsdkFromMavenCentral -PsdkVersion=0.1.0
```

## CI

`.github/workflows/ci.yml` runs on pushes to `main` and on pull requests:

- **Lint**: `spotlessCheck` and `detekt`.
- **Build and Test**: `assembleDebug`, `apiCheck` and the unit tests.
- **Emulator Tests (API 24)** and **(API 34)**: `:video:connectedDebugAndroidTest`, on API 24 (the minSdk) and 34.
  API 24's emulator image has a WebView older than the message listener (WebView 83) and nothing updates it, so
  the two tests that need the page's own messages skip there; the job prints the WebView's version first.
- **Release check (R8)**: the artifacts published to mavenLocal, then `extras/release-check` built from them,
  minified.

`.github/workflows/publish_sdk.yml` publishes a release tag (see [RELEASING.md](RELEASING.md)).
`.github/workflows/release-consumer.yml` builds the release check from Maven Central once that release is there:
after **Publish SDK** succeeds (it waits for the release to reach Maven Central, up to 90 minutes), and by hand
(Actions → Release consumer → Run workflow, with the version).

## What a publisher needs

From the build files and the artifacts' dependencies:

- **minSdk 24** (`video` and `video-media3`).
- **compileSdk 34** for `so.kontext:video`, **35** with `so.kontext:video-media3`: AndroidX Core 1.13.1, Activity
  1.8.2 and WebKit 1.12.1 require 34 in their AAR metadata, Media3 1.6.0 requires 35. AGP refuses a lower one.
- **Android Gradle Plugin 8.1.1+** for compileSdk 34, **8.6+** for 35 (the first versions that support those API
  levels), with the Gradle version that AGP requires (8.0+ and 8.7+), and **JDK 17** (what AGP 8 runs on; the
  artifacts are Java 17 bytecode, `jvmToolchain(17)`).
- **Kotlin 2.0+** for a Kotlin app: the artifacts are compiled with Kotlin 2.1.0, and a Kotlin compiler reads
  metadata at most one version newer than its own. A Java app needs no Kotlin plugin.
- R8 needs nothing from the app: the consumer rules ship in the AAR.

What CI builds a publisher's app with (`extras/release-check`): AGP 8.7.3, Gradle 8.11.1, Kotlin 2.1.0, JDK 17,
compileSdk 35. The older minimums above follow from the metadata and are not built by any job.

## Running the example app

The example talks to the production ad server, whose demo routes answer any token: four videos, one per
situation, each with its labels.

```bash
./gradlew :example:installDebug
adb shell am start -n so.kontext.video.example/.MainActivity                    # the home screen
adb shell am start -n so.kontext.video.example/.MainActivity --es video demo-overlay   # one video directly
adb logcat -s KontextVideo
```

An emulator needs enough memory for ExoPlayer and a WebView at once: give the AVD 4 GB or more (`hw.ramSize` in
its `config.ini`). With too little, the system swaps and reports the app as not responding.
