# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kontext Kotlin Video SDK — an Android SDK for integrating AI-powered ads into video experiences. To be published to Maven Central as `so.kontext:video`.

**Current status:** scaffolding only. The `:video` module is a near-empty placeholder (`KontextVideo` namespace object + `SDKInfo`). Real functionality will be added incrementally. Modelled on the sibling `sdk-kotlin` repo.

## Modules

The stack mirrors the **Samsung TV+** app (Kotlin + Jetpack Compose + Material3, Media3/ExoPlayer, HLS-only) per the internal teardown, so the SDK fits a Samsung-TV+-shaped host.

- **`:video`** — the publishable SDK library (package `so.kontext.video`, min SDK 30, JVM 17, `explicitApi()`, Compose enabled for future ad-overlay surfaces). No third-party runtime dependencies (does **not** depend on `kontext-kit-android`).
- **`:example`** — Compose + Material3 demo app mirroring the Samsung TV+ home layout (APK 1.0.28.6 decompile). A single `HomeScreen`: top bar (logo + title + cast/search/overflow icons) → docked 16:9 player → scrollable category chips → scrollable film list. `DockedPlayer` creates **one fresh `ExoPlayer` + `PlayerView` per film** (keyed by id) and fully releases the previous one on switch, so no old frame bleeds through. Plain ExoPlayer + default Media3 pipeline; `PlayerView` uses a TextureView surface with `resize_mode=fit`. `Channel`/`VideoDrm` (`Channel.kt`) carry per-stream URLs + optional Widevine/PlayReady DRM, and an inferred `mimeType` (required by the Cast receiver); `SAMPLE_FILMS` maps each row to its own stream — HLS, DASH, progressive MP4 or WebM (license-clean CC films + public test streams). Thumbnails via Coil (CC0 placeholders). Logs tagged `VideoExample`. Application id `so.kontext.video.example`.

Runtime/player libs pinned to Samsung TV+'s versions: **Media3 1.6.0** (exoplayer + HLS + **DASH** + ui + **cast**), Compose 1.7.7 / Material3 1.3.1, activity 1.8.2, lifecycle 2.8.4, coroutines 1.8.1, minSdk 30 / targetSdk 35. Build toolchain kept stable (AGP 8.6.1, Kotlin 2.0.21, Gradle 8.9, compileSdk 35) rather than chasing Samsung's AGP 8.12.0 / compileSdk 36. Samsung's SESL AndroidX/Compose forks aren't public, so standard AndroidX is used. The example is trimmed to essentials — Samsung's MediaSession foreground service, OkHttp data source, Hilt, Navigation Compose, Paging, WorkManager, and Glide are host-app concerns left out. **Casting is implemented**, though: the top-bar cast icon is a real `MediaRouteButton`, with a `CastPlayer` that takes over playback on connect (`CastOptionsProvider` registers Google's default media receiver; `MainActivity` is a `FragmentActivity` so the device-picker dialog works). DRM-over-Cast needs a custom receiver, so the Widevine sample won't cast on the default receiver.

## Common Commands

```bash
# Build
./gradlew :video:assembleDebug
./gradlew :example:assembleDebug

# Test
./gradlew :video:testDebug

# Run the example app
./gradlew :example:installDebug
adb shell am start -n so.kontext.video.example/.MainActivity

# Code quality (both must pass in CI)
./gradlew spotlessCheck   # verify formatting
./gradlew spotlessApply   # auto-fix formatting
./gradlew detekt          # static analysis (config: extras/detekt.yml)

# Publish (once there's something to publish)
./gradlew :video:publish -PsdkVersion=X.Y.Z
```

## Key Patterns

- **`explicitApi()` mode** on `:video` — every public declaration must spell out its visibility.
- **`BuildConfig.SDK_VERSION`** is baked in at build time from `libs.versions.sdk-kotlin-video` (or `-PsdkVersion=X.Y.Z` at publish), so the SDK version can't drift from the published Maven version.
- **Spotless** enforces ktlint 0.50.0; **Detekt** config in `extras/detekt.yml`.
- Tests use **JUnit 5** + **mockk** + **kotlinx-coroutines-test**.
- **No `kontext-kit-android` dependency** — unlike the sibling `sdk-kotlin` repo. Samsung TV+ uses SSAI + GMA-embedded OMID (not IAB OM SDK), so kontextkit's OMID/device-info primitives aren't pulled in here; the dependency surface is kept minimal to match `tvplus-test-player`.
