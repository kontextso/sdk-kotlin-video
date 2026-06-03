# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kontext Kotlin Video SDK — an Android SDK for integrating AI-powered ads into video experiences. To be published to Maven Central as `so.kontext:video`.

**Current status:** scaffolding only. The `:video` module is a near-empty placeholder (`KontextVideo` namespace object + `SDKInfo`). Real functionality will be added incrementally. Modelled on the sibling `sdk-kotlin` repo.

## Modules

- **`:video`** — the publishable SDK library (package `so.kontext.video`, min SDK 24, JVM 17, `explicitApi()`).
- **`:example`** — demo Android app: one `MainActivity` playing a sample video via Media3 ExoPlayer (`PlayerView`).

## Common Commands

```bash
# Build
./gradlew :video:assembleDebug
./gradlew :example:assembleDebug

# Test
./gradlew :video:testDebug

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

## KontextKit dependency

`so.kontext.kit:kontext-kit-android` ([github.com/kontextso/kontextkit-android](https://github.com/kontextso/kontextkit-android)) provides shared device-info / privacy / UI / OMID primitives. `settings.gradle.kts` substitutes the published artifact with a local `../kontextkit-android` checkout via composite build when present; `useLocalKontextkit=false` forces Maven Central resolution.
