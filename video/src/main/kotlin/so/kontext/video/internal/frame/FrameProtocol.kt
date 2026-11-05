package so.kontext.video.internal.frame

import so.kontext.video.VideoAd
import so.kontext.video.internal.support.Logger

/**
 * The Kontext Frame Protocol v2 as rules, with no WebView: what a message of the ad's page means, in which state,
 * and what the bridge must do about it. The functional core of the page bridge; `FrameBridge` is its shell (the
 * WebView, its clients, the load timeout, the teardown) and does what [accept] returns.
 *
 * Why apart: every rule here (the sender, the order of the states, the video's ordering, the touch a click
 * needs, a navigation, a violation reported once) is tested as a table, with no WebView or main thread. Reading
 * the message itself (the JSON, the envelope, the limit, the fields, the playhead's bounds) is [FrameMessage]'s.
 *
 * How: the bridge passes each page message to [accept] and executes the [Step] it gets back. The clock comes in
 * as `now`; the page's origin and the link check come in from the bridge (they parse URLs with Android's `Uri`).
 *
 * The page's state ([PageState]) is not the ad's (`AdState`), and both are needed: the page's rules are checked
 * before the ad hears a message at all; the page can end itself (close, skip, error) before the ad knows; a page
 * can be loaded for an ad only offered, never shown; and an ad can be reserved (`show` accepted) while its page
 * is not shown yet. They move together at fixed points: the page's `ad.loaded` makes the ad loaded; the ad
 * reaching the screen shows the page (LOADED → SHOWN); a page that ends itself hands the ad its event, and the ad
 * ends; the ad's end, or its invalidation, hides the page (→ ENDED).
 *
 * ```
 *   message              loading         loaded          shown                       ended
 *   ad.loaded            Loaded          ignore          ignore                      ignore
 *   ad.click, ad.open    ignore          ignore          deliver, after a touch      ignore
 *   ad.close, video.skip ignore          ignore          endThenDeliver              ignore
 *   video.start          ignore          ignore          deliver                     ignore
 *   video.mute, unmute   ignore          ignore          deliver                     ignore
 *   other video.*        ignore          ignore          deliver after video.start   ignore
 *   ad.error             endThenDeliver  endThenDeliver  endThenDeliver              ignore
 *                        (400 video, 500 display; 405 on screen; the page's own code wins)
 *   unknown type         drop            drop            drop                        ignore
 *   broken envelope      drop, reported once as a protocol violation                 ignore
 *                        (FrameMessage.parse: not an object, another ad's id, no type, over 1 MB)
 * ```
 */
