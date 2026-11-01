package so.kontext.video

import android.content.ComponentCallbacks2
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.ad.contentDidChange
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.playback.ContentPlayback
import so.kontext.video.internal.rendering.Tracker
import so.kontext.video.internal.support.DebugReporter
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.ErrorReporter
import so.kontext.video.internal.support.Labels
import so.kontext.video.internal.support.Logger
import so.kontext.video.internal.wire.AdPayload
import so.kontext.video.internal.wire.AdsRequest
import so.kontext.video.internal.wire.AdsResponse
import java.util.UUID
import android.content.res.Configuration as AndroidConfiguration

/**
 * The ads of one video ("content"): one per episode, created when it opens. Creating it sends nothing; every
 * request comes from [format]. Create the next episode's while the current one plays if you like; any number
 * can be alive at once. Call [destroy] when the viewer leaves the video (see its notes). Every call is
 * main-thread, except [format], which may be called from any dispatcher.
 *
 * [labels] say where the ads play, such as the viewer's stage in the series or the player's layout
 * (`mapOf("stage" to "first_episode")`); every ads request of this `VideoAds` carries them, fixed for its
 * life. Keys of `a-z`, `0-9` and `_`, values not empty, both at most 64 bytes, at most 20 pairs; the rest is
 * dropped with a warning. They describe the screen, never the viewer: no personal data.
 */
