package so.kontext.video

import android.content.ComponentCallbacks2
import android.content.res.Configuration
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
import so.kontext.kit.util.UuidV7
import so.kontext.video.KontextVideo.Content
import so.kontext.video.VideoAdException.NoFillReason
import so.kontext.video.internal.content.ContentPlayback
import so.kontext.video.internal.diagnostics.DebugReporter
import so.kontext.video.internal.diagnostics.IntegrationGuard
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.measurement.Tracker
import so.kontext.video.internal.network.AdsFetcher
import so.kontext.video.internal.network.PendingBeacons
import so.kontext.video.internal.payloads.AdsResponse
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.state.AdStore
import so.kontext.video.internal.state.LiveVideoAds
import so.kontext.video.internal.state.SdkState
import java.lang.ref.WeakReference

/**
 * The ads of one video ("content"): one per episode, created when it opens. Creating it sends nothing; every
 * request comes from [format]. Create the next episode's while the current one plays if you like; any number
 * can be alive at once. Call [destroy] when the viewer leaves the video (see its notes). Every call is
 * main-thread, except [format], which may be called from any dispatcher.
 *
 * Kotlin: `VideoAds(content, section = "feed")`. Java: `new VideoAds(content, "feed", null)`.
 */
public class VideoAds
@MainThread @JvmOverloads
constructor(
    /** The video the ads are for; every request carries it. */
    public val content: Content,
    /**
     * Where in the app the player is, as Kontext named it with you (`"feed"`, `"series_detail"`): it chooses the
     * formats (and their settings) the publisher configured for that section. One non-empty name of at most 128
     * characters; `null` sends none. Empty or longer is an integration mistake: a debuggable build stops at it, and
     * the value is left out of the request. It describes the screen, never the viewer: no personal data. Every
     * request of this `VideoAds` carries it.
     */
    public val section: String? = null,
    /**
     * The publisher's A/B test variant for this video's ads; `null` when none. Sent with every request. Empty or
     * longer than 128 characters is an integration mistake: a debuggable build stops at it, and the value is left
     * out of the request.
     */
    public val variantId: String? = null,
) {
    /** [destroy] was called: the object does nothing any more. */
    public var isDestroyed: Boolean = false
        private set

    /** The id of the latest ads request, `null` before the first. Quote it to Kontext when an ad or a no-fill looks wrong. */
    public val lastRequestId: String? get() = fetcher.lastRequestId

    internal val playback = ContentPlayback()

    /**
     * One per `VideoAds`, sent with every request: the server keeps the bids it gave out per session. A UUID v7,
     * so sessions sort by when they began.
     */
    internal val sessionId: String = UuidV7.generate()

    /** The ads held, at most one unshown per format, and the ids that left (see [AdStore]). */
    private val store = AdStore()

    /** The `notUsed` and `error` beacons of this object's ads still on the wire: the next request waits for them. */
    internal val pendingBeacons = PendingBeacons()

    /** The unshown ad of each format, at most one. */
    internal val ads: MutableMap<AdFormat, VideoAd> get() = store.ads

    /** The ad of this `VideoAds` on screen. */
    // Stored, not derived from ScreenSlot.current: destroy() clears it at once while a linear ad's slot lasts until its screen closes.
    internal var showing: VideoAd? = null
        private set

    /** Where this object's requests and loads run; [destroy] cancels it. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var request: Deferred<Map<AdFormat, NoFillReason>>? = null

    /** The ads request itself: the waits, the body, the answer, its failures and the kill switch. */
    private val fetcher = AdsFetcher(
        content,
        section,
        variantId,
        sessionId,
        playheadMillis = { playheadMillis },
        pendingBeacons = pendingBeacons,
        checkIdsAgainstHeld = store::checkIdsAgainstHeld,
        checkStillUsable = ::checkStillUsable,
    )

    /** Under memory pressure an expired ad's WebView goes at once; valid ads stay loaded, so `show` stays instant. */
    private val memoryCallbacks = MemoryCallbacks(this)

    /** Content time in milliseconds, read now, or 0. */
    internal val playheadMillis: Long get() = playback.playheadMillis

    init {
        IntegrationGuard.requireInitialized("VideoAds(content)")
        // What the publisher passed wrong, said once here rather than as a server 400 later.
        IntegrationGuard.checkContent(content, sessionId)
        IntegrationGuard.checkNames(section, variantId)
        // Held weakly: the kill switch reaches its unshown ads; it does not keep a forgotten one alive.
        LiveVideoAds.add(this)
        Environment.context?.registerComponentCallbacks(memoryCallbacks)
        // The ad on screen hears of the player: an overlay ends when the content stops, and counts its time.
        playback.onChange = { change -> showing?.contentRule?.contentDidChange(change) }
        playback.isInlineAdShown = { showing?.let { it.format != AdFormat.LINEAR && !it.hasEnded } ?: false }
        Logger.debug { "VideoAds created for video ${content.id}" }
    }

    /**
     * The ad for [format]: `ads.format(AdFormat.PAUSE)`, then its `price` and other facts, then `load()` or
     * `reject()`. Every call asks the server for all formats of the [section]; the server keeps the bids it gave
     * this `VideoAds` and returns an unshown one unchanged until it expires or its outcome is known, so the same ad
     * comes back. A `reject()` just before is heard first: the request waits up to 2 s for its `notUsed` beacon.
     * Calls made while a request runs share it. A failed request returns the held ad when it is still valid.
     * Without `KontextVideo.user` nothing is sent and it fails with `Request(MissingUser)`, held ad or not. Throws
     * [VideoAdException]: `NoFill`, `Request` (nothing valid held; its `failure` says why), `Destroyed`,
     * `Disabled`, `NotInitialized`, `InitializationFailed`.
     * A cancelled caller stops waiting; the request itself continues. Callable from any dispatcher: the work
     * runs on the main thread.
     */
    public suspend fun format(format: AdFormat): VideoAd = withContext(Dispatchers.Main.immediate) { formatOnMain(format) }

    private suspend fun formatOnMain(format: AdFormat): VideoAd {
        if (isDestroyed) throw VideoAdException.Destroyed()
        if (!IntegrationGuard.requireInitialized("ads.format()")) throw VideoAdException.NotInitialized()
        // 1. One request at a time, shared by every caller.
        val task = request ?: startRequest()
        // 2. Its answer; a failed request still returns the held ad while it is valid.
        val noFill = try {
            task.await()
        } catch (error: VideoAdException.Request) {
            checkStillUsable()
            // No user is the app's mistake, not the moment's: a held ad must not hide it.
            if (error.failure == VideoAdException.RequestFailure.MissingUser) throw error
            store.ad(format)?.let { held ->
                Logger.info { "$format for ${content.id}: request failed, returning the held ad ${held.id.take(8)}" }
                return held
            }
            throw error
        } catch (error: CancellationException) {
            // The request was cancelled by destroy(): this caller hears `Destroyed`, not a cancellation it never asked for.
            if (isDestroyed && task.isCancelled) throw VideoAdException.Destroyed()
            throw error
        }
        // 3. The format's held ad, or its no-fill reason.
        checkStillUsable()
        store.ad(format)?.let { return it }
        val reason = noFill[format] ?: NoFillReason.Unknown
        // The answer covers every format, so its no-fills are logged quietly; a format the app asks for and the
        // dashboard does not configure is a setup mistake the developer must see.
        if (reason == NoFillReason.FormatNotEnabled) Logger.warning { notEnabledMessage(format, reason) }
        throw VideoAdException.NoFill(reason)
    }

    /** [format] for callers without coroutines: [callback] gets the ad, or the exception, on the main thread. */
    @MainThread
    public fun format(format: AdFormat, callback: VideoAdCallback) {
        Environment.scope.launch {
            val ad = try {
                format(format)
            } catch (error: VideoAdException) {
                callback.onError(error)
                return@launch
            }
            callback.onAd(ad)
        }
    }

    /**
     * The player this episode plays in. The SDK reads its time for requests and the `[CONTENTPLAYHEAD]` macro
     * of beacons, and follows its playback for an overlay: an overlay is shown over playing content only
     * (`show` throws `ContentPaused` otherwise), ends with `INTERRUPTED` when the content pauses or ends, and
     * its duration counts milliseconds of video. A pause while the app is in the background (your app pausing
     * the player in `onStop`) only freezes it: it goes on if the content plays again within a second of the
     * return, and ends `INTERRUPTED` otherwise. A pause ad ends with `INTERRUPTED` when the content plays again
     * after it was seen paused while the ad is on screen, wherever the viewer resumed it (the media
     * notification, the lock screen, a headset, picture-in-picture); an overlay shown then finds the screen free.
     * Held until [destroy] or the next `track`. With one player
     * reused across episodes, call it when this episode starts playing in it, not at creation. Without a
     * player the playhead is 0 (right for a prefetch), an overlay runs on its own time and a pause ad stays until it is dismissed or closed.
     *
     * Show an episode's ads only in that episode: an ad requested for it is counted for it. A player belongs to
     * one `VideoAds` at a time: tracking it in another one lets go of it here, as if this one had never tracked
     * a player (an overlay of it on screen ends `INTERRUPTED`), and from then on its overlay and pause ads throw
     * `Invalidated(ContentChanged)` at `show`, until this one tracks a player again. So do they once the player
     * reports another item than the one at `track` (or, with none then, the first it reported): another episode,
     * or a loop's next play. A Media3 player reports its current media item; a [ContentPlayer] its
     * `currentItem`, when it implements it. Linear ads are not checked: they cover the content. Without a tracked
     * player nothing can be checked.
     */
    @MainThread
    public fun track(player: ContentPlayer) {
        if (!IntegrationGuard.requireMainThread("VideoAds.track()")) return
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
     * destroys is freed once nothing holds it (a player it tracks holds it while that player lives); its ad on
     * screen then runs until it ends. Only `destroy()` ends its ads at once.
     */
    @MainThread
    public fun destroy() {
        if (!IntegrationGuard.requireMainThread("VideoAds.destroy()")) return
        if (isDestroyed) return
        isDestroyed = true
        scope.cancel()
        request = null
        endAll()
        LiveVideoAds.remove(this)
        Environment.context?.unregisterComponentCallbacks(memoryCallbacks)
        playback.stop()
        Logger.debug { "VideoAds destroyed for video ${content.id}" }
    }

    override fun toString(): String = "VideoAds(content=${content.id}, section=$section, destroyed=$isDestroyed)"

    /** An ad ended before `show`, or was shown: it leaves its format, and its id is never accepted again. */
    internal fun adDidLeave(ad: VideoAd) = store.left(ad)

    internal fun adDidBeginShowing(ad: VideoAd) {
        showing = ad
        // An overlay counts its milliseconds of video, and an overlay or pause ad watches a player without a
        // listener, on ticks. With no player tracked there is nothing to watch; `track` starts them later.
        playback.isTicking = ad.format != AdFormat.LINEAR && playback.isTracking
        store.beganShowing(ad)
    }

    /** `show` reserved [ad] and could not put it on screen: it is held again as before, its id unspent. */
    internal fun adDidNotShow(ad: VideoAd, wasHeld: Boolean) {
        if (showing === ad) {
            showing = null
            playback.isTicking = false
        }
        store.didNotShow(ad, wasHeld)
    }

    internal fun adDidEnd(ad: VideoAd) {
        if (showing !== ad) return
        showing = null
        playback.isTicking = false
    }

    /**
     * Registered with the application, which outlives every `VideoAds`: it holds its `VideoAds` weakly, so one
     * nobody destroys can be collected, and unregisters itself at the first callback after that.
     */
    private class MemoryCallbacks(ads: VideoAds) : ComponentCallbacks2 {
        private val ads = WeakReference(ads)

        override fun onTrimMemory(level: Int) = dropExpiredAds()
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = dropExpiredAds()

        private fun dropExpiredAds() {
            val owner = ads.get()
            if (owner == null) Environment.context?.unregisterComponentCallbacks(this) else owner.dropExpiredAds()
        }
    }

    private fun dropExpiredAds() {
        val expired = ads.values.filter { it.isExpired }
        if (expired.isEmpty()) return
        Logger.info { "${content.id}: memory pressure, ${expired.size} expired ad(s) released" }
        expired.forEach { it.invalidate(VideoAd.InvalidationReason.Expired) }
    }

    private fun checkStillUsable() {
        if (isDestroyed) throw VideoAdException.Destroyed()
        SdkState.current.disabledReason?.let { throw VideoAdException.Disabled(it) }
    }

    /**
     * The request belongs to the `VideoAds`, not to the caller: nobody cancels it but [destroy]. Calls made while
     * it runs share it. [AdsFetcher] asks the server; the answer is then followed as [AdStore.plan] says.
     */
    private fun startRequest(): Deferred<Map<AdFormat, NoFillReason>> {
        val task = scope.async { apply(fetcher.fetch()) }
        request = task
        task.invokeOnCompletion { if (request === task) request = null }
        return task
    }

    /**
     * Follows the server for every format it answered for: the same id keeps the bid unchanged; a new id
     * replaces it, as does a no-fill. A format the answer leaves out keeps its held ad as it is (its own expiry
     * still applies); with nothing held it is a no-fill of unknown reason. An id already spent (shown or
     * rejected while the request ran) is ignored for its format. A held ad that expired while the request ran
     * leaves first, silently, whether the answer names its format or not.
     */
    private fun apply(response: AdsResponse): Map<AdFormat, NoFillReason> {
        store.dropExpired()
        val noFill = mutableMapOf<AdFormat, NoFillReason>()
        for (step in store.plan(response)) {
            val format = step.format
            when (step) {
                is AdStore.Step.Keep -> Unit
                is AdStore.Step.Drop -> ads[format]?.invalidate(VideoAd.InvalidationReason.Expired)
                is AdStore.Step.NoFill -> {
                    noFill[format] = step.reason
                    if (step.isAnswered) logNoFill(format, step.reason)
                }
                is AdStore.Step.IgnoreUsed -> {
                    Logger.debug { "$format for ${content.id}: ad ${step.id.take(8)} was already used, ignored" }
                    noFill[format] = NoFillReason.AlreadyUsed
                }
                is AdStore.Step.Replace -> {
                    val payload = step.payload
                    Logger.info { "$format for ${content.id}: ad ${payload.id.take(8)}, ${payload.price.value} ${payload.price.currency} cpm" }
                    DebugReporter.event("ads.response", sessionId = sessionId, bidId = payload.id, properties = mapOf("format" to format.wireName, "filled" to true))
                    ads[format] = VideoAd(payload, Environment.now(), this)
                }
            }
        }
        return noFill
    }

    private fun logNoFill(format: AdFormat, reason: NoFillReason) {
        // Every format's answer, asked for or not: quiet here; format() warns for the one the app asks for.
        if (reason == NoFillReason.FormatNotEnabled) {
            Logger.info { notEnabledMessage(format, reason) }
        } else {
            Logger.info { "$format for ${content.id}: no fill ($reason)" }
        }
        val known = if (reason is NoFillReason.Other) "other" else reason.toString()
        DebugReporter.event("ads.response", sessionId = sessionId, properties = mapOf("format" to format.wireName, "filled" to false, "reason" to known))
    }

    private fun notEnabledMessage(format: AdFormat, reason: NoFillReason) =
        "$format for ${content.id}: no configuration for this format in the Kontext dashboard ($reason)"

    /** Every valid held ad gets its `notUsed` beacon and ends; the ad on screen ends. */
    private fun endAll() {
        store.endAll { ad ->
            ad.withContentPlayhead {
                ad.invalidate(VideoAd.InvalidationReason.Destroyed)
                ad.fireNotUsed(Tracker.NotUsed.Destroyed)
            }
        }
        showing?.end(VideoAd.EndReason.DESTROYED)
        showing = null
    }
}
