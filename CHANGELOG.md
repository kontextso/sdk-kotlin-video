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
* `VideoAdException.Request` says why the request failed: its `failure`, a `VideoAdException.RequestFailure`, is
  `Offline` (no connection, the connection lost, the host unreachable; ask again once online), `TimedOut` (ask again
  later), `Server(status)` (an error status; ask again later, quote `lastRequestId` if it repeats) or `BadResponse`
  (an answer the SDK could not use; quote `lastRequestId`). The underlying exception stays in the SDK's log.
* Viewability (MRC), beacons with the IAB and Kontext macros, error and debug reports when the server asks.
* A linear ad under another Activity (translucent, or a sheet such as a store's in-app overlay) reads as exposure 0 on
  every tick: its page pauses, no `viewable` fires, and the dwell starts over once the cover goes. A dialog over it
  does not cover it; a full cover stops its screen, as before.
* `ad.exposure` sends `exposedPercentage` 0–100, the unit of MRAID's `exposureChange`.
* No viewability verdict for an ad without an impression (an untapped video, a video failing before its first frame).
* Transparency multiplies along the ad's ancestors, for showing and for viewability: three layers at alpha 0.1 are
  transparent.
* A click whose `url` is missing or unusable (a space, a web link without a host) opens its valid `fallbackUrl`, as on
  iOS.
* Every accepted click fires `click` entries: an `endCard` click on an ad without end-card entries fires the main
  creative's.
* An overlay or pause ad's time on screen (`overlayViewDuration`, an untracked overlay's duration) counts only while
  some of it is seen, as the viewability meter measures; one nobody can see for a second while the app is in front
  (a feed cell swiped away) ends `INTERRUPTED` and frees the app's one slot.
* `isReady` is `false` while the SDK is switched off, for every `VideoAds`.
* `load()` throws `CreativeFailed` whenever the creative failed, also when it failed right after it reported ready;
  on an ad already shown it returns at once.
* The first `track()` while an overlay is on screen leaves it there; only a change from one tracked player to another
  ends it.
* A page that ends itself completed (`ad.close` with `reason: complete`) sets `isCompleted`.
* The ads request lists its `held` ads after its waits (`/config`, the user agent, the advertising id), so an ad
  shown or rejected meanwhile is not listed.
* The creative's 10 s load timeout counts the time that passed while the app was in front, not the ticks.
* A pause ad on screen ends `INTERRUPTED` when another `VideoAds` takes its player (the next page of a feed that shares one player) or the content changes; it no longer holds the app's slot over content it is not for.
* KontextKit for Android 0.2.0: the SDK ships no Open Measurement binary, and the kit no longer brings `androidx.preference`, `appcompat` or `recyclerview` into the app.
* Example app: four videos with labels (inline pause, full-screen pause, overlay, linear) against the ad server.
