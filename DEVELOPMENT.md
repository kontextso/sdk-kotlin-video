# Development

This guide is for contributors working on the Kontext Kotlin Video SDK locally.

## Prerequisites

- **JDK 17** (Temurin recommended) — `brew install --cask temurin@17`
- **Android SDK** with platform 35 installed (Android Studio, or `sdkmanager "platforms;android-35"`)
- **An Android emulator or physical device** running API 24+ (Android 7) for the example app

Gradle 8.11 is downloaded by the wrapper on first build.

## Repository layout

```
sdk-kotlin-video/
├── video/src/main/kotlin/so/kontext/video/   # the public API only (KontextVideo, VideoAds, VideoAd, …)
│   └── internal/                             #   SdkInitializer.kt, state/, then frame/ screen/ measurement/ content/ payloads/ network/ device/ diagnostics/ platform/
│                                             #   state/: the facts of the SDK, of each ad and of each episode's ads, and the only ways they change
│                                             #   state/SdkState.kt, state/AdState.kt: every fact (one source of truth)
│                                             #   state/SdkLifecycle.kt, state/AdLifecycle.kt: the legal moves, stateless
│                                             #   SdkInitializer.kt: the entry point of the internals (initialize, /config, the kill switch); the rest is in folders by topic
│                                             #   frame/: the ad's web page and its messages
│                                             #   frame/FrameProtocol.kt: the frame protocol's rules, no WebView
│                                             #   frame/FrameMessage.kt: one page message read (the envelope, the fields)
│                                             #   screen/: putting the ad on screen
│                                             #   screen/ScreenSlot.kt: the app's one ad on screen, from show to onAdEnded
│                                             #   measurement/: what the viewer saw (visibility, time on screen) and the beacons that report it
│                                             #   format differences: content/ContentRule.kt, screen/AdScreen.kt
├── video/src/test/                           # JUnit 5 and Robolectric tests
├── video/api/video.api                       # the public API, checked by apiCheck
├── adapters/                                 # player adapters, one module each (see adapters/README.md)
│   ├── media3/                               #   :video-media3, VideoAds.track(Player) for a Media3 player
│   ├── ttsdk/                                #   :video-ttsdk, VideoAds.track(TTVideoEngine) for a TTSDK engine
│   └── txplayer/                             #   :video-txplayer, VideoAds.track(TXVodPlayer) for a Tencent Cloud Player
├── integrations/compose/                     # :video-compose, rememberVideoAds and VideoAdHost for Jetpack Compose
├── example/                                  # classic-View demo app, five videos (see its README)
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
./gradlew spotlessCheck detekt :video:lintRelease :video-media3:lintRelease :video-ttsdk:lintRelease :video-txplayer:lintRelease :video-compose:lintRelease assembleDebug apiCheck :video:testDebugUnitTest :video-media3:testDebugUnitTest :video-ttsdk:testDebugUnitTest :video-txplayer:testDebugUnitTest :video-compose:testDebugUnitTest   # what CI runs, with the two below
```

`internal/CorpusTest` runs the malformed-answer corpus the Swift SDK runs too: `video/src/test/resources/corpus/`
is a copy of the `video-sdk-corpus` repo (internal), written by its `sync.sh` and never edited here; its README
has the format and how to add a case.

The release check (`extras/release-check`, see its README) is a separate build that sees only the published
artifacts, as a publisher's app does, and minifies them with R8. Its log (tag `ReleaseCheck`) shows an overlay requested,
loaded and shown:

```bash
./gradlew :video:publishToMavenLocal :video-media3:publishToMavenLocal :video-ttsdk:publishToMavenLocal :video-txplayer:publishToMavenLocal :video-compose:publishToMavenLocal
./gradlew -p extras/release-check assembleRelease   # or installRelease, on a running emulator
```

Once a version is on Maven Central, the same app builds against it instead of mavenLocal:

```bash
./gradlew -p extras/release-check assembleRelease -PsdkFromMavenCentral -PsdkVersion=0.1.0
```

## Comments

