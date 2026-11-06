package so.kontext.video

/**
 * Why the SDK could not do its job, reached two ways.
 *
 * - Thrown by a call (`format`, `load`, `show`, `reject`): that call did not happen. Nothing is
 *   shown. Whether the ad stays usable is said per case.
 * - Delivered through [VideoAdListener.onAdFailed], after a `show` that was accepted: the ad broke on
 *   screen ([CreativeFailed]), or the linear ad's Activity never started ([CannotShowNow]).
 *   `onAdEnded(FAILED)` follows and the ad is over.
 */
public sealed class VideoAdException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** `KontextVideo.initialize` was not called. Works once it runs. */
    public class NotInitialized : VideoAdException("KontextVideo.initialize() was not called")

    /** `/config` failed, and so did the attempt this `format()` made. Every `format()` tries it again; no ads until it succeeds. */
    public class InitializationFailed : VideoAdException("/config failed, and trying it again failed too; no ads until it succeeds")

    /** The `VideoAds` was destroyed. It is terminal: create a new one for the next content. */
    public class Destroyed : VideoAdException("the VideoAds was destroyed")

    /**
     * The server switched the SDK off for this app launch. Loaded ads cannot be shown either; an ad already on screen finishes normally.
     * @property reason What the server or the SDK said, for logs.
     */
    public class Disabled(public val reason: String) : VideoAdException("the SDK is disabled by the server ($reason)")

    /**
     * The request succeeded and the server had nothing for this format. Not a failure, and never retried by the SDK.
     * @property reason Why the server had nothing.
     */
    public class NoFill(public val reason: NoFillReason) : VideoAdException("no fill ($reason)")

    /**
     * The request failed: [failure] says why (no network, a timeout, a server error, an answer the SDK could not
     * use). Thrown only when the SDK holds no valid ad for the format; otherwise `format()` returns that one.
     * Never retried by the SDK; the app decides when to ask again. The underlying exception is in the SDK's log,
     * with the request's id.
     * @property failure Why the request failed.
     */
    public class Request(public val failure: RequestFailure) : VideoAdException("request failed ($failure)")

    /**
     * The creative failed. Thrown by `load()` when the frame page failed or did not report it loaded within
     * 10 s of active time; delivered through `onAdFailed` when it broke on screen. [code] is the VAST error
     * code the SDK fired (before show 400 for a video, 500 otherwise; 405 after show; or the page's own, 900
     * when it is outside 100-999).
     */
    public class CreativeFailed(public val code: Int) : VideoAdException("the creative failed (VAST error $code)")

    /** The ad was already shown: it cannot be shown again, nor rejected. */
    public class AlreadyShown : VideoAdException("the ad was already shown")

    /** Another ad is on screen, from any `VideoAds`: at most one in the whole app. This ad is untouched and still ready. */
    public class AnotherAdShowing : VideoAdException("another ad is on screen; show this one after it ended")

    /**
     * The ad ended before it was shown: it expired, was rejected, or its creative died. `load()` and `show` throw it from then on.
     * @property reason Why it ended.
     */
    public class Invalidated(public val reason: VideoAd.InvalidationReason) :
        VideoAdException("the ad ended before it was shown ($reason)")

    /** `show` before `load()` finished: nothing is rendered yet. The ad stays as it was. */
    public class NotLoaded : VideoAdException("the ad is not loaded yet; call load() first")

    /** `show(container)` on an overlay while the tracked content is paused: an overlay runs over playing content only. The ad stays ready. */
    public class ContentPaused : VideoAdException("the content is paused; show the overlay once it plays")

    /**
     * No variant of the ad fits the container: it would have to shrink below 50 % (aspect ratio kept), or
     * at all for a creative marked not scalable. The minimum is the smallest container that holds it, in
     * pixels, its insets included. The ad stays ready: show it in a bigger container, or reject it.
     * @property minimumWidthPx The narrowest container that holds the ad, in pixels.
     * @property minimumHeightPx The lowest container that holds the ad, in pixels.
     */
    public class ContainerTooSmall(public val minimumWidthPx: Int, public val minimumHeightPx: Int) :
        VideoAdException("the container is too small for the ad; at least $minimumWidthPx×$minimumHeightPx px are needed")

    /**
     * Nothing on screen can hold the ad: the container is not attached to a window, is not shown, has no area
     * or lies wholly outside its window, its Activity is stopped or in picture-in-picture, or the app is in the
     * background; for a linear ad the Activity is finishing, destroyed, stopped or in picture-in-picture. The ad stays ready. Delivered through
     * `onAdFailed` when `show` was accepted but the linear ad's Activity never started; the ad is over then.
     */
    public class CannotShowNow : VideoAdException("nothing on screen can hold the ad now")

    /** The call does not fit the format: `show(activity)` on an overlay or pause ad, `show(container)` on a linear ad. */
    public class WrongShowMethod : VideoAdException(
        "this call does not fit the ad's format (show(container) for overlay and pause, show(activity) for linear)",
    )

    /**
     * Why an ads request failed, in four outcomes an app can act on. Unlike a no-fill, the server's answer for
     * the moment is unknown: asking again later may fill. There is no cancelled case: only `destroy()` cancels a
     * request, and its callers hear `Destroyed`.
     */
    public sealed class RequestFailure(private val name: String) {
        /**
         * The request did not get through: no connection, the connection lost, the server's name or address
         * unreachable, a secure connection that failed (often a captive portal). Ask again once the device is
         * back online, for example at the next episode or the next pause.
         */
        public object Offline : RequestFailure("offline")

        /** The server did not answer in time (10 s). The network is slow or the server is busy: ask again later, not straight away. */
        public object TimedOut : RequestFailure("timedOut")

        /**
         * The server answered with an error status (5xx, or any other the contract does not expect, 4xx included).
         * Ask again later; quote `VideoAds.lastRequestId` to Kontext when it repeats.
         * @property status The HTTP status.
         */
        public class Server(public val status: Int) : RequestFailure("server($status)") {
            override fun equals(other: Any?): Boolean = other is Server && status == other.status

            override fun hashCode(): Int = status
        }

        /**
         * An answer arrived and the SDK could not use it: not the JSON of the contract, malformed (one ad under
         * two formats, for example), empty, cut off, not HTTP, or over the 5 MB limit. Asking again soon is unlikely to
         * help; quote `VideoAds.lastRequestId` to Kontext.
         */
        public object BadResponse : RequestFailure("badResponse")

        /** The case's name, for logs: `offline`, `timedOut`, `server(503)`, `badResponse`. */
        override fun toString(): String = name
    }

    /** Why the server had nothing for a format; [Other] is a reason this SDK version does not know, as sent. */
    public sealed class NoFillReason(internal val wireName: String) {
        /** No campaign matched the content, the viewer or the moment. */
        public object NoAdMatch : NoFillReason("noAdMatch")

        /** The content is not brand safe. */
        public object ContentUnsafe : NoFillReason("contentUnsafe")

        /** No configuration for this format in the Kontext dashboard: the integration, not the moment. */
        public object FormatNotEnabled : NoFillReason("formatNotEnabled")

        /** The viewer has seen enough ads for now. */
        public object FrequencyCap : NoFillReason("frequencyCap")

        /** Not served in this country. */
        public object GeoRestricted : NoFillReason("geoRestricted")

        /** The server's side; ask again later. */
        public object TemporaryError : NoFillReason("temporaryError")

        /** The SDK's own: the answer was an ad the app had just shown or rejected while the request ran. */
        public object AlreadyUsed : NoFillReason("alreadyUsed")

        /** The server gave no reason, or one the SDK could not read. */
        public object Unknown : NoFillReason("unknown")

        /**
         * A reason this SDK version does not know, as the server sent it.
         * @property raw The reason as sent.
         */
        public class Other(public val raw: String) : NoFillReason(raw) {
            override fun equals(other: Any?): Boolean = other is Other && raw == other.raw

            override fun hashCode(): Int = raw.hashCode()
        }

        /** The wire name, for logs. */
        override fun toString(): String = wireName
    }
}
