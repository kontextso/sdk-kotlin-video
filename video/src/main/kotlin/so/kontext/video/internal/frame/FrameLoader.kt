package so.kontext.video.internal.frame

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment

/**
 * Gets an ad's page running in a WebView: makes its [FrameBridge] once, loads it, and lets the ad wait for the
 * page's `ad.loaded`. A device with no usable WebView (missing, updating, or refusing the bridge) is a typed
 * failure, [NoWebView], never an exception of the platform's. What the ad does about any of it (the error
 * beacon, the reports, the public exceptions) stays `VideoAd`'s.
 *
 * ```
 *   bridge(existing) ──► the page's bridge, made once (VideoAd.makeBridge), or NoWebView
 *   load(bridge)     ──► the page loads; the returned signal completes at ad.loaded (loaded())
 *                        or fails with the page's error or the ad's end (fail(error))
 * ```
 */
internal class FrameLoader(private val payload: AdPayload) {
    /** No WebView for the page: it is missing or updating, or refused the bridge. */
    class NoWebView(cause: Throwable) : Exception(cause)

    /** The signal of the load in progress, until the page reports `ad.loaded` or fails. */
    private var signal: CompletableDeferred<Unit>? = null

    /** A load waits for the page's `ad.loaded`. */
    val isWaiting: Boolean get() = signal != null

    /**
     * The ad's bridge: [existing], or a new one made through the seam, whose events go to [onEvent]. A linear
     * ad's duration is its video's length; an overlay's is its time on screen, not the video's: a fact of the
     * linear format, not of its screen. Throws [NoWebView].
     */
    fun bridge(existing: FrameBridge?, isLinear: Boolean, onEvent: (FrameEvent) -> Unit): FrameBridge = try {
        existing ?: VideoAd.makeBridge(
            checkNotNull(Environment.context) { "no application context" },
            payload.id,
            payload.frameUrl,
            payload.creative == AdPayload.Creative.VIDEO,
            payload.durationMillis.takeIf { isLinear },
        ).also { it.onEvent = onEvent }
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        throw NoWebView(error)
    }

    /** Loads [bridge]'s page; the returned signal completes at its `ad.loaded`. */
    fun load(bridge: FrameBridge): Deferred<Unit> {
        val loading = CompletableDeferred<Unit>().also { signal = it }
        bridge.load()
        return loading
    }

    /** The page reported `ad.loaded`: the load waiting, if any, is done. */
    fun loaded() {
        signal?.complete(Unit)
        signal = null
    }

    /** The load waiting, if any, fails with [error]; says whether one waited. */
    fun fail(error: VideoAdException): Boolean {
        val waiting = signal ?: return false
        signal = null
        waiting.completeExceptionally(error)
        return true
    }
}
