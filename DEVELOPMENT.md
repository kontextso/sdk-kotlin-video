# Development

This guide is for contributors working on the Kontext Kotlin Video SDK locally.

## Prerequisites

- **JDK 17** (Temurin recommended) — `brew install --cask temurin@17`
- **Android SDK** with platform 35 installed (Android Studio, or `sdkmanager "platforms;android-35"`)
- **An Android emulator or physical device** running API 24+ for the example app

Gradle 8.9 is downloaded by the wrapper on first build.

## Repository layout

```
sdk-kotlin-video/
├── video/src/main/kotlin/so/kontext/video/   # SDK source: entry points at the root, then
│   ├── ads/                                  #   AdPlacement base + one folder per format
│   ├── model/  network/  tracking/  internal/
├── video/src/test/                           # JUnit 5 unit tests
├── example/                                  # Compose short-drama demo app
├── CLAUDE.md                                 # Claude Code project context
└── RELEASING.md                              # how to cut a release
```

## Building and testing

```bash
./gradlew :video:assembleDebug        # build the SDK library
./gradlew :example:assembleDebug      # build the demo app
./gradlew :video:testDebug            # unit tests (JUnit 5)
./gradlew spotlessApply detekt        # format + static analysis (CI runs spotlessCheck)
./gradlew spotlessCheck detekt assembleDebug :video:testDebug   # the full CI sequence
```

## Mock ad server

The real `/instream/preload` endpoint does not exist yet. Development runs
against [sdk-video-mock-server](https://github.com/kontextso/sdk-video-mock-server):

```bash
git clone git@github.com:kontextso/sdk-video-mock-server.git
cd sdk-video-mock-server && pnpm i && pnpm dev   # http://localhost:5175, also on the LAN
```

The example app is pointed at `http://10.0.2.2:5175` (`example/.../Ads.kt`),
which is the host machine as seen from the emulator; for a physical device use
the host's LAN IP. Edit the fixtures in the mock repo's `mocks/` to change what
the app renders.

## Running the example app

```bash
./gradlew :example:installDebug
adb shell am start -n so.kontext.video.example/.MainActivity
```

For CI/screenshot automation, `--ez auto_open_story true` jumps straight into
the player; add `--ez auto_pause_loop true` to pause/resume every 3 s so the
pause → ad → tracking pings → resume → reload cycle shows up in logcat (each
`shown` carries a different `adId`; the mock server terminal shows the
`/impression/{bid}/*` pings). The interstitial shows on its own 3 s into the
first playback of an episode.

```bash
adb logcat -s VideoExample KontextVideo      # example events + SDK log
```