public class VideoAds @JvmOverloads constructor(
    /** The video the ads are for; every request carries it. */
    public val content: Content,
    labels: Map<String, String> = emptyMap(),
) {
    /** [destroy] was called: the object does nothing any more. */
    public var isDestroyed: Boolean = false
        private set

    /** The id of the latest ads request, `null` before the first. Quote it to Kontext when an ad or a no-fill looks wrong. */
    public var lastRequestId: String? = null
        private set

    internal val labels: Map<String, String> = Labels.checked(labels, content.id)
    internal val playback = ContentPlayback()

    /** One per `VideoAds`, sent with every request: the server keeps the bids it gave out per session. */
    internal val sessionId: String = UUID.randomUUID().toString()

    /** The unshown ad of each format, at most one. */
    internal val ads = mutableMapOf<AdFormat, VideoAd>()

    /** Ids that left: shown, rejected, expired, failed, destroyed. A response that brings one back is ignored. */
    private val spentIds = mutableSetOf<String>()

    /** The ad of this `VideoAds` on screen. */
    internal var showing: VideoAd? = null
        private set

    /** Where this object's requests and loads run; [destroy] cancels it. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var request: Deferred<Map<AdFormat, NoFillReason>>? = null

    /** Under memory pressure an expired ad's WebView goes at once; valid ads stay loaded, so `show` stays instant. */
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = dropExpiredAds()
        override fun onConfigurationChanged(newConfig: AndroidConfiguration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = dropExpiredAds()
    }

    init {
        Sdk.requireInitialized("VideoAds(content)")
        // What the publisher passed wrong, said once here rather than as a server 400 later.
        val missing = content.missingRequired
        if (missing.isNotEmpty()) {
            Environment.assertionFailure("Content: ${missing.joinToString()}")
            Logger.error { "content ${content.id}: ${missing.joinToString()}; the server will refuse its requests" }
            ErrorReporter.report(ErrorReporter.Code.BAD_CONTENT, ErrorReporter.Stage.INTEGRATION, sessionId = sessionId, detail = missing.joinToString(","))
        }
        if (content.leftOut.isNotEmpty()) Logger.warning { "content ${content.id}: ${content.leftOut.joinToString()}" }
        Environment.context?.registerComponentCallbacks(memoryCallbacks)
        // The ad on screen hears of the player: an overlay ends when the content stops, and counts its time.
        playback.onChange = { change -> showing?.contentDidChange(change) }
        playback.isOverlayShown = { showing?.let { it.format == AdFormat.OVERLAY && !it.hasEnded } ?: false }
        Logger.debug { "VideoAds created for video ${content.id}" }
    }

    /**
     * The ad for [format]: `ads.format(AdFormat.PAUSE)`, then its `price` and other facts, then `load()` or
     * `reject()`. Every call asks the server for all formats and lists the unshown ads it holds (`held`); the
     * server returns those unchanged until they expire, so the same ad comes back. Calls made while a request
     * runs share it. A failed request returns the held ad when it is still valid. Throws [VideoAdException]:
     * `NoFill`, `Request` (nothing valid held), `Destroyed`, `Disabled`, `NotInitialized`, `InitializationFailed`.
     * A cancelled caller stops waiting; the request itself continues. Callable from any dispatcher: the work
     * runs on the main thread.
     */
    public suspend fun format(format: AdFormat): VideoAd = withContext(Dispatchers.Main.immediate) { formatOnMain(format) }

    private suspend fun formatOnMain(format: AdFormat): VideoAd {
        if (isDestroyed) throw VideoAdException.Destroyed()
        if (!Sdk.requireInitialized("ads.format()")) throw VideoAdException.NotInitialized()
        val task = request ?: startRequest()
        val noFill = try {
            task.await()
        } catch (error: VideoAdException.Request) {
            checkStillUsable()
            ads[format]?.takeIf { !it.isExpired }?.let { held ->
                Logger.info { "$format for ${content.id}: request failed, returning the held ad ${held.id.take(8)}" }
                return held
            }
            throw error
        } catch (error: CancellationException) {
            // The request was cancelled by destroy(): this caller hears `Destroyed`, not a cancellation it never asked for.
            if (isDestroyed && task.isCancelled) throw VideoAdException.Destroyed()
            throw error
        }
        checkStillUsable()
        return ads[format] ?: throw VideoAdException.NoFill(noFill[format] ?: NoFillReason.Unknown)
    }

    /** [format] for callers without coroutines: [callback] gets the ad, or the exception, on the main thread. */
    @MainThread
    public fun format(format: AdFormat, callback: VideoAdsCallback<VideoAd>) {
        Environment.scope.launch {
            val ad = try {
                format(format)
            } catch (error: VideoAdException) {
                callback.onError(error)
                return@launch
            }
            callback.onSuccess(ad)
        }
    }

    /**
     * The player this episode plays in. The SDK reads its time for requests and the `[CONTENTPLAYHEAD]` macro
     * of beacons, and follows its playback for an overlay: an overlay is shown over playing content only
     * (`show` throws `ContentPaused` otherwise), ends with `INTERRUPTED` when the content pauses or ends, and
     * its duration counts milliseconds of video. Held until [destroy] or the next `track`. With one player
     * reused across episodes, call it when this episode starts playing in it, not at creation. Without a
     * player the playhead is 0 (right for a prefetch) and an overlay runs on its own time.
     */
    @MainThread
    public fun track(player: VideoAdsPlayer) {
        Sdk.checkMainThread("VideoAds.track()")
        if (isDestroyed) return
        playback.track(player)
    }

    /**
     * Releases the video's ads. Every valid held ad, loaded or not, is dropped with a `notUsed` beacon and its
     * WebView released; one that expired leaves silently. `load()` and `show` on them throw `Destroyed`; the ad
     * on screen ends with `DESTROYED`; a `format()` or `load()` in flight throws `Destroyed`. Idempotent.
     *
     * Call it with whatever owns the `VideoAds`. A `ViewModel`: in `onCleared()`, and in every new Activity
     * `track` its player and set the ads' listeners again, so the ads survive a rotation. An Activity, a
     * Fragment or a Compose `DisposableEffect`: in its `onDestroy()` (`onDispose`), every time, with
     * `android:configChanges` declared for rotation so a rotation does not recreate it. A `VideoAds` nobody
     * destroys is never released.
     */
    @MainThread
    public fun destroy() {
        Sdk.checkMainThread("VideoAds.destroy()")
        if (isDestroyed) return
        isDestroyed = true
        scope.cancel()
        request = null
        endAll()
        Environment.context?.unregisterComponentCallbacks(memoryCallbacks)
        playback.stop()
        Logger.debug { "VideoAds destroyed for video ${content.id}" }
    }

    override fun toString(): String = "VideoAds(content=${content.id}, destroyed=$isDestroyed)"

    /** Content time in milliseconds, read now, or 0. */
    internal val playheadMillis: Long get() = playback.playheadMillis

    /** An ad ended before `show`, or was shown: it leaves its format, and its id is never accepted again. */
    internal fun adDidLeave(ad: VideoAd) {
        if (ads[ad.format] === ad) ads.remove(ad.format)
        spentIds += ad.id
    }

    internal fun adDidBeginShowing(ad: VideoAd) {
        showing = ad
        // An overlay counts its milliseconds of video, and watches a player without a listener, on ticks.
        playback.isTicking = ad.format == AdFormat.OVERLAY
        adDidLeave(ad)
    }

    internal fun adDidEnd(ad: VideoAd) {
        if (showing !== ad) return
        showing = null
        playback.isTicking = false
    }

    private fun dropExpiredAds() {
        val expired = ads.values.filter { it.isExpired }
        if (expired.isEmpty()) return
        Logger.info { "${content.id}: memory pressure, ${expired.size} expired ad(s) released" }
        expired.forEach { it.invalidate(VideoAd.InvalidationReason.Expired) }
    }

    private val holdsAds: Boolean get() = ads.values.any { !it.isExpired }

    private fun checkStillUsable() {
        if (isDestroyed) throw VideoAdException.Destroyed()
        Sdk.disabledReason?.let { throw VideoAdException.Disabled(it) }
    }

    /**
     * The request belongs to the `VideoAds`, not to the caller: nobody cancels it but [destroy]. Expired ads
     * are dropped first (silently; the server knows `expires`), so `held` lists valid ones.
     */
    private fun startRequest(): Deferred<Map<AdFormat, NoFillReason>> {
        ads.values.filter { it.isExpired }.forEach { it.invalidate(VideoAd.InvalidationReason.Expired) }
        val held = AdFormat.entries.mapNotNull { ads[it]?.id }
        val task = scope.async {
            run {
                val enabled = Sdk.waitUntilEnabled()
                checkStillUsable()
                if (!enabled) {
                    Logger.warning { "${content.id}: not requested, the SDK is ${Sdk.state}" }
                    Sdk.disabledReason?.let { throw VideoAdException.Disabled(it) }
                    throw VideoAdException.InitializationFailed()
                }
                val configuration = Sdk.configuration ?: throw VideoAdException.NotInitialized()
                val context = Environment.context ?: throw VideoAdException.NotInitialized()
                val built = AdsRequest.make(context, configuration.publisherToken, content, labels, playheadMillis, sessionId, held)
                // Another video's response may have switched the SDK off meanwhile: nothing leaves.
                checkStillUsable()
                lastRequestId = built.requestId
                Logger.debug { "${content.id}: requesting at playhead ${built.playheadMillis} ms, holding ${held.size}" }
                DebugReporter.event("ads.request", state = mapOf("held" to held.size, "playhead" to built.playheadMillis))
                val response = try {
                    AdsResponse.parse(ApiClient(configuration.adServerUrl).post("video/ads", built.body))
                } catch (error: CancellationException) {
                    throw error
                } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                    checkStillUsable()
                    Logger.error { "${content.id}: request ${built.requestId} failed, ${error.message}" }
                    @Suppress("InstanceOfCheckForException") // the code, not the handling, depends on it
                    ErrorReporter.report(
                        if (error is ApiException.Decoding) ErrorReporter.Code.ADS_DECODE_FAILED else ErrorReporter.Code.ADS_REQUEST_FAILED,
                        ErrorReporter.Stage.REQUEST,
                        requestId = built.requestId,
                        sessionId = sessionId,
                        failure = ErrorReporter.RequestFailure(error),
                        recovery = if (holdsAds) ErrorReporter.Recovery.HELD_ADS_KEPT else ErrorReporter.Recovery.NO_AD,
                    )
                    throw VideoAdException.Request(error)
                }
                receive(response)
            }
        }
        request = task
        task.invokeOnCompletion { if (request === task) request = null }
        return task
    }

    /** The kill switch in an answer switches the whole SDK off and drops every ad, with no notices. */
    private fun receive(response: AdsResponse): Map<AdFormat, NoFillReason> {
        if (response is AdsResponse.Disabled) {
            Sdk.disable(response.reason)
            ads.values.toList().forEach { it.invalidate(VideoAd.InvalidationReason.Destroyed) }
            throw VideoAdException.Disabled(response.reason)
        }
        checkStillUsable()
        return apply(response)
    }

    /**
     * Follows the server for every format: the same id keeps the bid unchanged; a new id replaces it, as does a
     * no-fill. An id already spent (shown or rejected while the request ran) is ignored for its format.
     */
    private fun apply(response: AdsResponse): Map<AdFormat, NoFillReason> {
        val noFill = mutableMapOf<AdFormat, NoFillReason>()
        for (format in AdFormat.entries) {
            val current = ads[format]
            val result = response.result(format)
            val payload: AdPayload? = result?.ads?.firstOrNull()
            if (payload == null) {
                current?.invalidate(VideoAd.InvalidationReason.Expired)
                val reason = result?.noFillReason ?: NoFillReason.Unknown
                noFill[format] = reason
                logNoFill(format, reason)
                continue
            }
            if (current != null && current.id == payload.id && !current.isExpired) continue
            current?.invalidate(VideoAd.InvalidationReason.Expired)
            if (payload.id in spentIds) {
                Logger.debug { "$format for ${content.id}: ad ${payload.id.take(8)} was already used, ignored" }
                noFill[format] = NoFillReason.AlreadyUsed
                continue
            }
            Logger.info { "$format for ${content.id}: ad ${payload.id.take(8)}, ${payload.price.value} ${payload.price.currency} cpm" }
            DebugReporter.event("ads.response", adId = payload.id, state = mapOf("format" to format.wireName, "filled" to true))
            ads[format] = VideoAd(payload, Environment.now(), this)
        }
        return noFill
    }

    private fun logNoFill(format: AdFormat, reason: NoFillReason) {
        if (reason == NoFillReason.FormatNotEnabled) {
            Logger.error { "$format for ${content.id}: no configuration for this format in the Kontext dashboard ($reason)" }
        } else {
            Logger.info { "$format for ${content.id}: no fill ($reason)" }
        }
        val known = if (reason is NoFillReason.Other) "other" else reason.toString()
        DebugReporter.event("ads.response", state = mapOf("format" to format.wireName, "filled" to false, "reason" to known))
    }

    /** Every valid held ad gets its `notUsed` beacon and ends; the ad on screen ends. */
    private fun endAll() {
        for (ad in ads.values.toList()) {
            if (ad.isExpired) {
                ad.invalidate(VideoAd.InvalidationReason.Expired)
                continue
            }
            ad.withContentPlayhead {
                ad.invalidate(VideoAd.InvalidationReason.Destroyed)
                ad.tracker.fire("notUsed", notUsed = Tracker.NotUsed.Destroyed)
            }
        }
        ads.clear()
        showing?.end(VideoAd.EndReason.DESTROYED)
        showing = null
    }
}
