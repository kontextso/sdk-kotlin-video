# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kontext Kotlin Video SDK — an Android SDK for integrating Kontext video ads into short-drama and other video-first apps. Published to Maven Central as `so.kontext:video`. It is the Kotlin twin of [sdk-swift-video](https://github.com/kontextso/sdk-swift-video): same three formats, same API shape (verbs and names match — `createSession`, `createAds`, `load`, `isReady`, `show`, `hide`, `destroy`), same wire contract, same event vocabulary. When a decision is made in one SDK, mirror it in the other; sdk-swift-video's CLAUDE.md carries the full decision log (dates, partners, alternatives), this file records only what differs on Android.

**Status: pause, overlay and interstitial ads are implemented.** The SDK speaks the JSON `POST /instream/preload` contract the real ad server does not implement yet — development runs against the Vite mock in [sdk-video-mock-server](https://github.com/kontextso/sdk-video-mock-server) (`pnpm dev` → `http://localhost:5175`; the example reaches it at `http://10.0.2.2:5175` from the emulator). The contract is documented in that repo's README.

First partner: **NetShort** (classic-View Android app on the ByteDance/Volcengine player, MAX + AdMob mediation with an in-app auction) — hence View-based rendering into a `ViewGroup` the app hands us, `ecpm` on every ad, and the interstitial on the MAX model.

The Samsung TV+-style demo that used to live in `example/` moved to [samsung-android-video-demo](https://github.com/kontextso/samsung-android-video-demo) on 2026-09-23.

## Modules

- **`:video`** — the publishable SDK library (package `so.kontext.video`, min SDK 24, compileSdk 35, JVM 17, `explicitApi()`). Runtime deps: kotlinx coroutines + serialization, `androidx.activity` (the interstitial's `ComponentActivity`), `androidx.browser` (Custom Tabs click-through), `androidx.annotation`. **No Compose, no Media3, no `kontext-kit-android`** in the SDK: it renders classic Views, plays the interstitial with the platform `MediaPlayer`, and keeps the install id / click-through in a few lines of its own.
- **`:example`** — Compose short-drama demo app (home grid → fullscreen vertical episode feed with `VerticalPager`; ExoPlayer/Media3 as the *content* player inside a `FrameLayout` container the SDK mounts into). Bundled sample clip in `res/raw`. Application id `so.kontext.video.example`; logs under `VideoExample`.

## Common Commands

```bash
./gradlew :video:assembleDebug        # build the SDK library
./gradlew :example:assembleDebug      # build the demo app
./gradlew :video:testDebug            # unit tests (JUnit 5 + mockk)
./gradlew spotlessApply detekt        # format + static analysis (CI runs spotlessCheck detekt assembleDebug :video:testDebug)

# Run the example (mock server must be up on the host)
./gradlew :example:installDebug
adb shell am start -n so.kontext.video.example/.MainActivity --ez auto_open_story true --ez auto_pause_loop true
adb logcat -s VideoExample KontextVideo
```

## Architecture

### Public API (`video/src/main/kotlin/so/kontext/video/`)

**`KontextVideo.createSession(context, VideoSessionOptions)`** → **`VideoSession`** (one per app user; fires `/init` in the background; `isEnabled`). **`session.createAds(videoId, title?, description?, durationMs?, language?, episodeNumber?, series?)`** → **`VideoAds`**: creates a handle and nothing else — any number alive at once (NetShort prepares episode N+1 while N plays); the app `destroy()`s each when done. `ads.load(formats, playerSize?)` fires one `/instream/preload` for exactly the listed formats, appends to the placements' `pending`, emits one `Loaded`. `ads.destroy()` is a plain `fun` (not suspend): it schedules the 1 s teardown itself.

**Placements** — `ads.pause: PauseAd`, `ads.overlay: OverlayAd`, `ads.interstitial: InterstitialAd`, all subclasses of `ads/AdPlacement` (queue, optional reveal delay, tracking, viewability, teardown). `mount(view, container)` returns the root to remove later: banners mount into a full-size transparent `FrameLayout` layer added to the app's container (so any `ViewGroup` whose children may overlap works), the interstitial's view is its own root. The measured view (`view`) and the removable root are tracked separately.

| Format | Android specifics |
|---|---|
| `PAUSE` | `show(container: ViewGroup, position = TOP, style = BannerStyle())`. `BannerStyle` is a data class with dp margins (`copy` to tweak). |
| `OVERLAY` | `playheadMs: Long` (milliseconds — Android's player APIs are ms, unlike the Swift SDK's seconds), `onWindowOpen` / `onWindowClose` lambdas, `show(container, position?, style)` / `show(ad, container, …)`. Same open-window → manual → next-pending preference as Swift. |
| `INTERSTITIAL` | `show(activity: Activity, style = Style(), onDismissed: () -> Unit)` starts the SDK's own **`InterstitialAdActivity`** (declared in the SDK manifest: portrait, `configChanges` so rotation never recreates it, fullscreen black theme, back disabled via `OnBackPressedCallback`). The placement registers itself under a token in a process-wide map; the Activity looks it up in `onCreate`, hands its content `FrameLayout` to `attach()`, and reports `onScreenGone` from `onDestroy` so a system-killed screen still runs `onDismissed`. Video via `InterstitialAdView`: `TextureView` + platform `MediaPlayer`, aspect-fit, first-frame watchdog (`MEDIA_INFO_VIDEO_RENDERING_START`, 8 s), stall watchdog (`MEDIA_INFO_BUFFERING_START/END`, 8 s), quartiles from a 250 ms tick, Close chip counting down `skip_after_ms`. **MAX end-card behaviour:** completion fires `Completed` + `video.complete` but leaves the last frame up with Close enabled; only the tap (or the app's `hide()`) ends it, and `onDismissed` runs exactly once for Close / failure / `hide()`, not for `destroy()`. In IAB CTV terms this is a *Linear* ad (pre/mid/post-roll); the name follows MAX / AdMob / LevelPlay because that is what our partners know. |

**Events** — `model/VideoAdEvent` is a sealed class (`Loaded`, `Shown`, `Clicked`, `Closed`, `Completed`, `Error`) delivered on the main thread via `VideoSessionOptions.onEvent`, with a `name` string like sdk-kotlin's `AdEvent`. Payloads documented in `EVENTS.md` — keep it in sync.

### Internal structure

- `network/` — `HttpClient` (`HttpURLConnection` on `Dispatchers.IO`, no OkHttp/Ktor), `AdsService` (request DTO + **lossy** response decode: `ads` is walked element by element so one malformed ad never drops the fill; `ecpm` is required, so an ad without it is skipped), `InitService`.
- `model/` — the public wire models are `@Serializable` with `@SerialName` snake_case keys (no separate DTO layer, unlike sdk-kotlin); `VideoAdFormat` enum entries carry `@SerialName` wire values (`INSTREAM_IMAGE_PAUSE`, `INSTREAM_VIDEO_INTERSTITIAL`, `INSTREAM_IMAGE_OVERLAY`).
- `tracking/` — `AdTracker` (POST pings, `[CACHEBUSTING]`/`[TIMESTAMP]` macros, once per URL), `ViewabilityMeter` (MRC ≥ 50 % for 1 continuous s, polled every 100 ms via `getGlobalVisibleRect`).
- `ads/` — `AdPlacement`, `BannerAdView` + `mountBanner`, `BannerStyle`/`BannerPosition`, `AdImageLoader` (HttpURLConnection + BitmapFactory), `ClickThrough` (Custom Tabs, `ACTION_VIEW` fallback); `pause/`, `overlay/`, `interstitial/`.
- `internal/` — `log()` (tag `KontextVideo`), `InstallId` (SharedPreferences UUID).

### Conventions

- Every public method is main-thread; placements use a `Dispatchers.Main.immediate` scope. Anything crossing to I/O uses `withContext(Dispatchers.IO)`.
- `explicitApi()` is on: every public declaration spells out its visibility. Internal hooks between `AdPlacement` and its subclasses are `internal`.
- Times in the public API are **milliseconds** with an `Ms` suffix (`durationMs`, `playheadMs`, `fromMs`, `skipAfterMs`) — the Android convention (ExoPlayer, MediaPlayer). The Swift SDK uses seconds; the wire is ms in both.
- Kotlin nests block comments: never write `/*` inside a KDoc (a `` `/instream/*` `` literal broke the build once).
- Spotless enforces ktlint 0.50.0; Detekt config in `extras/detekt.yml` (long parameter lists are allowed for wire models and view constructors).
- Tests are JUnit 5 + mockk with `isReturnDefaultValues`; anything touching Views/MediaPlayer is verified in the example on the emulator instead.

## Release Process

See `RELEASING.md`: branch `release/X.Y.Z`, update `CHANGELOG.md` + `gradle/libs.versions.toml` (`sdk-kotlin-video`), PR to `main`, annotated tag `X.Y.Z` (no `v`) → `publish_sdk.yml` publishes via vanniktech maven-publish.

## Related repos

- [sdk-swift-video](https://github.com/kontextso/sdk-swift-video) — the iOS twin; decisions are logged there
- [sdk-video-mock-server](https://github.com/kontextso/sdk-video-mock-server) — the mock ad server and the wire contract
- [sdk-kotlin](https://github.com/kontextso/sdk-kotlin) — chat-apps SDK this repo mirrors conventions from
- [samsung-android-video-demo](https://github.com/kontextso/samsung-android-video-demo) — the former example app
- [ads monorepo](https://github.com/kontextso/ads) — the ad server this SDK talks to
