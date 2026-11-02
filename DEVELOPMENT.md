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
├── video-media3/                             # VideoAds.track(Player) for a Media3 player
├── example/                                  # classic-View demo app: the four formats
├── AGENTS.md                                 # guidance for coding agents (CLAUDE.md imports it)
└── RELEASING.md                              # how to cut a release
```

## Building and testing

```bash
./gradlew assembleDebug                       # the SDK, the Media3 adapter and the example
./gradlew :video:testDebugUnitTest            # unit tests (JUnit 5 and Robolectric)
./gradlew spotlessApply detekt                # format + static analysis
./gradlew apiCheck                            # the public API against video/api and video-media3/api
./gradlew :video:createDebugUnitTestCoverageReport   # JaCoCo over the unit tests: video/build/reports/coverage/
./gradlew :video:connectedDebugAndroidTest    # emulator tests (a real WebView), on a running emulator
./gradlew spotlessCheck detekt :video:lintRelease :video-media3:lintRelease assembleDebug apiCheck :video:testDebugUnitTest :video-media3:testDebugUnitTest   # what CI runs, with the two below
```

`internal/CorpusTest` runs the malformed-answer corpus the Swift SDK runs too: `video/src/test/resources/corpus/`
is a copy of the `video-sdk-corpus` repo (internal), written by its `sync.sh` and never edited here; its README
has the format and how to add a case.

The publisher app (`extras/publisher-app`) is a separate build that sees only the published artifacts, as a
publisher's app does, and minifies them with R8. Its log (tag `PublisherApp`) shows an overlay requested,
loaded and shown:

```bash
./gradlew :video:publishToMavenLocal :video-media3:publishToMavenLocal
./gradlew -p extras/publisher-app assembleRelease   # or installRelease, on a running emulator
```

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
