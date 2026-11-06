# Kontext Kotlin Video SDK

An Android SDK for Kontext video ads (overlay, pause and linear) in short-drama and other video-first apps. The
Android twin of the [Swift Video SDK](https://github.com/kontextso/sdk-swift-video).

## Install

```kotlin
dependencies {
    implementation("so.kontext:video:0.1.0")
    implementation("so.kontext:video-media3:0.1.0")  // optional: ads.track(exoPlayer)
    implementation("so.kontext:video-ttsdk:0.1.0")   // optional: ads.track(ttVideoEngine), TTSDK 1.40+
    implementation("so.kontext:video-compose:0.1.0") // optional: rememberVideoAds and VideoAdHost for Jetpack Compose
}
```

## Use

```kotlin
// Application.onCreate, once
KontextVideo.initialize(this, KontextVideo.Configuration("your-publisher-token"))

// The episode's ads live in a ViewModel, so a rotation keeps them
class EpisodeViewModel : ViewModel() {
    val ads = VideoAds(KontextVideo.Content("episode-6", "en") { title = "Episode 6"; durationMillis = 92_000 })
    var pauseAd: VideoAd? = null

    override fun onCleared() = ads.destroy() // the viewer left the episode
}

// The episode's screen, in onCreate: the player and the screen are new after a recreation, so track the
// new player and set the listeners again; a listener left from the old screen would keep it alive
val ads = viewModel.ads
ads.track(player) // your Media3 player, or your own ContentPlayer

// Prepare a pause ad early: format() gets the offer (its price, before anything renders),
// load() renders the creative so show() is instant
lifecycleScope.launch {
    val pauseAd = try { ads.format(AdFormat.PAUSE).apply { load() } } catch (e: VideoAdException) { null }
    pauseAd?.listener = object : VideoAdListener {
        override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
            if (reason == VideoAd.EndReason.RESUMED) player.play()
        }
    }
    viewModel.pauseAd = pauseAd
}

// The viewer pauses, and resumes. show throws for routine outcomes (the ad expired, another ad is
// showing, the container is too small): catch VideoAdException, or the app crashes
player.pause()
try { viewModel.pauseAd?.show(playerContainer) } catch (e: VideoAdException) { /* not shown */ }
viewModel.pauseAd?.dismiss(); player.play() // dismiss and reject never throw
```

`show` (and `format` and `load`) throw `VideoAdException`; Kotlin does not make you catch it, so wrap every `show`
in a `try`. Nothing is shown and no listener call follows when it throws. `reject` and `dismiss` never throw on the
main thread. Every call is main-thread (`load`, `format` and `awaitInitialization` may be called from any
dispatcher); in a debuggable build a call from another thread stops the app, in release it does nothing.

Destroy the `VideoAds` with whatever owns it. Owned by a `ViewModel`, as above: `destroy()` in `onCleared()`, and in
every new Activity `track` its player and set the ads' listeners again, so the ads survive a rotation. Owned by the
Activity (or a Fragment, or a Compose `DisposableEffect`): `destroy()` in its `onDestroy()` (`onDispose`), every
time; declare `android:configChanges` for rotation, as the example app does, so a rotation does not recreate it. A
`VideoAds` its owner forgot is never released: it holds its player adapter, its ads and their listeners.

A linear ad shows with `ad.show(activity)`; resume your content in `onAdEnded`. The [example app](example) shows
every format. Development: [DEVELOPMENT.md](DEVELOPMENT.md).
