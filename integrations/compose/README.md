# Compose (`so.kontext:video-compose`)

Jetpack Compose functions for the SDK. Two calls replace the host view, the effects and the listener wiring a
Compose screen would otherwise write itself.

```kotlin
implementation("so.kontext:video:0.1.0")
implementation("so.kontext:video-compose:0.1.0")
```

```kotlin
@Composable
fun EpisodeScreen(content: KontextVideo.Content, player: ExoPlayer) {
    // One per episode: created for it, tracked, destroyed when the screen or the episode goes
    val ads = rememberVideoAds(content) { it.track(player) }
    var adOnScreen by remember { mutableStateOf<VideoAd?>(null) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxSize())
        // Shows the ad while it is set; null (or another ad) dismisses it
        VideoAdHost(
            ad = adOnScreen,
            modifier = Modifier.fillMaxSize(),
            onEnded = { _, reason ->
                adOnScreen = null
                if (reason == VideoAd.EndReason.RESUMED) player.play()
            },
        )
    }

    // The viewer pauses:
    scope.launch { adOnScreen = try { ads.format(AdFormat.PAUSE).apply { load() } } catch (e: VideoAdException) { null } }
}
```

- `VideoAdHost` shows an overlay or pause ad over the content where it is placed, and a linear ad full screen
  from the Activity hosting the composition. It owns the ad's listener: hear it through `onImpression`,
  `onClicked` and `onEnded`. `onShowFailed` means it could not be shown (`AnotherAdShowing`, `ContentPaused`, …).
- `rememberVideoAds` destroys the `VideoAds` when the composition leaves or `content.id` changes; destroying ends a
  linear ad too, so move to the next episode after a post-roll's `onEnded`. For ads that must survive a rotation,
  keep the `VideoAds` in a `ViewModel` and destroy it in `onCleared()`.

Compiled against Compose 1.6.8: the app's own, newer Compose is used.
