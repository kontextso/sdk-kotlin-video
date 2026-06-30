package so.kontext.video.example

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes

/**
 * Per-stream DRM config. Mirrors Samsung TV+'s
 * `com.samsung.android.tvplus.library.player...VideoDrm`
 * (licenseUrl / scheme / customHeaderName / customHeaderValue), as also
 * replicated in the tvplus-test-player reference app.
 */
data class VideoDrm(
    val licenseUrl: String,
    val scheme: DrmScheme = DrmScheme.WIDEVINE,
    val customHeaderName: String? = null,
    val customHeaderValue: String? = null,
)

/** Samsung TV+ ships dual DRM; Media3 supports both via its core. */
enum class DrmScheme { WIDEVINE, PLAYREADY }

data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val drm: VideoDrm? = null,
)

/**
 * Builds the Media3 [MediaItem], wiring per-stream DRM the same way
 * Samsung's `VideoDrm` model implies. `DefaultMediaSourceFactory` reads
 * the `DrmConfiguration` and sets up the Widevine/PlayReady session.
 */
fun Channel.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setUri(streamUrl)
        .apply {
            // The local ExoPlayer infers the container from the stream, but the
            // Cast receiver doesn't — CastPlayer's DefaultMediaItemConverter
            // *requires* an explicit mimeType ("The item must specify its
            // mimeType") or it throws when handing off. Infer it from the URL.
            mimeTypeFor(streamUrl)?.let { setMimeType(it) }
            drm?.let { d ->
                val uuid = when (d.scheme) {
                    DrmScheme.WIDEVINE -> C.WIDEVINE_UUID
                    DrmScheme.PLAYREADY -> C.PLAYREADY_UUID
                }
                val drmConfiguration = MediaItem.DrmConfiguration.Builder(uuid)
                    .setLicenseUri(d.licenseUrl)
                    .apply {
                        if (!d.customHeaderName.isNullOrBlank() && !d.customHeaderValue.isNullOrBlank()) {
                            setLicenseRequestHeaders(mapOf(d.customHeaderName to d.customHeaderValue))
                        }
                    }
                    .build()
                setDrmConfiguration(drmConfiguration)
            }
        }
        .build()

/**
 * Best-effort MIME type from a stream URL's extension, so the Cast receiver
 * knows the container. Covers the formats our samples use; null (unknown) lets
 * the local player fall back to inference. Strips any query string first.
 */
private fun mimeTypeFor(url: String): String? =
    when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
        "m3u8" -> MimeTypes.APPLICATION_M3U8
        "mpd" -> MimeTypes.APPLICATION_MPD
        "mp4" -> MimeTypes.VIDEO_MP4
        "webm" -> MimeTypes.VIDEO_WEBM
        else -> null
    }

/**
 * Default sample — Apple's advanced fMP4 multi-variant HLS, the format
 * Samsung TV+ streams (HLS-only). Swap in a server-stitched (SSAI)
 * manifest, or add a [VideoDrm] for a Widevine/PlayReady stream, to
 * exercise the rest of the path.
 */
val DEFAULT_CHANNEL: Channel = Channel(
    id = "bipbop",
    name = "Apple Advanced (fMP4) HLS",
    streamUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
)

/**
 * A row in the film list. Each carries its own [streamUrl] so tapping it
 * plays different content in the docked player. Thumbnails are CC0
 * placeholder photos (picsum) standing in for poster art; titles are
 * Creative-Commons / public test films, so nothing copyrighted is bundled.
 */
data class Film(
    val title: String,
    val subtitle: String,
    val tech: String,
    val thumbnailUrl: String,
    val streamUrl: String,
    val drm: VideoDrm? = null,
    // Category this film belongs to. The home screen groups the list into
    // category sections (with headers) and drives the breadcrumb chips from
    // them, mirroring Samsung TV+. Films of the same category are kept
    // contiguous in [SAMPLE_FILMS] so a section is one unbroken run.
    val category: String = "Other",
)

/** Maps a [Film] to a playable [Channel] (unique id so the player reloads). */
fun Film.toChannel(): Channel =
    Channel(id = "film:$title", name = title, streamUrl = streamUrl, drm = drm)

