# Tencent Cloud Player adapter (`so.kontext:video-txplayer`)

Follows a Tencent Cloud Player SDK (LiteAV) `TXVodPlayer`, 11.0 or later, so an app on it tracks its player in one
line instead of implementing `ContentPlayer`.

```kotlin
implementation("so.kontext:video:0.2.0")
implementation("so.kontext:video-txplayer:0.2.0")
```

```kotlin
ads.track(player)                                   // the page's player, once it has its source
ads.track(player, currentItem = { episode.id })     // one player for several episodes
ads.track(player, playbackSpeed = { currentRate })  // when the app plays at another speed than 1× (setRate)
```

A `TXVodPlayer` has one listener slot, and it is the app's (`setVodListener`). The SDK does not take it: hand the
play events on from the app's own listener, one line.

```kotlin
player.setVodListener(object : ITXVodPlayListener {
    override fun onPlayEvent(player: TXVodPlayer, event: Int, param: Bundle?) {
        KontextTXVodPlayer.onPlayEvent(player, event) // every event, as given
        // ... the app's own handling
    }

    override fun onNetStatus(player: TXVodPlayer, status: Bundle?) {}
})
```

- **The Player SDK is not included.** The adapter compiles against `com.tencent.liteav:LiteAVSDK_Player` 11.0 and
  uses the app's own, licensed SDK (any edition with `com.tencent.rtmp.TXVodPlayer`).
- **R8:** the adapter needs no rules. The app keeps the Player SDK's own (`-keep class com.tencent.** { *; }`, from
  its integration guide): minified without it, the Player SDK aborts when the first player is created.
- **Playing** is the player's `isPlaying()`: paused, ended or stopped is not playing; a stall to load is. A pause
  has no event: the SDK reads `isPlaying()` four times a second while an ad is on screen, and at `show`.
- **The events** tell the SDK at once of the end (`PLAY_END`), a loop's next round (`LOOP_ONCE_COMPLETE`) and a
  seek (`SEEK_COMPLETE`). Without them the SDK still follows the player from its position and `isPlaying()`.
- **Which video:** the player is the episode, unless the app gives `currentItem` (when it reuses one player for
  several episodes). Playing it to its end, or a loop's next round, is another play: an overlay or pause ad of the
  episode before then throws `Invalidated(ContentChanged)` at `show`.
- **Feeds:** each page usually has its own player. Track the page's player with that page's `VideoAds` when the page
  becomes current.
- **Speed:** the Player SDK has no getter for it. Pass the speed when the app changes it; without it the SDK accepts
  up to about 4×.

## Tests

- Unit tests (`src/test`): the adapter's rules over a player the test drives.
- Emulator tests (`src/androidTest`, in CI): a real `TXVodPlayer` (LiteAV 13.3) tracked by a `VideoAds`. The Player
  SDK plays nothing without a license (event -5, `VOD_PLAY_ERR_INVALID_LICENCE`), so CI checks the wiring with the
  real classes, and the playback tests (playing, pause, seek, end, loop) skip. With a license they run:
  ```bash
  ./gradlew :video-txplayer:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.txLicenceUrl=<licence url> \
      -Pandroid.testInstrumentationRunnerArguments.txLicenceKey=<licence key>
  ```
