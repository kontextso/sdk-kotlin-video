# Events

Every ad lifecycle event is delivered through the `onEvent` callback you pass
in `VideoSessionOptions`, always on the **main thread**:

```kotlin
val session = KontextVideo.createSession(
    context = applicationContext,
    options = VideoSessionOptions(
        publisherToken = "pub-…",
        userId = "user-123",
        onEvent = { event ->
            when (event) {
                is VideoAdEvent.Loaded -> for (r in event.data.results) {
                    for (ad in r.ads) Log.d("Ads", "loaded ${r.format} ${ad.id} eCPM ${ad.ecpm}")
                    r.noFillReason?.let { Log.d("Ads", "no fill ${r.format} $it") }
                }
                is VideoAdEvent.Shown -> analytics.log("ad_shown", event.ad.adId)
                is VideoAdEvent.Clicked -> analytics.log("ad_clicked", event.ad.adId)
                is VideoAdEvent.Closed -> Unit
                is VideoAdEvent.Completed -> Unit
                is VideoAdEvent.Error -> Log.e("Ads", event.error.message)
            }
        },
    ),
)
```

Events are informational — the SDK never requires you to react to one
(show/hide/load are separate, explicit calls). Identifiers:

- `videoId` — the id you passed to `createAds(videoId = …)`.
- `adId` — unique per **served ad**. Two `Shown` events with different
  `adId`s are two different ads; the same creative can appear behind many
  served ads.

Every event also has a stable `name` string (`ad.loaded`, `ad.shown`, …) for logs.

## The events

### `Loaded(LoadedData)`

A `load(formats)` finished: the server answered, and this is what is ready.
**Exactly one per `load` call**, with one result row per requested format.

| Field | Type | Meaning |
|---|---|---|
| `videoId` | `String` | Your video id |
| `results` | `List<FormatResult>` | One row per requested format |

`FormatResult`:

| Field | Type | Meaning |
|---|---|---|
| `format` | `VideoAdFormat` | `PAUSE`, `INTERSTITIAL`, or `OVERLAY` |
| `ads` | `List<Ad>` | The served ads, in order — each carries its `id` (matching the `adId` on later `Shown`/`Clicked` events) and its `ecpm` (USD per 1,000 impressions). Empty = no fill |
| `noFillReason` | `String?` | Why the server served less than it could have (`no_ad_match`, `frequency_capped`, `content_unsafe`, …) |

A non-empty `ads` with a `noFillReason` means the server could have served
more (e.g. an overlay window stayed empty).

`ecpm` is what the ad pays, in USD per 1,000 impressions. It is known before
the ad is shown — here, on `VideoAd.ecpm` in the placement's `pending` list,
and on the ad handed to `overlay.onWindowOpen` — so an app that runs its own
auction can rank a Kontext ad against its other demand and skip `show` when
it loses. An ad that is never shown is never billed. Not emitted when the
request itself failed — that's `Error`.

### `Shown(AdData)`

The ad is on screen — emitted when the view is mounted (for the
interstitial, when its Activity is up and playback is about to start). The
SDK separately fires the `impression` + `creative_view` tracking when the
creative has actually rendered (image painted / first video frame), so
`Shown` may precede the tracked impression by the image-load time.

### `Clicked(AdData)`

The user tapped the ad. The SDK opens the click-through
(`/impression/{bid}/redirect`, which records the click server-side) in
Chrome Custom Tabs — you don't need to do anything.

### `Closed(AdData)`

The user cut the ad short **from inside it**: the interstitial's Close chip,
enabled once the server's `skip_after_ms` has elapsed, tapped before the
video ended. The ad is already hidden, its Activity finished, and
`skip`/`close` tracking has fired; `onDismissed` runs too. Your own `hide()`
calls do **not** emit `Closed` — you initiated those.

### `Completed(AdData)`

A video ad (interstitial) played to the end. `video.complete` tracking
fired. The screen is **still up** on the last frame with Close enabled, like
a MAX end card; `onDismissed` runs when the viewer taps it (no `Closed`
then — the ad was not cut short).

### `Error(ErrorData)`

Something failed: the `/instream/preload` request (network, non-200,
undecodable body) or unplayable ad media. No `Loaded` accompanies a failed
request — `Loaded` means "the server answered".

| Field | Type | Meaning |
|---|---|---|
| `videoId` | `String?` | The video the failure belongs to, when known |
| `message` | `String` | Diagnostic description |

For an interstitial whose media fails after `show`, the SDK finishes its
Activity and calls `onDismissed` so your flow continues.

## Per-format cheat sheet

| | `PAUSE` | `INTERSTITIAL` | `OVERLAY` |
|---|---|---|---|
| `Loaded` result row | 0–1 ad | 0–1 ad | 0–N ads |
| `Shown` | on `show(container)` | on `show(activity)` | on `show(container)` (windowed or manual) |
| `Clicked` | tap | tap on video | tap |
| `Closed` | – | Close chip before the video ended | – |
| `Completed` | – | video end | – |
