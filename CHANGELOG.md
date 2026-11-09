# Changelog

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