// Grouped by [Film.category] — same-category films are kept contiguous so each
// home-screen section is one unbroken run. Category order here is the order the
// breadcrumb chips appear in.
val SAMPLE_FILMS: List<Film> = listOf(
    // ---- Sci-Fi ----
    Film(
        // Widevine-protected "Tears of Steel" — Google's open test vector. The
        // .mpd segments are CENC-encrypted; the content key is fetched per
        // session from Google's UAT license proxy (no auth token needed), so
        // it exercises the full DRM path end-to-end. These vectors don't
        // require a secure surface (no HDCP enforcement), so even the
        // emulator's Widevine L3 (software CDM) renders them visibly — verified
        // climbing to HD 1920x856. Production DRM (e.g. Netflix) DOES enforce a
        // secure surface: there the frames decode in a protected path the app
        // can't read, so the video shows BLACK in screenshots/recordings —
        // that's the protection working, not a bug.
        title = "Tears of Steel — Widevine DRM",
        subtitle = "Encrypted · Google test vector",
        tech = "DASH · CENC · Widevine · H.264 + AAC · license-clean",
        thumbnailUrl = "https://picsum.photos/seed/wvtears/240/140",
        streamUrl = "https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd",
        drm = VideoDrm(
            licenseUrl = "https://proxy.uat.widevine.com/proxy",
            scheme = DrmScheme.WIDEVINE,
        ),
        category = "Sci-Fi",
    ),
    Film(
        title = "Tears of Steel",
        subtitle = "Sci-Fi · 2012 · Blender",
        tech = "HLS · fMP4/CMAF · H.264 + AAC · 1680×750",
        thumbnailUrl = "https://picsum.photos/seed/tearsofsteel/240/140",
        streamUrl = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
        category = "Sci-Fi",
    ),
    Film(
        title = "Tears of Steel — 4K demo",
        subtitle = "Sci-Fi · 2012 · Blender",
        tech = "HLS · fMP4 · H.264 + AAC · audio + WebVTT/TTML subtitle tracks",
        thumbnailUrl = "https://picsum.photos/seed/tos4k/240/140",
        streamUrl = "https://test-streams.mux.dev/tos_ismc/main.m3u8",
        category = "Sci-Fi",
    ),

    // ---- Animation ----
    Film(
        title = "Big Buck Bunny — HLS",
        subtitle = "Animation · 2008 · Blender",
        tech = "HLS · MPEG-TS · H.264 + AAC · up to 1080p",
        thumbnailUrl = "https://picsum.photos/seed/bigbuckbunny/240/140",
        streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        category = "Animation",
    ),
    Film(
        title = "Big Buck Bunny — DASH",
        subtitle = "Animation · 2008 · Blender",
        tech = "DASH · .mpd · fMP4 · H.264 + AAC · adaptive, up to 2160p (4K)",
        thumbnailUrl = "https://picsum.photos/seed/bbbdash/240/140",
        streamUrl = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd",
        category = "Animation",
    ),
    Film(
        title = "Big Buck Bunny — MP4",
        subtitle = "Animation · 2008 · Blender",
        tech = "Progressive MP4 · single file · H.264 + AAC · 320×180 · full ~10 min",
        thumbnailUrl = "https://picsum.photos/seed/bbbmp4/240/140",
        streamUrl = "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4",
        category = "Animation",
    ),

    // ---- Codecs ----
    Film(
        // VP9 video in a WebM container — Google's royalty-free, web-native
        // alternative to H.264-in-MP4. Media3 decodes it with no extra
        // dependency. ExoPlayer's own published test asset (Google CDN), so it
        // streams reliably to a player (unlike Wikimedia, which rate-limits
        // hotlinking with HTTP 429). Video-only clip — no audio track.
        // (VP9 is HW-decoded on real phones, SW-decoded on the emulator —
        // verify smoothness on a device.)
        title = "VP9 / WebM — codec demo",
        subtitle = "Royalty-free · Google test asset",
        tech = "WebM · VP9 · 360p · video-only",
        thumbnailUrl = "https://picsum.photos/seed/bbbwebm/240/140",
        streamUrl = "https://storage.googleapis.com/exoplayer-test-media-1/" +
            "gen-3/screens/dash-vod-single-segment/video-vp9-360.webm",
        category = "Codecs",
    ),

    // ---- World ----
    Film(
        // Shaka's "Angel One" — the classic multi-track test asset: 6 audio
        // languages (en/es/fr/de/it/el) plus subtitle tracks. Exercises audio
        // (and later subtitle) selection. Google CDN, license-clean.
        title = "Angel One — multi-audio",
        subtitle = "Shaka demo · multi-language",
        tech = "DASH · fMP4 · H.264 + AAC · 6 audio languages + subtitles",
        thumbnailUrl = "https://picsum.photos/seed/angelone/240/140",
        streamUrl = "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd",
        category = "World",
    ),

    // ---- Reference ----
    Film(
        title = "BipBop Advanced (fMP4)",
        subtitle = "Apple reference stream",
        tech = "HLS · fMP4 · H.264 + AAC/AC-3 · CC + subs · 60fps · to 1080p",
        thumbnailUrl = "https://picsum.photos/seed/bipbopadv/240/140",
        streamUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
        category = "Reference",
    ),
    Film(
        title = "BipBop Classic (4:3)",
        subtitle = "Apple reference stream",
        tech = "HLS · MPEG-TS · H.264 + AAC · 4:3 (legacy)",
        thumbnailUrl = "https://picsum.photos/seed/bipbop43/240/140",
        streamUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8",
        category = "Reference",
    ),
    Film(
        title = "Mux Reference Stream",
        subtitle = "Mux test stream",
        tech = "HLS · MPEG-TS · reference / conformance",
        thumbnailUrl = "https://picsum.photos/seed/muxref/240/140",
        streamUrl = "https://test-streams.mux.dev/test_001/stream.m3u8",
        category = "Reference",
    ),
    Film(
        title = "Time-Shift Demo",
        subtitle = "Mux test stream",
        tech = "HLS · MPEG-TS · shifted PTS timestamps",
        thumbnailUrl = "https://picsum.photos/seed/ptsshift/240/140",
        streamUrl = "https://test-streams.mux.dev/pts_shift/master.m3u8",
        category = "Reference",
    ),
)
