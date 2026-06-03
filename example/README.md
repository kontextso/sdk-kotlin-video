# Kontext Video — Example app

A minimal Android video player whose stack mirrors the **actual Samsung TV+
app** (`com.samsung.android.tvplus`, APK 1.0.28.6), so the Kontext Video SDK
(`:video`) can be developed against a realistic Samsung-TV+-shaped host.

## Flow

`MainActivity` (empty Home screen with an "Open video" link) → tap → `PlayerScreen`.
System Back returns to Home and releases the player.

## Player architecture (mirrors Samsung TV+)

```
PlayerScreen ──MediaController──▶ PlaybackService (MediaSessionService)
  (Compose UI,                      ├─ ExoPlayer (Media3 1.6.0)
   PlayerView via                   ├─ OkHttpDataSource.Factory  ← playback over OkHttp
   AndroidView)                     └─ MediaSession              ← system controls / background
Channel / VideoDrm ───────────────▶ MediaItem.DrmConfiguration  ← Widevine / PlayReady
```

- **`PlaybackService`** — a Media3 `MediaSessionService` owns the `ExoPlayer`; the
  UI drives it through a `MediaController`. Mirrors Samsung's
  `...library.player...PlayerMediaSession` + foreground media-playback service.
- **OkHttp data source** — playback runs through `media3-datasource-okhttp`
  (Samsung uses `media3.datasource.okhttp`), so headers / interceptors can later
  carry DRM license auth and SSAI tracking.
- **`Channel` / `VideoDrm`** — per-stream DRM model (licenseUrl / scheme /
  customHeader), mirroring Samsung's `VideoDrm`. Default sample is Apple's
  advanced fMP4 **HLS** stream (Samsung is HLS-only).
- **Cast** — wired via `androidx.media3.cast.DefaultCastOptionsProvider` (manifest
  meta-data), the same provider class Samsung uses; dormant until a Cast session.

## Tech stack

| Area | Library | Version | Same as Samsung TV+? |
|---|---|---|---|
| Language / UI | Kotlin + Jetpack Compose | Compose **1.7.7** | ✅ exact |
| | Material3 | **1.3.1** | ✅ exact |
| | activity-compose | **1.8.2** | ✅ exact |
| | lifecycle-runtime-ktx | **2.8.4** | ✅ exact |
| | core-ktx | 1.13.1 | ✗ Samsung ships a `2.0.x-sesl8` fork (not on public Maven) |
| Player | Media3 ExoPlayer | **1.6.0** | ✅ exact |
| | media3 modules | exoplayer · exoplayer-hls · ui · session · datasource-okhttp · cast | ✅ same set (no DASH — Samsung is HLS-only) |
| Networking | OkHttp | 4.12.0 | ~ (Samsung bundles OkHttp; exact version not recoverable from the APK) |
| Cast | play-services-cast-framework | 21.5.0 | ~ (present; exact version not recoverable) |
| | mediarouter | **1.6.0** | ✅ exact |
| Coroutines | kotlinx-coroutines | **1.8.1** | ✅ exact |

### Deliberate divergences from the real app

- **Samsung's SESL forks** — Samsung ships private One-UI forks of AndroidX
  (`*-sesl8`) and even a Samsung Compose fork (`com.samsung.sesl.compose`). These
  aren't on public Maven, so this app uses **standard AndroidX**.
- **Build toolchain** — kept stable (AGP 8.6.1, Kotlin 2.0.21, Gradle 8.9,
  compileSdk 35) rather than chasing Samsung's AGP 8.12.0 / compileSdk 36.
- **Host-app concerns not added** — Samsung uses Hilt/Dagger, Navigation Compose,
  Paging, WorkManager, DataStore, and Glide. Those are application-architecture
  choices; the SDK shouldn't impose them on integrators, so the example leaves
  them out and keeps a single-Activity Compose-state flow.
- **Ads / measurement** — Samsung's video ads are **SSAI** (server-stitched) with
  GMA-embedded OMID for display/native. There is no client video-ad SDK to mirror;
  that's where the Kontext Video SDK will eventually plug in.

`minSdk 30` / `targetSdk 35` match Samsung TV+.

## Run

```bash
./gradlew :example:installDebug
adb shell am start -n so.kontext.video.example/.MainActivity
```

Needs network to stream the HLS sample.
