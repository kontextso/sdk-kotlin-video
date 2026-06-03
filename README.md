# Kontext Kotlin Video SDK

A Kotlin SDK for integrating Kontext's AI-powered ads into Android video experiences.

> **Status: scaffolding.** The `:video` module is an intentionally near-empty
> placeholder — it does nothing yet. Functionality will be added incrementally.
> The repo currently exists to establish the module layout, build config, and a
> runnable example app.

This repo mirrors the **Samsung TV+** playback stack (Kotlin + Jetpack Compose + Material3, Media3/ExoPlayer, HLS-first with DASH for DRM) — see the internal Samsung TV+ teardown — so the SDK drops naturally into a Samsung-TV+-shaped host.

## Modules

- **`:video`** — the publishable SDK library (package `so.kontext.video`, min SDK 24, JVM 17, Compose enabled). Currently just the `KontextVideo` namespace placeholder.
- **`:example`** — a minimal Compose + Material3 demo app that loops an HLS sample (Apple's advanced fMP4 bipbop stream) via [Media3 ExoPlayer](https://developer.android.com/media/media3), rendering `PlayerView` through `AndroidView` interop. Media3 HLS + DASH source sets are included to match Samsung TV+'s streaming formats.

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

## KontextKit dependency

The SDK depends on [`so.kontext.kit:kontext-kit-android`](https://github.com/kontextso/kontextkit-android)
for shared device-info / privacy / UI / OMID primitives. When a sibling
`../kontextkit-android` checkout exists, `settings.gradle.kts` substitutes the
published artifact with the local source via a Gradle composite build — set
`useLocalKontextkit=false` in `local.properties` to force resolution from Maven
Central instead.

This SDK is modelled on the sibling [`sdk-kotlin`](https://github.com/kontextso/sdk-kotlin) repo.
