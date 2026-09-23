package so.kontext.video

import android.util.Size
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import so.kontext.video.ads.interstitial.InterstitialAd
import so.kontext.video.ads.overlay.OverlayAd
import so.kontext.video.ads.pause.PauseAd
import so.kontext.video.model.VideoAdEvent
import so.kontext.video.model.VideoAdFormat
import so.kontext.video.model.VideoSeries

/**
 * The ads of one video — returned by [VideoSession.createAds].
 *
 * One placement object per format. The app decides when ads are loaded and
 * shown; the SDK does not observe the player:
 *
 * ```kotlin
 * val ads = session.createAds(videoId = "ep-1042")
 * ads.load(listOf(VideoAdFormat.PAUSE, VideoAdFormat.INTERSTITIAL, VideoAdFormat.OVERLAY))
 * // on pause
 * if (ads.pause.isReady) ads.pause.show(playerContainer)
 * // on resume
 * ads.pause.hide()
 * ads.pause.load()                              // prepare the next one
 * ```
 *
 * Each ad is shown once; `show` consumes it. Load again to prepare another —
 * whether one is served is the server's decision.
 *
 * Handles are independent, so the next episode's can be created and loaded
 * while this one plays. When the viewer is done with a video, [destroy] —
 * nothing releases a handle for you.
 */
public class VideoAds internal constructor(
    /** The publisher's unique video ID this handle belongs to. */
    public val videoId: String,
    public val title: String?,
    public val description: String?,
    /** Video length in milliseconds, when the app provided it. */
    public val durationMs: Long?,
    public val language: String?,
    public val episodeNumber: Int?,
    public val series: VideoSeries?,
    private val session: VideoSession,
    private val emit: (VideoAdEvent) -> Unit,
) {
    /** Banner over part of the paused player. */
    public val pause: PauseAd = PauseAd(videoId, emit)

    /** Full-screen video the SDK presents at a moment the app chooses. */
    public val interstitial: InterstitialAd = InterstitialAd(videoId, emit)

    /** Image banners while the episode plays, in server-derived windows. */
    public val overlay: OverlayAd = OverlayAd(videoId, emit)

    /** `true` while a `/preload` request is in flight. */
    public var isLoading: Boolean = false
        private set

    /** `true` after [destroy]; the handle ignores `load` and `show`. */
    public var isDestroyed: Boolean = false
        private set

    private var loadJob: Job? = null

    init {
        pause.loadHandler = { load(listOf(VideoAdFormat.PAUSE)) }
        interstitial.loadHandler = { load(listOf(VideoAdFormat.INTERSTITIAL)) }
        overlay.loadHandler = { load(listOf(VideoAdFormat.OVERLAY)) }
    }

    /**
     * Requests ads for this video in one `/preload` call and appends them to
     * the placements' `pending` queues. Emits one [VideoAdEvent.Loaded] with
     * a result row per requested format. Ignored while a request is already
     * in flight.
     *
     * @param formats Which placements to fill. Must not be empty — only what
     *   you list is requested.
     * @param playerSize The player's size in pixels, when known, so the
     *   server can pick a creative that fits.
     */
    public fun load(formats: List<VideoAdFormat>, playerSize: Size? = null) {
        require(formats.isNotEmpty()) { "VideoAds.load(formats) called with an empty list — name the formats you want" }
        if (isDestroyed || isLoading || !session.isEnabled) return
        isLoading = true
        loadJob = session.scope.launch {
            val outcome = session.preload(this@VideoAds, formats, playerSize)
            isLoading = false
            if (isDestroyed || !outcome.succeeded) return@launch
            val ads = outcome.ads
            pause.append(ads.filter { it.format == VideoAdFormat.PAUSE })
            interstitial.append(ads.filter { it.format == VideoAdFormat.INTERSTITIAL })
            overlay.append(ads.filter { it.format == VideoAdFormat.OVERLAY })
            val rows = formats.map { format ->
                val served = ads.filter { it.format == format }
                    .map { VideoAdEvent.LoadedData.FormatResult.Ad(id = it.adId, ecpm = it.ecpm) }
                val server = outcome.results?.firstOrNull { it.format == format.wireValue }
                VideoAdEvent.LoadedData.FormatResult(
                    format = format,
                    ads = served,
                    noFillReason = server?.noFillReason
                        ?: outcome.noFillReason.takeIf { served.isEmpty() },
                )
            }
            emit(VideoAdEvent.Loaded(VideoAdEvent.LoadedData(videoId = videoId, results = rows)))
        }
    }

    /**
     * Releases everything for this video: cancels an in-flight load, finishes
     * measurement, removes ad views, and drops pending ads. When an ad was on
     * screen its view stays for about a second ([Constants.TEARDOWN_DELAY_MS])
     * so measurement can send its final beacons. Idempotent.
     */
    public fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        loadJob?.cancel()
        loadJob = null
        isLoading = false
        pause.destroy()
        interstitial.destroy()
        overlay.destroy()
    }
}
