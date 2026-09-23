package so.kontext.video

import android.content.Context
import android.util.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import so.kontext.video.internal.InstallId
import so.kontext.video.internal.log
import so.kontext.video.model.VideoAd
import so.kontext.video.model.VideoAdEvent
import so.kontext.video.model.VideoAdFormat
import so.kontext.video.model.VideoSeries
import so.kontext.video.network.AdsService
import so.kontext.video.network.InitService

/**
 * One instance per app user. Owns the connection to the ad server and the
 * viewing-session ID.
 *
 * Lifecycle:
 * 1. Create via [KontextVideo.createSession].
 * 2. Call [createAds] for a video, as early as you like — the next episode
 *    while the current one plays. It returns the [VideoAds] handle for that
 *    video; call [VideoAds.load] on it to request ads. Any number of handles
 *    can be alive at once.
 * 3. Drive the ads from the handle — the SDK never observes the player; the
 *    app tells it when to show, hide, and load again — and [VideoAds.destroy]
 *    it when the viewer is done with that video.
 *
 * Every public method must be called on the main thread.
 */
public class VideoSession internal constructor(
    private val context: Context,
    public val options: VideoSessionOptions,
) {
    /** `false` once `/init` reports the session permanently disabled; `load` becomes a no-op. */
    public var isEnabled: Boolean = true
        private set

    /** Viewing-session ID assigned by the server on the first preload response. */
    internal var sessionId: String? = null
        private set

    /** Persistent per-install id, sent on `/init` and every `/preload`. */
    internal val installId: String = InstallId.getOrCreate(context)

    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val adsService = AdsService(options.adServerUrl)
    private val initService = InitService(options.adServerUrl)
    private var preloadTimeoutMs: Long = Constants.DEFAULT_PRELOAD_TIMEOUT_MS

    init {
        // Fire /init in the background: lets the server disable the session
        // or override the preload timeout. Never blocks ad requests.
        scope.launch { runInit() }
    }

    private suspend fun runInit() {
        val response = initService.fetchInit(
            InitService.InitRequest(
                publisherToken = options.publisherToken,
                userId = options.userId,
                installId = installId,
                sdk = SDKInfo.dto(),
                app = InitService.InitRequest.App.collect(context),
            ),
        ) ?: return
        isEnabled = response.enabled
        response.preloadTimeoutMs?.let { preloadTimeoutMs = it }
        if (!isEnabled) log("session disabled by /init")
    }

    /**
     * Creates the ads handle for one video. Does not request anything — call
     * [VideoAds.load] on the handle. Handles are independent: create the next
     * episode's while the current one plays so its ads are ready on arrival,
     * and [VideoAds.destroy] each one when the viewer is done with that video;
     * a handle you keep keeps its ads.
     *
     * @param videoId The publisher's unique ID for this video — any scheme
     *   (`"100001-6"`, a UUID…), as long as it identifies the video.
     * @param title Optional if the video was uploaded via the API. Otherwise
     *   it is contextual signal, like [description].
     * @param durationMs Video length in milliseconds, when known. Lets the
     *   server place overlay windows for videos it has never analyzed.
     * @param language The audio/subtitle track being played (ISO 639) — ad
     *   copy follows it, not the app locale.
     * @param episodeNumber Position within [series], when applicable.
     * @param series Series-level metadata; for short drama this is where the
     *   synopsis, keywords, and content rating live.
     */
    public fun createAds(
        videoId: String,
        title: String? = null,
        description: String? = null,
        durationMs: Long? = null,
        language: String? = null,
        episodeNumber: Int? = null,
        series: VideoSeries? = null,
    ): VideoAds = VideoAds(
        videoId = videoId,
        title = title,
        description = description,
        durationMs = durationMs,
        language = language,
        episodeNumber = episodeNumber,
        series = series,
        session = this,
        emit = ::emit,
    )

    internal class PreloadOutcome(
        val ads: List<VideoAd>,
        val results: List<AdsService.FormatResult>?,
        val noFillReason: String?,
        /** `false` when the request failed — the caller emits no `Loaded`. */
        val succeeded: Boolean,
    )

    internal suspend fun preload(ads: VideoAds, formats: List<VideoAdFormat>, playerSize: Size?): PreloadOutcome {
        val failed = PreloadOutcome(emptyList(), null, null, succeeded = false)
        return try {
            val response = adsService.preload(
                AdsService.PreloadRequest(
                    publisherToken = options.publisherToken,
                    userId = options.userId,
                    userEmail = options.userEmail,
                    sessionId = sessionId,
                    installId = installId,
                    id = ads.videoId,
                    title = ads.title,
                    description = ads.description,
                    durationMs = ads.durationMs,
                    language = ads.language,
                    episodeNumber = ads.episodeNumber,
                    series = ads.series,
                    formats = formats,
                    context = playerSize?.let { AdsService.PreloadRequest.Context(it.width, it.height) },
                    sdk = SDKInfo.dto(),
                ),
                timeoutMs = preloadTimeoutMs,
            )
            response.sessionId?.let { sessionId = it }
            if (ads.isDestroyed) return failed
            val served = response.ads.filter { it.format in formats }
            val reason = response.noFillReason?.let { " ($it)" } ?: ""
            log("/instream/preload -> ${served.size} ad(s) for ${ads.videoId}$reason")
            PreloadOutcome(served, response.results, response.noFillReason, succeeded = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(VideoAdEvent.Error(VideoAdEvent.ErrorData(videoId = ads.videoId, message = e.toString())))
            failed
        }
    }

    private fun emit(event: VideoAdEvent) {
        options.onEvent?.invoke(event)
    }
}
