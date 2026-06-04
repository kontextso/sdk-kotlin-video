# Kontext Video — Example app

A minimal Android video app whose UI + player stack mirror the **actual Samsung
TV+ app** (`com.samsung.android.tvplus`, APK 1.0.28.6), so the Kontext Video SDK
(`:video`) can be developed against a realistic Samsung-TV+-shaped host.

## Screen

A single Compose `HomeScreen` (Material3, dark theme) that mirrors the Samsung
TV+ home layout:

1. **Top bar** — Samsung TV+ logo + "Samsung TV Plus" + cast / search / overflow icons.
2. **Docked player** — a 16:9 `PlayerView` under the bar that auto-plays on open.
3. **Category chips** — a horizontally-scrollable row (Featured / Favorites / Recent / …).
4. **Film list** — a vertically-scrollable list; tapping a row plays that film in the docked player.

## Player

Deliberately simple and robust: **one fresh `ExoPlayer` + `PlayerView` per film**,
keyed by film id (`DockedPlayer` in `HomeScreen.kt`). Tapping a film fully releases
the previous player and view and creates a new one, so nothing from the old film
can bleed through behind the new one. A plain `ExoPlayer` uses Media3's default
HLS pipeline (`media3-exoplayer-hls`). The `PlayerView` uses a `TextureView`
surface (`view_player.xml`) so it's clipped to its layout bounds, with
`resize_mode=fit` (whole frame, never cropped).

`Channel` / `VideoDrm` (`Channel.kt`) carry a per-stream URL and an optional DRM
config (Widevine / PlayReady via `MediaItem.DrmConfiguration`); `SAMPLE_FILMS`
maps each list row to its own HLS stream. Concise playback logs are tagged
`VideoExample` (`adb logcat -s VideoExample`).

Content is license-clean: CC films (Big Buck Bunny, Tears of Steel) + standard
public HLS test streams; thumbnails are CC0 placeholder photos (picsum) standing
in for poster art.

## Tech stack

| Area | Library | Version | Same as Samsung TV+? |
|---|---|---|---|
| Language / UI | Kotlin + Jetpack Compose | Compose **1.7.7** | ✅ exact |
| | Material3 | **1.3.1** | ✅ exact |
| | activity-compose | **1.8.2** | ✅ exact |
| | lifecycle-runtime-ktx | **2.8.4** | ✅ exact |
| | core-ktx | 1.13.1 | ✗ Samsung ships a `2.0.x-sesl8` fork (not on public Maven) |
| Player | Media3 ExoPlayer (exoplayer · exoplayer-hls · ui) | **1.6.0** | ✅ exact (HLS-only, no DASH) |
| Images | Coil | 2.7.0 | ~ (Samsung uses Glide; Coil is the Compose-idiomatic equivalent) |
| Coroutines | kotlinx-coroutines | **1.8.1** | ✅ exact |

### Deliberate divergences from the real app

- **Samsung's SESL forks** — Samsung ships private One-UI forks of AndroidX
  (`*-sesl8`) and a Samsung Compose fork (`com.samsung.sesl.compose`), not on
  public Maven; this app uses **standard AndroidX**.
- **Build toolchain** — kept stable (AGP 8.6.1, Kotlin 2.0.21, Gradle 8.9,
  compileSdk 35) rather than chasing Samsung's AGP 8.12.0 / compileSdk 36.
- **Trimmed to essentials** — Samsung uses a MediaSession foreground service,
  OkHttp data source, Cast, Hilt/Dagger, Navigation Compose, Paging, WorkManager,
  Glide. Those are host-app concerns; the example keeps just what's needed for a
  clean docked player. (The cast icon in the top bar is a non-functional visual.)
- **Ads / measurement** — Samsung's video ads are **SSAI** (server-stitched) with
  GMA-embedded OMID. There's no client video-ad SDK to mirror; that's where the
  Kontext Video SDK will eventually plug in.

`minSdk 30` / `targetSdk 35` match Samsung TV+.

## Run

```bash
./gradlew :example:installDebug
adb shell am start -n so.kontext.video.example/.MainActivity
```

Needs network to stream the HLS samples and load thumbnails.
