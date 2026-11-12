# TTSDK adapter (`so.kontext:video-ttsdk`)

Follows a TTSDK `TTVideoEngine` (BytePlus / Volcengine VOD player, 1.40 or later), so an app on TTSDK tracks its
player in one line instead of implementing `ContentPlayer`.

```kotlin
implementation("so.kontext:video:0.2.0")
implementation("so.kontext:video-ttsdk:0.2.0")
```

```kotlin
ads.track(engine)                              // the page's engine, once it has its source
ads.track(engine) { currentSpeed }             // when the app plays at another speed than 1×
```

- **TTSDK is not included.** The adapter compiles against TTSDK 1.40 and uses the app's own, licensed TTSDK.
- **The app's callback stays.** The adapter listens with `addVideoEngineCallback`, beside the app's own
  `setVideoEngineCallback`.
- **Playing** is TTSDK's playback state: paused or stopped by the app or viewer is not playing; buffering is.
- **Which video:** the engine's vid (`getVideoID()`). A new vid on the same engine, or the end of the video, is
  another play: an overlay or pause ad of the episode before then throws `Invalidated(ContentChanged)` at `show`.
  An engine playing a plain URL without a vid is still followed; only that check is skipped.
- **Feeds:** each page usually has its own engine, or takes a pre-rendered one. Track the page's engine with that
  page's `VideoAds` when the page becomes current.
- **Speed:** TTSDK has no getter for it. Pass the speed when the app changes it; without it the SDK accepts up
  to about 4×.
- **Seeks** are not reported by TTSDK: the SDK infers them from the position.
