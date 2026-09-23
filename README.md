# Kontext Kotlin Video SDK

Android SDK for integrating Kontext video ads into short-drama and other video-first apps. The Kotlin twin of [sdk-swift-video](https://github.com/kontextso/sdk-swift-video): same formats, same API shape, same wire contract.

> **Status: early development.** All three formats render natively; the server endpoint is being designed with our design partners against a [mock server](https://github.com/kontextso/sdk-video-mock-server).

## Supported formats

| Format | Behavior |
|---|---|
| **Pause** (`PAUSE`) | Image banner at the top (or bottom) of the paused player; the content stays visible |
| **Interstitial** (`INTERSTITIAL`) | Full-screen video the SDK presents in its own Activity at a moment the app chooses; closable after the server's `skip_after_ms`, and after the video ends the last frame stays up until the viewer taps Close |
| **Overlay** (`OVERLAY`) | Image banners while the episode plays — several per video, each in a window the server derived from the scene; the SDK tells you when a window opens, you decide to show |

All are prepared at video start; the app shows a pause ad when the user pauses and hides it on resume, shows overlays in their windows, and presents the interstitial at a moment it picks. Each ad is shown once. Every ad carries its `ecpm` (USD per 1,000 impressions) before it is shown, so an app that runs its own auction can rank a Kontext ad against its other demand.

## Usage

The SDK renders classic Views into a `ViewGroup` the app hands it and never assumes a particular player: pass the view that fills the video area (a `FrameLayout` around your `PlayerView`, `TextureView` or a third-party player's view), not the Activity's root, so the banner's margins land on the video. Every public method is called on the main thread.

```kotlin
import so.kontext.video.KontextVideo
import so.kontext.video.VideoSessionOptions
import so.kontext.video.model.VideoAdEvent
import so.kontext.video.model.VideoAdFormat
import so.kontext.video.model.VideoSeries

// 1. Initialize the SDK once per viewer.
val session = KontextVideo.createSession(
    context = applicationContext,
    options = VideoSessionOptions(
        publisherToken = "pub-xxxx",
        userId = "user-123",
        onEvent = { event ->
            if (event is VideoAdEvent.Loaded) {
                for (r in event.data.results) for (ad in r.ads) Log.d("Ads", "ready ${r.format} ${ad.id} eCPM ${ad.ecpm}")
            }
        },
    ),
)

// 2. Create the ads handle for a video, then load ads for it. Do this as
//    early as you like — for the next episode while the current one plays —
//    so the ads are ready on arrival; handles are independent.
//    `videoId` is YOUR unique id for the video — any scheme (series id +
//    number, a UUID, …). Everything else is optional contextual signal: for
//    short drama the synopsis/keywords/rating usually live on the series.
val ads = session.createAds(
    videoId = "100001-6",
    durationMs = 92_000,
    language = "en",                                    // the track being played
    episodeNumber = 6,
    series = VideoSeries(
        id = "100001",
        title = "My Marionette Lover",
        description = "To win a bet with his friends, Ryan…",
        keywords = listOf("forced love", "fantasy"),
        contentRating = "18+",
        languages = listOf("en", "es"),
        episodeCount = 40,
    ),
)
ads.load(listOf(VideoAdFormat.PAUSE, VideoAdFormat.INTERSTITIAL, VideoAdFormat.OVERLAY))

// 3. You control the player, so you tell the SDK when to show and hide.
//    One placement object per format; each ad is shown once, then
//    `isReady` is false again. The ad shows immediately on pause.
fun didPause() {
    val ad = ads.pause.pending.firstOrNull() ?: return
    if (ad.ecpm >= myFloor) {
        ads.pause.show(playerContainer, position = BannerPosition.TOP, style = BannerStyle(marginTopDp = 24))
    }
}

fun didResume() {
    ads.pause.hide()
    ads.pause.load()                                    // prepare the next pause (server decides whether to fill)
}

// 4. Interstitial: a full-screen video the SDK presents itself, at a
//    moment you choose (3 s into the episode, a scene break, the end of
//    an episode). Pause your content first and resume it in `onDismissed`,
//    which runs once when the screen goes away: the viewer tapped Close
//    (after the server's `skip_after_ms`, or after the video ended — the
//    last frame stays up like a MAX end card), media failed, or your `hide()`.
fun didReachAdMoment() {
    if (ads.interstitial.isReady) {
        player.pause()
        ads.interstitial.show(activity) { player.play() }
    }
}

// 5. Overlay, windowed mode (we know your content): feed the playhead; the
//    SDK calls back when a scene window opens or closes. Showing is your call.
ads.overlay.onWindowOpen = { ads.overlay.show(playerContainer, style = BannerStyle(marginBottomDp = 96)) }
ads.overlay.onWindowClose = { ads.overlay.hide() }
// from your player's progress callback, a few times per second:
ads.overlay.playheadMs = player.currentPosition

//    Overlay, manual mode (we never saw your content, so there are no
//    windows — the ads come with `trigger.kind == "manual"`): decide the
//    moment yourself, exactly like the pause ad.
if (ads.overlay.isReady) ads.overlay.show(playerContainer)
ads.overlay.hide()

// 6. Done with the video: release its ads. Nothing releases a handle for
//    you, so destroy the previous episode's when you move on.
ads.destroy()
```

All lifecycle events and their payloads are documented in [EVENTS.md](EVENTS.md).

## Installation

```kotlin
dependencies {
    implementation("so.kontext:video:0.1.0")
}
```

The SDK declares `INTERNET` and its interstitial Activity in its own manifest; nothing to add to yours. Min SDK 24.

## Example app

`example/` is a minimal short-drama app that links the SDK:

- **Home screen** — a grid of story cards.
- **Player** — tapping a story opens a fullscreen vertical episode feed; swipe up/down to move between episodes, tap to play/pause. The interstitial shows on its own 3 s into the first playback of an episode, and the next episode's ads are prepared while the current one plays.

Every episode plays the same bundled portrait (9:16) sample clip (`example/src/main/res/raw/sample_episode.mp4`, free stock footage from [Mixkit](https://mixkit.co)) — no network needed for the content. Ads come from the mock server on the host.

```bash
./gradlew :example:installDebug
adb shell am start -n so.kontext.video.example/.MainActivity
# jump straight into the player, and pause/resume every 3 s:
adb shell am start -n so.kontext.video.example/.MainActivity --ez auto_open_story true --ez auto_pause_loop true
adb logcat -s VideoExample KontextVideo
```

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for building and testing the SDK, and [RELEASING.md](RELEASING.md) for cutting a release.

## License

Apache 2.0 — see [LICENSE](LICENSE).
