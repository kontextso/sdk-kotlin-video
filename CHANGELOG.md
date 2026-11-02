# Changelog

## 0.1.0

In progress.

The first release: an SDK on the v2 contract, the Android twin of sdk-swift-video 0.2.0.

* `KontextVideo.initialize(context, KontextVideo.Configuration(token))` once at launch; `user`, `regulatory`, `variantId`.
* `VideoAds(content, labels)` per episode; `format(AdFormat)` returns a `VideoAd` (suspend, or a callback for Java);
  `track(player)` follows the content player (`VideoAdsPlayer`, or a Media3 `Player` with `so.kontext:video-media3`);
  `destroy()`.
* `VideoAd`: `price` and `durationMillis` before anything renders, `load()`, `reject(reason)`, `show(container, insets)`
  for overlay and pause, `show(activity)` for linear, `dismiss()`; `VideoAdListener` with `onAdImpression`,
  `onAdClicked`, `onAdFailed`, `onAdEnded` (exactly once per show).
* An overlay lives only while the tracked content plays; its duration counts milliseconds of video. `VideoAdsPlayer.Listener` tells the SDK each play-state change, seek and item change as it happens, and `playbackSpeed` tells fast playback from a seek; the Media3 adapter forwards all of them.
* A pause ad lives only while the tracked content is paused: the content playing again from anywhere (the media
  notification, a headset, picture-in-picture) after a pause seen while the ad is on screen ends it as
  `INTERRUPTED`.
* An episode's ads show only in that episode: a player belongs to one `VideoAds` at a time, and `track` on another
  one lets go of it in the first (an overlay of it on screen ends `INTERRUPTED`), whose overlay and pause ads then
  throw `Invalidated(ContentChanged)` at `show`, with no beacon. So do they once the player plays another item
  than at `track`: a Media3 player's current media item (a repeat's next play included), or the optional
  `VideoAdsPlayer.currentItem`. Linear ads are not checked.
* Viewability (MRC), beacons with the IAB and Kontext macros, error and debug reports when the server asks.
* KontextKit for Android 0.1.0: the SDK ships no Open Measurement binary.
* Example app: four videos with labels (inline pause, full-screen pause, overlay, linear) against the ad server.
