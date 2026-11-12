# Changelog

## 0.2.0
The ad server's `/v2` contract as it ships. Breaking changes first.

### Breaking
* `KontextVideo.user` is required for ads, and `KontextVideo.User(id)` takes the viewer's id: a required, non-empty `String` (`User("u-42") { email = … }`; Java `new User.Builder("u-42")`). `email`, `emailHash` and `keywords` stay optional. Without a user, or with an id that is empty or longer than 256 characters, `format()` sends nothing and throws `Request(MissingUser)`, a new `VideoAdException.RequestFailure` case, even when a valid held ad exists; a debuggable build stops at the mistake.
* COPPA: a child's request (`regulatory.coppa = true`) now carries `user.id` (the server keeps it from buyers); it still leaves out `email`, `emailHash` and the advertising id.
* `VideoAds(content, labels)` is `VideoAds(content, section, variantId)`. `section`: where in the app the player is, sent as `section`; it chooses the formats (and their settings) the publisher configured for that section. Empty or longer than 128 characters is an integration mistake: a debuggable build stops at it, and the value is left out of the request. `labels` are gone from the API and the wire.
* `KontextVideo.variantId` is gone: the variant is `VideoAds`'s `variantId`, sent as before. Empty or longer than 128 characters is an integration mistake: a debuggable build stops at it, and the value is left out of the request.
* `KontextVideo.Content.categories` (and its builder setter) is gone, and `video.categories` is no longer sent.
* `VideoAd.Price.precision` and `VideoAd.Price.Precision` are gone; a `precision` in an answer is ignored.
* A fourth format, `AdFormat.PAUSE_FULLSCREEN` (wire `pauseFullscreen`): the pause ad that covers the whole player, shown with `show(container)` and ended as the full-screen pause ad was (Resume: `RESUMED`; the content playing again: `INTERRUPTED`; ✕: `CLOSED`). `AdFormat.PAUSE` is the card only: a `pause` ad with position `fullscreen` is dropped and reported (`pause:position`). Ask for the one you show: `format(AdFormat.PAUSE)` or `format(AdFormat.PAUSE_FULLSCREEN)`.
* `rememberVideoAds(content, labels, setUp)` (`video-compose`) is `rememberVideoAds(content, section, variantId, setUp)`.

### Changed
* The ads request no longer sends `held`; the SDK still keeps one unshown ad per format and returns a still-valid one when a request fails.
* An ads request waits for this `VideoAds`'s `notUsed` and `error` beacons still on the wire, so `reject()` followed at once by `format()` does not get the rejected bid back. Each request waits at most 2 s; a beacon still on the wire then is waited for again by the next request. The user, the consent and the playhead are read after that wait, just before the request is sent.
* `device.network` carries `type` (`wifi`, `cellular`, `ethernet`, `other`), `generation` (`2g` to `5g`), `carrier` and `mccmnc` (`MCC-MNC`, for example `230-02`) where the device says, through KontextKit; carrier and MCC-MNC on a cellular connection only. No new permission.
* The frame page's message envelope carries `bidId` (was `adId`), both ways; a page message is accepted only with this ad's `bidId`.
* New debug events: `ad.clicked` (info, `area`), `ad.notUsed` (info, `reason`: `notUsed`, `outbid`, `destroyed`), `ad.expired` (info, `format`), `ads.requestFailed` (warning, `failure`: `offline`, `timedOut`, `server`, `badResponse`, `missingUser`, and `httpStatus` for `server`), `config.failed` (warning, the same properties), `ad.error` (error, `code`). `ads.request` carries `section` when set and no longer `held`.
* `Content.mediaRating`: unset means unknown, not safe; the server then serves only the ads that accept content of unknown rating.
* `VideoAd.id` is documented as the server's bid id: the same id in its beacons, in error and debug reports, and in support requests.
* The example app takes its publisher token from the Gradle property `kontextPublisherToken` (none is committed), sets a guest `KontextVideo.user`, and shows one video per format, each with a `section`.

## 0.1.1
### Changed
* The session id is a UUID v7 (`UuidV7.generate()` of KontextKit 0.4.0), so sessions sort by when they began.
* Diagnostics use the ad server's names. A debug event, and an error report's history entry, carries `occurredAt` (was `createdAt`), `bidId` (was `adId`), `properties` (was `state`), and now `sessionId` and `level` too; the history is the last 30 events as they were sent. An error report's `underlying` is `cause`; `stage`, `recovery`, `batchId` and `dropped` are no longer sent. A debug batch carries `app` and `os`, as an error batch does.
* While `/config` asks for the debug log (`reportDebug`), every Logcat line of every level is sent as the event `log`, its text as `message` (URLs without their query), whatever `Configuration.logLevel` shows.
* `/config` sets how long reports and events are collected before a batch leaves: `errorBatchInterval` and `debugBatchInterval`, in milliseconds; 0 sends each as it happens; 100 ms when the server says nothing.

## 0.1.0

Initial release: overlay, pause and linear ads for Android 7.0 (API 24) and later, on the *Video Ads API & SDK v2*
contract, with the optional `video-media3`, `video-ttsdk`, `video-txplayer` and `video-compose` artifacts.
Integration guide: https://docs.kontext.so/video/sdk/kotlin.
