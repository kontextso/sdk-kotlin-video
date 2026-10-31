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

// The episode opens
val ads = VideoAds(Content("episode-6", "en") { title = "Episode 6"; durationMillis = 92_000 })
ads.track(player) // your Media3 player, or your own VideoAdsPlayer

// Prepare a pause ad early: format() gets the offer (its price, before anything renders),
// load() renders the creative so show() is instant
lifecycleScope.launch {
    pauseAd = try { ads.format(AdFormat.PAUSE).apply { load() } } catch (e: VideoAdException) { null }
    pauseAd?.listener = object : VideoAdListener {
        override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
            if (reason == VideoAd.EndReason.RESUMED) player.play()
        }
    }
}

// The viewer pauses, and resumes
player.pause(); pauseAd?.show(playerContainer)
pauseAd?.dismiss(); player.play()

// The viewer leaves the episode
ads.destroy()
```

Call `destroy()` when the episode's screen goes: in `onDestroy()` of an Activity, `onDestroyView()` of a Fragment,
or a `DisposableEffect`'s `onDispose` in Compose. Until then the `VideoAds` holds its player adapter and its ads.

A linear ad shows with `ad.show(activity)`; resume your content in `onAdEnded`. The [example app](example) shows
every format. Development: [DEVELOPMENT.md](DEVELOPMENT.md).
