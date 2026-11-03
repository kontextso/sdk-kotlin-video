# AGENTS.md

Shared guidance for coding agents (Codex, Claude Code, others) in this repository. One set of rules: `CLAUDE.md`
only points here. Details live in [DEVELOPMENT.md](DEVELOPMENT.md), the release in [RELEASING.md](RELEASING.md).

## Project

Kontext Kotlin Video SDK: overlay, pause and linear ads in short-drama and other video-first Android apps. Kotlin
2.1 (built for apps on Kotlin 1.9+ and Java 11+), min SDK 26 (Android 8), published to Maven Central as `so.kontext:video` (and `so.kontext:video-media3` and `so.kontext:video-ttsdk`, the player
adapters, and `so.kontext:video-compose`, the Jetpack Compose functions). It is the Android twin of [sdk-swift-video](https://github.com/kontextso/sdk-swift-video): the same
public API shape, the same wire contract, the same frame protocol and the same behavior, in Android and Kotlin
idioms. The contract is the Notion document *Video Ads API & SDK v2*; the behavior is the Notion page *SDK
Lifecycle*. Timed mode (windows, `nextRequestAt`) is specified there but deliberately not in the SDK.

## Contract, and which source wins

1. The Notion pages (internal; the repo is public, so referenced by title): *Video Ads API & SDK v2* for the wire,
   *SDK Lifecycle* for the public behavior, *Kontext Frame Protocol (v2)* for the page bridge, *Tracking Events
   (beacons)* for the beacons.
2. sdk-swift-video implements them first; this SDK follows it. A behavior that differs between the two is a bug in
   one of them unless the platform forces it (say so in a comment).
3. When the code and a page disagree, the page wins. A public API change goes to *SDK Lifecycle* first, then
   sdk-swift-video, then here.

## Commands

```bash
./gradlew assembleDebug                         # the SDK, the Media3 adapter and the example
./gradlew :video:testDebugUnitTest              # unit tests: JUnit 5 and Robolectric, about a minute
./gradlew spotlessApply detekt apiCheck         # format, static analysis, the public API against */api/*.api
./gradlew :video:lintRelease :video-media3:lintRelease :video-ttsdk:lintRelease :video-compose:lintRelease   # Android Lint over the libraries, as CI
./gradlew apiDump                               # after a deliberate public API change; review the .api diff
./gradlew :example:installDebug                 # the example on a running emulator or device
adb shell am start -n so.kontext.video.example/.MainActivity --es video demo-overlay
adb logcat -s KontextVideo
```

## Architecture

- `KontextVideo` (an `object`): `initialize(context, configuration)` (`/config`), `user`, `regulatory`, `variantId`;
  the models nested in it, as in Swift: `KontextVideo.Configuration`, `.Content`, `.User`, `.Regulatory`
  (`VideoAdException.NoFillReason` in the exception).
- `VideoAds`: one per video. `format()` asks the server for all formats with the `VideoAds`'s `sessionId` and the
  ids of the unshown bids it holds (`held`); one request at a time, shared by every caller; the server owns the bid
  state and the SDK follows the response.
- `VideoAd`: the facts (`price`, `durationMillis`), `load()` (shared), `reject()`, `show(container, insets)` for
  overlay and pause, `show(activity)` for linear, `dismiss()`; the page's events mapped to beacons and the listener;
  `end()` is the only terminal path.
- `internal/`, mirroring the Swift SDK's folders:
  - `Sdk.kt`: what `KontextVideo` holds (configuration, `/config` state, the ad on screen).
  - `ad/`: `VideoAd`'s logic outside its class: the page's events, and the overlay-follows-content rules.
  - `rendering/`: `FrameBridge` (the WebView and the frame protocol; the page's messages come through
    `addWebMessageListener` as `KontextAndroid`, for the page's own origin and main frame only), `FrameEvent`,
    `Tracker` (which beacons fire, sent by `BeaconClient`), `ViewabilityMeter`, `ActiveTimeClock`, `AdHostView`, `AdPlacement`, `LinearAdActivity`.
  - `playback/`: `ContentPlayback` (the tracked player), `ContentProgress` (an overlay's video time).
  - `payloads/`: `AdPayload`, `AdsRequest`, `AdsResponse`, `ConfigRequest`, `ConfigResponse`, `ErrorResponse`.
  - `network/`: `HttpTransport` (connections, redirects, at most 10), `ApiClient` (our server), `BeaconClient`
    (tracking GETs), `ApiException`, the JSON helpers.
  - `device/`: `DeviceInfo`, `Consent` (through KontextKit).
  - `support/`: `Environment` (the seams the tests replace), `ActivityTracker`, `Logger`,
    `ErrorReporter`, `DebugReporter`.
- One main type per file. The root package holds the public API only.

## Rules

- Commit directly to `main`; no feature branches. Push and watch CI.
- The lifecycle guarantee is sacred: after a successful `show`, exactly one `onAdEnded`, whoever ended it; nothing is
  delivered to the listener before `show`.
- The page never fires a beacon; `Tracker` does, each entry once, except `click`, `pause`, `resume`, `mute` and
  `unmute`. The SDK never requests an ad by itself.
- Every public call is main-thread (`@MainThread`); listener calls arrive on the main thread.
- Public API: `explicitApi()`, no `data class` (binary compatibility), models are classes with a builder (Kotlin
  `KontextVideo.Content(id, language) { … }`, Java `KontextVideo.Content.Builder`), milliseconds as `Long` with a `…Millis` name, `suspend`
  for one-result calls with a callback overload for Java, sealed classes for closed vocabularies with an `Other`
  escape where the server may send its own value. `apiCheck` must pass; a deliberate change runs `apiDump`.
- Never name other companies' products in code, comments, tests or docs.
- Comments say *why*, in prose; KDoc on every public declaration.
- Facts about the device and consent come from KontextKit; what the SDK needs and KontextKit lacks goes into
  KontextKit, not into a workaround here.
- Tests: deterministic, no wall-clock bounds a slow CI runner breaks; the clock moves through `Environment.now`.
  A new behavior comes with its test, races and reentrancy included.

## Related repos

- [sdk-swift-video](https://github.com/kontextso/sdk-swift-video): the iOS twin, implemented first
- [kontextkit-android](https://github.com/kontextso/kontextkit-android): the shared Android primitives
- [sdk-kotlin](https://github.com/kontextso/sdk-kotlin): the chat SDK
