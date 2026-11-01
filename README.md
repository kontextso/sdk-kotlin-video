# Kontext Kotlin Video SDK

An Android SDK for Kontext video ads (overlay, pause and linear) in short-drama and other video-first apps. The
Android twin of the [Swift Video SDK](https://github.com/kontextso/sdk-swift-video).

## Install

```kotlin
dependencies {
    implementation("so.kontext:video:0.1.0")
    implementation("so.kontext:video-media3:0.1.0") // optional: ads.track(exoPlayer)
}
```

## Use

```kotlin
// Application.onCreate, once
KontextVideo.initialize(this, Configuration("your-publisher-token"))

// The episode's ads live in a ViewModel, so a rotation keeps them
class EpisodeViewModel : ViewModel() {
    val ads = VideoAds(Content("episode-6", "en") { title = "Episode 6"; durationMillis = 92_000 })
    var pauseAd: VideoAd? = null

    override fun onCleared() = ads.destroy() // the viewer left the episode
}

// The episode's screen, in onCreate: the player is new after a rotation, so track it again
val ads = viewModel.ads
ads.track(player) // your Media3 player, or your own VideoAdsPlayer

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
viewModel.pauseAd?.dismiss(); player.play() // dismiss throws only for a linear ad
```

`show`, `reject` and `dismiss` throw `VideoAdException`; Kotlin does not make you catch it, so wrap every `show`
and `reject` in a `try`. Nothing is shown and no listener call follows when they throw.

Call `destroy()` when the viewer leaves the episode, not whenever its screen is destroyed: a rotation or another
configuration change destroys and recreates the Activity, and `destroy()` there drops the held ads and ends the one
on screen. Keep the `VideoAds` in a `ViewModel` and destroy it in `onCleared()`, as above. Without one, call it in
`onDestroy()` only when the screen really goes: `if (isFinishing) ads.destroy()` in an Activity,
`if (requireActivity().isFinishing || isRemoving) ads.destroy()` in a Fragment's `onDestroy()`. In Compose, take
the `VideoAds` from a `ViewModel` (`viewModel()`), or create and destroy it in a `DisposableEffect` of a screen
that survives rotation. Until `destroy()` the `VideoAds` holds its player adapter and its ads.

A linear ad shows with `ad.show(activity)`; resume your content in `onAdEnded`. The [example app](example) shows
every format. Development: [DEVELOPMENT.md](DEVELOPMENT.md).
