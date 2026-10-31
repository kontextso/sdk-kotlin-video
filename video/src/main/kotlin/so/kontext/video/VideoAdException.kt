package so.kontext.video

/**
 * Why the SDK could not do its job, reached two ways.
 *
 * - Thrown by a call (`format`, `load`, `show`, `reject`, `dismiss`): that call did not happen. Nothing is
 *   shown. Whether the ad stays usable is said per case.
 * - Delivered through [VideoAdListener.onAdFailed], after a `show` that was accepted: the ad broke on
 *   screen ([CreativeFailed]), or the linear ad's screen never started ([PresenterUnavailable]).
 *   `onAdEnded(FAILED)` follows and the ad is over.
 */
public sealed class VideoAdException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** `KontextVideo.initialize` was not called. Works once it runs. */
    public class NotInitialized : VideoAdException("KontextVideo.initialize() was not called")

    /** `/config` failed, and so did the attempt this `format()` made. Every `format()` tries it again; no ads until it succeeds. */
    public class InitializationFailed : VideoAdException("/config failed, and trying it again failed too; no ads until it succeeds")

    /** The `VideoAds` was destroyed. It is terminal: create a new one for the next content. */
    public class Destroyed : VideoAdException("the VideoAds was destroyed")

    /** The server switched the SDK off for this app launch. Loaded ads cannot be shown either; an ad already on screen finishes normally. */
    public class Disabled(public val reason: String) : VideoAdException("the SDK is disabled by the server ($reason)")

    /** The request succeeded and the server had nothing for this format. Not a failure, and never retried by the SDK. */
    public class NoFill(public val reason: NoFillReason) : VideoAdException("no fill ($reason)")

    /**
     * The request failed: no network, a timeout, a server error, an unexpected answer ([cause] says which).
     * Thrown only when the SDK holds no valid ad for the format; otherwise `format()` returns that one.
     */
    public class Request(cause: Throwable) : VideoAdException("request failed: ${cause.message ?: cause.javaClass.simpleName}", cause)

    /**
     * The creative failed. Thrown by `load()` when the frame page failed or did not report it loaded within
     * 10 s of active time; delivered through `onAdFailed` when it broke on screen. [code] is the VAST error
     * code the SDK fired (400 for a video, 500 otherwise, 405 after show, or the page's own).
     */
    public class CreativeFailed(public val code: Int) : VideoAdException("the creative failed (VAST error $code)")

    /** The ad was already shown: it cannot be shown again, nor rejected. */
    public class AlreadyShown : VideoAdException("the ad was already shown")

    /** Another ad is on screen, from any `VideoAds`: at most one in the whole app. This ad is untouched and still ready. */
    public class AnotherAdShowing : VideoAdException("another ad is on screen; show this one after it ended")

    /** The ad ended before it was shown: it expired, was rejected, or its creative died. `load()`, `show` and `reject()` throw it from then on. */
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
     */
    public class ContainerTooSmall(public val minimumWidthPx: Int, public val minimumHeightPx: Int) :
        VideoAdException("the container is too small for the ad; at least $minimumWidthPx×$minimumHeightPx px are needed")

    /**
     * Nothing on screen can hold the ad: the container is not attached to a window, is not shown, or has no
     * area; for a linear ad the Activity is finishing or destroyed. The ad stays ready. Delivered through
     * `onAdFailed` when `show` was accepted but the linear ad's screen never started; the ad is over then.
     */
    public class PresenterUnavailable : VideoAdException("nothing on screen can hold the ad now")

    /** The call does not fit the format: `show(activity)` on an overlay or pause ad, `show(container)` or `dismiss()` on a linear ad. */
    public class WrongPresentation : VideoAdException(
        "this call does not fit the ad's format (show(container) for overlay and pause, show(activity) for linear; dismiss() only for overlay and pause)",
    )
}
