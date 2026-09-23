# Changelog

## Unreleased

* First real SDK, the Kotlin twin of sdk-swift-video: `KontextVideo.createSession`, `VideoSession.createAds(videoId, …)` (independent handles, so the next episode's ads load while the current one plays), `VideoAds.load(formats)` over `POST /instream/preload`, one placement object per format — `pause` (banner over the paused player), `overlay` (windowed or manual banners while the episode plays, driven by `playheadMs`), `interstitial` (full-screen video in the SDK's own Activity with a server-driven Close countdown and a MAX-style end card) — each with `isReady` / `show` / `hide` / `load` / `pending`, `ecpm` on every ad, MRC viewability, tracking pings, Custom Tabs click-through, `/init`, and a sealed `VideoAdEvent` stream.
* Short-drama example app (home grid → fullscreen vertical episode feed) against the mock server.
* The Samsung TV+ demo that used to live in `example/` moved to [samsung-android-video-demo](https://github.com/kontextso/samsung-android-video-demo).