internal class FrameProtocol(
    /** The ad's id: every message of its page carries it. */
    val adId: String,
    /** The page's own origin, the one sender whose messages count; `null` for a URL with none (none count). */
    val origin: FrameBridge.Origin?,
    isVideo: Boolean,
    /** The video's length when the server gives one (a linear ad); without it the playhead is capped at a day. */
    videoDurationMillis: Long?,
    /** A URL's origin, for a navigation's target. */
    private val originOf: (String) -> FrameBridge.Origin?,
    /** Whether a link may open (see `FrameBridge.isAllowed`). */
    private val isAllowed: (String) -> Boolean,
) {
    /** What a top-level navigation of the page may do. */
    enum class NavigationDecision { ALLOW, CANCEL, EXTERNAL }

    /** What the bridge does with a page message. */
    sealed class Step {
        /** Nothing, not even a log line: a message the state makes meaningless (a late one, one too early). */
        object Ignore : Step()

        /** Dropped with [log]; [violation], when set, is reported once as a protocol violation. */
        class Drop(val log: String, val violation: String? = null, val isWarning: Boolean = false) : Step()

        /** `ad.loaded`: the page is ready; the load timeout stops and the page waits paused for `show`. */
        object Loaded : Step()

        /** [event] goes to the ad. */
        class Deliver(val event: FrameEvent) : Step()

        /** The page ended itself (close, skip, error): the bridge tears the page down, then [event] goes to the ad. */
        class EndThenDeliver(val event: FrameEvent) : Step()
    }

    var state: PageState = PageState.LOADING
        private set

    /** The VAST error of a page that fails before it was shown: 400 for a video, 500 for a display creative. */
    val loadErrorCode: Int = if (isVideo) 400 else 500

    /** The VAST error of a failure now: 405 on screen, else the load error. */
    val failureCode: Int get() = if (state == PageState.SHOWN) 405 else loadErrorCode

    private var lastTouchAt = Long.MIN_VALUE / 2
    private var hasReportedViolation = false

    /** `video.start` arrived: the video's progress, quartiles, pause, resume and complete count only after it. */
    private var isVideoStarted = false

    /** The highest playhead a page may report, in milliseconds: the video's duration and the grace, or a day. */
    private val playheadLimitMillis = videoDurationMillis?.let { minOf(it.coerceAtLeast(0) + PLAYHEAD_GRACE_MILLIS, DAY_MILLIS) } ?: DAY_MILLIS

    /**
     * Checks the sender of one text message from the page, reads it ([FrameMessage.parse]: a broken envelope is
     * a violation), and says what to do with it.
     */
    fun accept(raw: String?, sender: FrameBridge.Origin?, isMainFrame: Boolean, now: Long): Step {
        if (state == PageState.ENDED) return Step.Ignore
        if (!isFromPage(sender, isMainFrame)) return Step.Drop("message from another frame dropped")
        val message = FrameMessage.parse(raw, adId, playheadLimitMillis) ?: return drop("malformed message dropped", "envelope")
        return accept(message, now)
    }

    /**
     * The rules for one message of this page, read: its order in the page's state and the video's. It changes the
     * state for `ad.loaded` and for the messages that end the page.
     */
    // One branch per message type of the frame protocol.
    @Suppress("CyclomaticComplexMethod")
    fun accept(message: FrameMessage, now: Long): Step {
        if (state == PageState.ENDED) return Step.Ignore
        val type = message.type
        val playhead = message.playheadMillis
        Logger.debug { "frame ${adId.take(8)}: ← $type" }
        return when (type) {
            "ad.loaded" -> {
                if (state != PageState.LOADING) return Step.Ignore
                state = PageState.LOADED
                Step.Loaded
            }
            "ad.click", "ad.open" -> click(message, now)
            "ad.close" -> endIfShown(FrameEvent.Close(FrameEvent.CloseReason.from(message.reason)))
            "video.skip" -> endIfShown(FrameEvent.Skip(playhead))
            "video.complete" -> when {
                state != PageState.SHOWN -> Step.Ignore
                !isVideoStarted -> beforeStart(type)
                else -> Step.Deliver(FrameEvent.Complete(playhead))
            }
            "video.progress" -> progress(message)
            "ad.error" -> {
                // A VAST error code has three digits; any other code from the page goes as 900, undefined error.
                val given = message.code
                val code = given?.let { if (it in 100.0..999.0) it.toInt() else UNDEFINED_ERROR } ?: failureCode
                state = PageState.ENDED
                Step.EndThenDeliver(FrameEvent.Error(code))
            }
            else -> {
                val event = type.takeIf { it.startsWith("video.") }?.let { FrameEvent.VideoEvent.from(it.removePrefix("video.")) }
                    ?: return Step.Drop("unknown message $type dropped")
                if (state != PageState.SHOWN) return Step.Ignore
                video(event, type, playhead)
            }
        }
    }

    /**
     * A message that is not text (an ArrayBuffer) is never read: its `data` accessor throws. From the page itself
     * it is a broken envelope.
     */
    fun acceptNonText(sender: FrameBridge.Origin?, isMainFrame: Boolean): Step {
        if (state == PageState.ENDED || !isFromPage(sender, isMainFrame)) return Step.Ignore
        return drop("a message that is not text dropped", "envelope")
    }

    /** The SDK shows the page: only a loaded page can be, once. Says whether it moved to [PageState.SHOWN]. */
    fun show(): Boolean {
        if (state != PageState.LOADED) return false
        state = PageState.SHOWN
        return true
    }

    /** The page ends: terminal. Says whether it was not ended already, so its teardown runs once. */
    fun end(): Boolean {
        if (state == PageState.ENDED) return false
        state = PageState.ENDED
        return true
    }

    /** The viewer touched the page at [now]; a URL may be opened for [TOUCH_WINDOW_MILLIS]. */
    fun noteTouch(now: Long) {
        lastTouchAt = now
    }

    /** What a top-level navigation may do: while loading, its own origin and `about:blank`; once ready only an in-document (fragment) move. */
    fun navigationDecision(target: String, isMainFrame: Boolean, current: String?): NavigationDecision {
        if (!isMainFrame) return NavigationDecision.ALLOW
        val isOurs = originOf(target) == origin || target == "about:blank"
        if (state == PageState.LOADING) return if (isOurs) NavigationDecision.ALLOW else NavigationDecision.EXTERNAL
        if (current != null && '#' in target && target.substringBefore('#') == current.substringBefore('#')) return NavigationDecision.ALLOW
        return if (isOurs) NavigationDecision.CANCEL else NavigationDecision.EXTERNAL
    }

    /** A navigation out of our origin is a click the page could not report: honoured only after a touch. */
    fun externalNavigation(target: String, now: Long): Step {
        if (state != PageState.SHOWN || !isAllowed(target) || !consumeTouch(now)) return Step.Drop("navigation to $target cancelled", isWarning = true)
        return Step.Deliver(FrameEvent.Click(target, null, VideoAd.Click.Area.Creative))
    }

    private fun isFromPage(sender: FrameBridge.Origin?, isMainFrame: Boolean) = isMainFrame && sender != null && sender == origin

    /** A click or an open: on screen, to a link that may open, right after a touch. */
    private fun click(message: FrameMessage, now: Long): Step {
        if (state != PageState.SHOWN) return Step.Ignore
        val type = message.type
        val primary = message.url?.takeIf(isAllowed)
        val fallback = if (type == "ad.click") message.fallbackUrl?.takeIf(isAllowed) else null
        // A click whose link may not open still has its fallback, which then is the click-through.
        val target = primary ?: fallback ?: return Step.Ignore
        if (!consumeTouch(now)) return Step.Drop("$type to $target dropped, no touch before it", isWarning = true)
        return if (type == "ad.click") {
            Step.Deliver(FrameEvent.Click(target, fallback.takeIf { primary != null }, VideoAd.Click.Area.from(message.area)))
        } else {
            Step.Deliver(FrameEvent.Open(target, message.purpose ?: "unknown"))
        }
    }

    /** The page's own end (close, skip): only on screen. */
    private fun endIfShown(event: FrameEvent): Step {
        if (state != PageState.SHOWN) return Step.Ignore
        state = PageState.ENDED
        return Step.EndThenDeliver(event)
    }

    /**
     * A checkpoint of the video, in milliseconds within a day, a fraction rounded to the nearest, after
     * `video.start`; anything else would fire every checkpoint early.
     */
    private fun progress(message: FrameMessage): Step {
        if (state != PageState.SHOWN) return Step.Ignore
        if (!isVideoStarted) return beforeStart(message.type)
        val offset = message.offset
        if (offset == null || offset !in 0.0..DAY_MILLIS.toDouble()) {
            return drop("video.progress with a malformed offset dropped", "progress.offset")
        }
        return Step.Deliver(FrameEvent.Progress(minOf(Math.round(offset), playheadLimitMillis), message.playheadMillis))
    }

    /**
     * `video.start` opens the video; mute and unmute are the viewer's and may come while it still loads; the
     * rest need the start before them.
     */
    private fun video(event: FrameEvent.VideoEvent, type: String, playhead: Long): Step {
        when (event) {
            FrameEvent.VideoEvent.START -> isVideoStarted = true
            FrameEvent.VideoEvent.MUTE, FrameEvent.VideoEvent.UNMUTE -> Unit
            else -> if (!isVideoStarted) return beforeStart(type)
        }
        return Step.Deliver(FrameEvent.Video(event, playhead))
    }

    /**
     * A video message before `video.start` is dropped: progress, quartiles or a complete for a video that never
     * started would be billed with no impression.
     */
    private fun beforeStart(type: String): Step = drop("$type before video.start dropped", "video.beforeStart")

    /** A drop that is a protocol violation: reported for the first one only, so a broken page reports once. */
    private fun drop(log: String, violation: String): Step.Drop {
        val first = !hasReportedViolation
        hasReportedViolation = true
        return Step.Drop(log, violation.takeIf { first })
    }

    /** One tap opens one URL: the first click, open or navigation it authorized spends it. */
    private fun consumeTouch(now: Long): Boolean {
        if (now - lastTouchAt > TOUCH_WINDOW_MILLIS) return false
        lastTouchAt = Long.MIN_VALUE / 2
        return true
    }

    companion object {
        const val TOUCH_WINDOW_MILLIS = 1_000L
        private const val DAY_MILLIS = 86_400_000L

        /** How far past the video's duration a page playhead may go: the page's rounding, not more. */
        private const val PLAYHEAD_GRACE_MILLIS = 1_000L

        /** VAST's undefined error: what a page's code outside 100-999 becomes. */
        private const val UNDEFINED_ERROR = 900
    }
}
