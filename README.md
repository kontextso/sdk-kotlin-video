# Kontext Kotlin Video SDK

A Kotlin SDK for integrating Kontext's AI-powered ads into Android video experiences.

> **Status: scaffolding.** The `:video` module is an intentionally near-empty
> placeholder — it does nothing yet. Functionality will be added incrementally.
> The repo currently exists to establish the module layout, build config, and a
> runnable example app.

This repo mirrors the **Samsung TV+** playback stack (Kotlin + Jetpack Compose + Material3, Media3/ExoPlayer, HLS-first with DASH for DRM) — see the internal Samsung TV+ teardown — so the SDK drops naturally into a Samsung-TV+-shaped host.

## Modules

- **`:video`** — the publishable SDK library (package `so.kontext.video`, min SDK 30, JVM 17, Compose enabled). Currently just the `KontextVideo` namespace placeholder. No third-party runtime dependencies.
- **`:example`** — a Compose + Material3 demo app whose player mirrors the **actual Samsung TV+ app** (APK 1.0.28.6 decompile): a Media3 `MediaSessionService` (`PlaybackService`) owns the `ExoPlayer`, the UI drives it through a `MediaController`, playback runs through an **OkHttp data source**, and per-stream **DRM** (Widevine/PlayReady) is wired via a `VideoDrm` model. It loops an HLS sample (Apple's advanced fMP4 bipbop).

The player stack is pinned to what Samsung TV+ actually ships:
**Media3 1.6.0** (exoplayer + **HLS** + ui + **session** + **datasource-okhttp** +
**cast**; no DASH — Samsung is HLS-only), Compose 1.7.x / Material3 1.3.1,
activity 1.8.2, lifecycle 2.8.4, minSdk 30 / targetSdk 35. Cast is wired via
`androidx.media3.cast.DefaultCastOptionsProvider` (dormant until used).

Build toolchain (AGP 8.6.1, Kotlin 2.0.21, Gradle 8.9, compileSdk 35) is kept
stable rather than chasing Samsung's AGP 8.12.0 / compileSdk 36. Samsung's
SESL AndroidX/Compose forks aren't on public Maven, so standard AndroidX is used.

## Building

```bash
./gradlew :video:assembleDebug      # build the SDK library
./gradlew :example:assembleDebug    # build the demo app
./gradlew spotlessCheck detekt      # code quality
```

## Running the example

```bash
./gradlew :example:installDebug
adb shell am start -n so.kontext.video.example/.MainActivity
```

The app needs network access to stream the sample clip. The application id is
`so.kontext.video.example` — note it's `video`, not `ads`.

This SDK is modelled on the sibling [`sdk-kotlin`](https://github.com/kontextso/sdk-kotlin) repo, but — unlike `sdk-kotlin` — it deliberately does **not** depend on `kontext-kit-android`, keeping the dependency surface minimal and matching the lean `tvplus-test-player` stack.