Every file starts with a comment that says, in this order: what it is, in one plain sentence; why it exists or is
shaped this way, when that is not obvious; a picture or example (an ASCII diagram, a table, real numbers) when the
idea is spatial, a flow or a decision; where it fits: who uses it and what it hands off to. The main function of a
file with a multi-step flow gets numbered step comments (`// 1. …`, `// 2. …`). Small, obvious files get only the
first line. Never repeat what the code says; explain what it cannot. See `internal/screen/InlineAdView.kt`: its top
comment, and `onLayout`'s numbered steps.

## Naming

A boolean that names a state reads as an assertion: `is`, `has` or `was` before the adjective or participle
(`isLoaded`, `isCompleted`, `hasImpression`, `wasOnScreen`, `isStopped`, `wasPlaying`). A boolean named by a
third-person verb keeps it (`countsTime`, `checksEpisode`, `holdsAds`, `completesAd`). A field that mirrors a wire
format or a standard keeps that name (`Regulatory.gdpr`, `coppa`, the config's `reportErrors`, JSON keys). Where
Swift has the same field, the name is the same.

## CI

`.github/workflows/ci.yml` runs on pushes to `main` and on pull requests:

- **Lint**: `spotlessCheck` and `detekt`.
- **Build and Test**: `assembleDebug`, `apiCheck` and the unit tests.
- **Emulator Tests (API 24)**, **(API 26)** and **(API 34)**: the `connectedDebugAndroidTest` of `:video`,
  `:video-media3`, `:video-txplayer` and `:video-compose`, on API 24 (the minSdk), 26 and 34.
  The older emulator images may have a WebView older than the message listener (WebView 83) and nothing updates it,
  so the two tests that need the page's own messages skip there; the job prints the WebView's version first.
- **Release check (R8)**: the artifacts published to mavenLocal, then `extras/release-check` built from them,
  minified.

`.github/workflows/publish_sdk.yml` publishes a release tag (see [RELEASING.md](RELEASING.md)).
`.github/workflows/release-consumer.yml` builds the release check from Maven Central once that release is there:
after **Publish SDK** succeeds (it waits for the release to reach Maven Central, up to 90 minutes), and by hand
(Actions → Release consumer → Run workflow, with the version).

## What a publisher needs

From the build files and the artifacts' dependencies:

- **minSdk 24**, Android 7 (every module).
- **compileSdk 34**: AndroidX Core 1.13.1, Activity 1.8.2 and WebKit 1.12.1 require it in their AAR metadata;
  AGP refuses a lower one. `so.kontext:video-media3` depends on Media3 1.1.1 or newer (`media3-common`, the
  floor it compiles against); the app's own Media3 sets what it needs beyond that (Media3 1.6.0 requires
  compileSdk 35).
- **Android Gradle Plugin 8.1.1+** for compileSdk 34 (8.6+ for 35), with the Gradle version that AGP requires,
  and **JDK 17** to build (what AGP 8 runs on). The artifacts are **Java 11** bytecode (`jvmTarget` 11, the
  toolchain is 17), so a Java 11 app reads them.
- **Kotlin 2.0+** for a Kotlin app: the artifacts depend on `kotlin-stdlib` 2.1.0 (KontextKit requires it;
  coroutines 1.9 and serialization 1.7 require 2.0), and a Kotlin 1.9 compiler refuses its metadata. The
  artifacts are compiled with Kotlin 2.1.0, whose metadata Kotlin 2.0 reads. A Java app needs no Kotlin plugin.
- R8 needs nothing from the app: the consumer rules ship in the AAR.

What CI builds a publisher's app with (`extras/release-check`): AGP 8.7.3, Gradle 8.11.1, Kotlin 2.1.0, JDK 17,
compileSdk 35. The older minimums above follow from the metadata and are not built by any job.

## Running the example app

The example talks to the production ad server with the publisher token you pass it (none is committed): four
videos, one per format, each with its section.

```bash
./gradlew :example:installDebug -PkontextPublisherToken=<your token>
adb shell am start -n so.kontext.video.example/.MainActivity                    # the home screen
adb shell am start -n so.kontext.video.example/.MainActivity --es video demo-overlay   # one video directly
adb logcat -s KontextVideo
```

An emulator needs enough memory for ExoPlayer and a WebView at once: give the AVD 4 GB or more (`hw.ramSize` in
its `config.ini`). With too little, the system swaps and reports the app as not responding.
