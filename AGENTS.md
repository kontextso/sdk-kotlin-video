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
- Format differences live in two places: `ContentRule` (how an ad follows the content: `OverlayRule`,
  `PauseRule`, `NoContentRule`) and `AdScreen` (where it is shown: `InlineScreen`, the host view, the clock, the
  app's insets, the chosen size and the holding Activity; `LinearScreen`, its own Activity, the Activity that
  started it and its closing). `VideoAd` asks them rather than branching on the format.
- Functional core, imperative shell: the rules with no Android in them (`SdkLifecycle`, `SdkState`, `AdLifecycle`,
  `AdState`, `FrameProtocol`, `closeOutcome`) decide, and `Sdk`, `VideoAd` and `FrameBridge` act
  on the answer; the rules are tested as plain JUnit tables. Swift has `SdkLifecycle`, `SdkState`, `AdLifecycle`,
  `AdState` and `FrameProtocol` under the same names.
- `internal/`, mirroring the Swift SDK's folders:
  - `state/`, the state of the SDK and of each ad, and their legal moves; no Android, no side effects.
    `SdkLifecycle.kt`: the SDK's legal moves between the phases of `KontextVideo.State` (`canMove`), stateless.
    `SdkState.kt`: every fact of the SDK's life, the one source of truth: its phase (moved only by `moveTo`, which
    asks `SdkLifecycle.canMove`), the configuration, what `/config` said of the reports (`isErrorReportingOn`,
    `null` until it answers; `isDebugReportingOn`), and `isInitialized` and `disabledReason` derived from the
    phase; `Sdk` holds one, `KontextVideo.state` and the reporters read it. `AdLifecycle.kt`: the rules of an ad's
    life, stateless: its phases (offered, invalidated, reserved, on screen, ending, ended) and `canMove`, their
    legal moves. `AdState.kt`: every fact of an ad's life, the one source of truth: its phase (moved only by
    `moveTo`, which asks `AdLifecycle.canMove`), its milestones (`isLoaded`, `wasOnScreen`, `hasImpression`,
    `isCompleted`, the failure), each recorded once and never undone, and the flags derived from them; `VideoAd`
    holds one and its public flags read from it.
  - `core/Sdk.kt`: the SDK's shell, what `KontextVideo` forwards to: it moves `SdkState` (`initialize`, `/config`
    and its retry, the kill switch) and holds the app's inputs (`user`, `regulatory`, `variantId`).
  - `frame/`, the ad's web page and its messages: `FrameMessage` (one page message read: the
    envelope, the 1 MB limit, the fields, the playhead's bounds; `parse` is `null` for a broken envelope),
    `FrameProtocol` (the frame protocol's rules, no WebView: the sender, the frame's phase (`FramePhase`, not the
    ad's), the video's order, the touch a click needs; `accept` returns a `Step`), `FrameNavigation` (where the
    page may navigate, and when leaving is a click), `TouchGate` (one touch opens one URL), `Origin`,
    `FrameBridge` (its shell: the WebView and its context, which does each `Step`; the page's messages come
    through `addWebMessageListener` as `KontextAndroid`, for the page's own origin and main frame only),
    `FrameWebView` (the WebView locked down), `FrameWebViewClient` (what the WebView reports of the page's loads),
    `FrameEvent` (what the page told the bridge; what the ad does with each is `VideoAd`'s, `handle`).
  - `screen/`, putting the ad on screen: `AdScreen` (where an ad is shown: `InlineScreen`, which also places the page in its host, and `LinearScreen`, each in its own file),
    `ScreenSlot` (the app's one ad on screen, taken at `show`, freed before `onAdEnded`; the ad keeps its
    `AdScreen`, which holds the meter), `InlineAdView`, `AdGeometry` (the placement
    arithmetic), `LinearAdActivity` (with `Callbacks`, what a linear ad gives its screen), `Views` (what the SDK asks of a view: its Activity, its system insets, its first layout).
  - `measurement/`, what the viewer saw (visibility, time on screen) and the beacons that report it: `Tracker`
    (which beacons fire, sent by `BeaconClient`) with `TrackingMacros`, `ViewabilityMeter` with `ViewExposure`
    and `Polygon`, `OnScreenTimeClock`.
  - `content/`: `ContentPlayback` (the tracked player), `PlayerOwnership` (a player belongs to one `VideoAds` at a
    time), `ContentTimeClock` (an overlay's video time), `ContentRule` (how an ad follows the content, one class
    per format: `OverlayRule`, `PauseRule` in their own files, `NoContentRule`).
  - `payloads/`: `AdPayload`, `AppInputs` (the request rules of the app's Content and User), `AdsRequest`, `AdsResponse`, `ConfigRequest`, `ConfigResponse`, `ErrorResponse`, `SdkInfo`, `JsonParser` (the depth-guarded parse), `JsonFields` (the lenient readers).
  - `network/`: `HttpTransport` (connections, redirects, at most 10), `ApiClient` (our server), `BeaconClient`
    (tracking GETs), `ApiException`.
  - `device/`: `DeviceInfo` (the device facts), `UserAgent` (the shared lookup, its wait and its fallback), `Consent` (through KontextKit).
  - `diagnostics/`: `Logger`; `ErrorReporter` (the shell that sends to `/error`), `ErrorReport` (one report: its
    vocabulary and its JSON), `ReportQueue` (the bounded queue and its batches); `DebugReporter` (`/debug`),
    `EventHistory` (the last events an error report carries); `ReportFormat` (capped, timestamp, json).
  - `platform/`: `Environment` (the seams the tests replace), `AppActivities` (the app's screens: in front,
    started, resumed), `LinkOpener` (which links may open, and opening them).
- `integrations/compose` (`:video-compose`): `rememberVideoAds`, `VideoAdHost`, and `VideoAdHostController`, the
  engine behind `VideoAdHost` (turns "this ad should be shown here" into show and dismiss), on `video`'s public
  API only; its unit tests reach `video`'s internals as a friend module, to make loaded ads without a server.
- One main type per file. The root package holds the public API only.

## Rules

- Commit directly to `main`; no feature branches. Push and watch CI.
- One source of truth: every fact of the SDK's life is in `SdkState`, every fact of an ad's life in `AdState`;
  everything else asks them.
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
- A boolean that names a state (an adjective or participle) is an assertion with `is`, `has` or `was`: `isLoaded`,
  `hasImpression`, `wasOnScreen`; one named by a third-person verb stays (`countsTime`, `holdsAds`); a field that
  mirrors a wire format or a standard keeps its name (`gdpr`, `coppa`, `reportErrors`, JSON keys). Same names as Swift.
- A class reads top down: its constants and properties first, then its functions, then its companion object, whose
  own constants come first too. detekt's `ClassOrdering` checks it.
- Facts about the device and consent come from KontextKit; what the SDK needs and KontextKit lacks goes into
  KontextKit, not into a workaround here.
- Tests: deterministic, no wall-clock bounds a slow CI runner breaks; the clock moves through `Environment.now`.
  A new behavior comes with its test, races and reentrancy included.

## Related repos

- [sdk-swift-video](https://github.com/kontextso/sdk-swift-video): the iOS twin, implemented first
- [kontextkit-android](https://github.com/kontextso/kontextkit-android): the shared Android primitives
- [sdk-kotlin](https://github.com/kontextso/sdk-kotlin): the chat SDK
