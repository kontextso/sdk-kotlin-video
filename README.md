# Kontext Kotlin Video SDK

A Kotlin SDK for integrating Kontext's AI-powered ads into Android video experiences.

> **Status: scaffolding.** The `:video` module is an intentionally near-empty
> placeholder — it does nothing yet. Functionality will be added incrementally.
> The repo currently exists to establish the module layout, build config, and a
> runnable example app.

This repo mirrors the **Samsung TV+** playback stack (Kotlin + Jetpack Compose + Material3, Media3/ExoPlayer, HLS + DASH) — see the internal Samsung TV+ teardown — so the SDK drops naturally into a Samsung-TV+-shaped host.

## Modules

- **`:video`** — the publishable SDK library (package `so.kontext.video`, min SDK 30, JVM 17, Compose enabled). Currently just the `KontextVideo` namespace placeholder. No third-party runtime dependencies.
- **`:example`** — a Compose + Material3 demo app mirroring the Samsung TV+ home layout (APK 1.0.28.6 decompile): a top bar, a docked 16:9 player, category chips, and a scrollable film list. It builds one fresh `ExoPlayer` + `PlayerView` per film (keyed by id, released on switch). Beyond plain playback it exercises quality/bitrate selection, audio & subtitle tracks, buffering profiles + disk caching, a buffering spinner, fullscreen, **Picture-in-Picture**, per-stream **DRM** (a Widevine sample via the `VideoDrm` model), and **Casting** to a Chromecast / Cast-built-in TV — a real `MediaRouteButton` plus a `CastPlayer` that takes over playback (handing off the current film + position on connect, resuming locally on disconnect). `SAMPLE_FILMS` spans HLS, DASH, progressive MP4 and WebM test streams (license-clean CC films + public test streams).

The player stack pins Media3 to Samsung TV+'s version:
**Media3 1.6.0** (exoplayer + **HLS** + **DASH** + ui + **cast**), Compose 1.7.x /
Material3 1.3.1, activity 1.8.2, lifecycle 2.8.4, minSdk 30 / targetSdk 35.
Casting registers Google's default media receiver via a `CastOptionsProvider`;
`MainActivity` is a `FragmentActivity` so the Cast device-picker dialog can show.
DRM-over-Cast needs a custom receiver, so the default receiver casts the plain
HLS/DASH/MP4 films but not the Widevine sample.

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
