# Media3 adapter (`so.kontext:video-media3`)

Optional add-on for apps that play content with Media3 (ExoPlayer). It adds one call, `ads.track(player)`, so the
SDK can follow the content player (playhead, play and pause, the current item) without the app writing its own
`ContentPlayer`. Apps on TTSDK use `adapters/ttsdk`; apps with another player implement `ContentPlayer` themselves (see `adapters/README.md`).

```kotlin
implementation("so.kontext:video:0.1.0")
implementation("so.kontext:video-media3:0.1.0")

ads.track(player)   // after player.setMediaItem(...) for each episode
```

It compiles against Media3 1.1 and uses only its `Player` interface, so it works with any Media3 1.1 or newer
and never changes the app's Media3 version.
