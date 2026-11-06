# Example app

A small Android app that shows the SDK end to end with a real ExoPlayer and the Media3 adapter, written with
classic Views. It is built with the SDK from this repo (not from Maven Central) and is never published.

## What it shows

The home screen lists five videos, one per situation. Each plays a bundled sample episode on loop:

| Video | Format | What happens |
|---|---|---|
| Revenge of the Heiress | overlay (bottom) | An overlay after 3 s of playing; pausing ends it |
| Midnight Vows | overlay (top) | An overlay at the top after 3 s of playing (labels `layout: top`); pausing ends it |
| The CEO's Secret | linear | A full-screen linear ad after 3 s of playing |
| The Heir's Return | pause (full screen) | Tap to pause: a full-screen pause ad shows; its Resume button plays on |
| Moonlit Contract | pause (card) | Tap the player to pause: a pause ad shows; tap again to resume |

A status line under the title shows the ad's format and price once it is loaded, or why there is none. An ad at
the top stays below the back button (76 dp for an overlay strip, 68 dp for a pause card), one at the bottom 20 dp
above the title and the status line.

## The code

| File | What it does |
|---|---|
| `ExampleApp.kt` | `KontextVideo.initialize` once, at launch, with debug logging |
| `MainActivity.kt` | The list of videos |
| `PlayerActivity.kt` | One episode: the player, the ad area over it, `VideoAds` per episode, `track(player)`, `format` → `load` → `show`, the listener, `destroy()` in `onDestroy` |
| `Video.kt` | The five demo videos: id, title, format, labels; `find`, the video a deep link names |

`PlayerActivity` is the one to read: it is the integration a publisher writes, in about 200 lines.

## Run it

```bash
./gradlew :example:installDebug
adb shell am start -n so.kontext.video.example/.MainActivity                          # the home screen
adb shell am start -n so.kontext.video.example/.MainActivity --es video demo-overlay  # one video directly
adb shell am start -n so.kontext.video.example/.MainActivity --es video pause         # a format: its demo-<format> video
adb logcat -s KontextVideo                                                            # the SDK's log
```

It talks to the production ad server, whose demo routes answer any token (`example-debug`); put your own
publisher token in `ExampleApp.kt` to see your own ads. An emulator needs 4 GB of RAM or more for ExoPlayer and a
WebView at once.

For Jetpack Compose, see `integrations/compose`; for a TTSDK player, `adapters/ttsdk`.
