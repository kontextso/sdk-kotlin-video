# Player adapters

The SDK follows the content player through one small interface, `ContentPlayer`: where the content is, whether it
plays, its speed, which video it is, and (optionally) a listener for pause, resume, seek and the end. An adapter
implements it for one player, so an app writes `ads.track(player)` and nothing else.

| Folder | Published as | Player | Call |
|---|---|---|---|
| [`media3/`](media3) | `so.kontext:video-media3` | Media3 / ExoPlayer (any `Player`, 1.1+) | `ads.track(exoPlayer)` |
| [`ttsdk/`](ttsdk) | `so.kontext:video-ttsdk` | TTSDK `TTVideoEngine` (1.40+) | `ads.track(engine)` |

Each adapter is optional and ships separately, so an app gets only the player it uses, and never a player it does
not: an adapter compiles against the oldest player version it supports and leaves the app's own version in charge.

Another player: implement `ContentPlayer` in the app (about 30 lines; see its KDoc), or add an adapter here as
`adapters/<player>/`, published as `so.kontext:video-<player>`, registered in `settings.gradle.kts`.
