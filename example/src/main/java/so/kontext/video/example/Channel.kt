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
