package so.kontext.video.example

import androidx.media3.common.C
import androidx.media3.common.MediaItem

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
)

/** Maps a [Film] to a playable [Channel] (unique id so the player reloads). */
fun Film.toChannel(): Channel = Channel(id = "film:$title", name = title, streamUrl = streamUrl)

val SAMPLE_FILMS: List<Film> = listOf(
    Film(
        title = "Big Buck Bunny",
        subtitle = "Animation · 2008 · Blender",
        tech = "HLS · MPEG-TS · H.264 + AAC · up to 1080p",
        thumbnailUrl = "https://picsum.photos/seed/bigbuckbunny/240/140",
        streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
    ),
    Film(
        title = "Tears of Steel",
        subtitle = "Sci-Fi · 2012 · Blender",
        tech = "HLS · fMP4/CMAF · H.264 + AAC · 1680×750",
        thumbnailUrl = "https://picsum.photos/seed/tearsofsteel/240/140",
        streamUrl = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
    ),
    Film(
        title = "Tears of Steel — 4K demo",
        subtitle = "Sci-Fi · 2012 · Blender",
        tech = "HLS · fMP4 · H.264 + AAC · audio + WebVTT/TTML subtitle tracks",
        thumbnailUrl = "https://picsum.photos/seed/tos4k/240/140",
        streamUrl = "https://test-streams.mux.dev/tos_ismc/main.m3u8",
    ),
    Film(
        title = "BipBop Advanced (fMP4)",
        subtitle = "Apple reference stream",
        tech = "HLS · fMP4 · H.264 + AAC/AC-3 · CC + subs · 60fps · to 1080p",
        thumbnailUrl = "https://picsum.photos/seed/bipbopadv/240/140",
        streamUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
    ),
    Film(
        title = "BipBop Classic (4:3)",
        subtitle = "Apple reference stream",
        tech = "HLS · MPEG-TS · H.264 + AAC · 4:3 (legacy)",
        thumbnailUrl = "https://picsum.photos/seed/bipbop43/240/140",
        streamUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8",
    ),
    Film(
        title = "Mux Reference Stream",
        subtitle = "Mux test stream",
        tech = "HLS · MPEG-TS · reference / conformance",
        thumbnailUrl = "https://picsum.photos/seed/muxref/240/140",
        streamUrl = "https://test-streams.mux.dev/test_001/stream.m3u8",
    ),
    Film(
        title = "Time-Shift Demo",
        subtitle = "Mux test stream",
        tech = "HLS · MPEG-TS · shifted PTS timestamps",
        thumbnailUrl = "https://picsum.photos/seed/ptsshift/240/140",
        streamUrl = "https://test-streams.mux.dev/pts_shift/master.m3u8",
    ),
)
