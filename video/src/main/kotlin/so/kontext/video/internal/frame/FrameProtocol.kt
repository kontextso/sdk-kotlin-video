package so.kontext.video.internal.frame

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import so.kontext.video.VideoAd
import so.kontext.video.internal.network.double
import so.kontext.video.internal.network.obj
import so.kontext.video.internal.network.str
import so.kontext.video.internal.support.Logger

/**
 * The Kontext Frame Protocol v2 as rules, with no WebView: what a message of the ad's page means, in which state,
 * and what the bridge must do about it. The functional core of the page bridge; `FrameBridge` is its shell (the
 * WebView, its clients, the load timeout, the teardown) and does what [accept] returns.
 *
 * Why apart: every rule here (the envelope, the sender, the order of the states, the video's ordering, the
 * playhead's bounds, the touch a click needs) is tested as a table, with no WebView or main thread.
 *
 * How: the bridge passes each page message to [accept] and executes the [Step] it gets back. The clock comes in
 * as `now`; the page's origin and the link check come in from the bridge (they parse URLs with Android's `Uri`).
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
 *                        (not an object, another ad's id, no type, over MAX_MESSAGE_BYTES)
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
    /** Where the page is: loading, ready off screen, on screen, or gone (terminal). */
    enum class State { LOADING, LOADED, SHOWN, ENDED }

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

    var state: State = State.LOADING
        private set

    /** The VAST error of a page that fails before it was shown: 400 for a video, 500 for a display creative. */
    val loadErrorCode: Int = if (isVideo) 400 else 500

    /** The VAST error of a failure now: 405 on screen, else the load error. */
    val failureCode: Int get() = if (state == State.SHOWN) 405 else loadErrorCode

    private var lastTouchAt = Long.MIN_VALUE / 2
    private var hasReportedViolation = false

    /** `video.start` arrived: the video's progress, quartiles, pause, resume and complete count only after it. */
    private var isVideoStarted = false

    /** The highest playhead a page may report, in milliseconds: the video's duration and the grace, or a day. */
    private val playheadLimitMillis = videoDurationMillis?.let { minOf(it.coerceAtLeast(0) + PLAYHEAD_GRACE_MILLIS, DAY_MILLIS) } ?: DAY_MILLIS

    /**
     * Checks the envelope, the sender and the order of one text message from the page, and says what to do. It
     * changes the state for `ad.loaded` and for the messages that end the page.
     */
    // One branch per message type of the frame protocol.
    @Suppress("CyclomaticComplexMethod")
    fun accept(raw: String?, sender: FrameBridge.Origin?, isMainFrame: Boolean, now: Long): Step {
        if (state == State.ENDED) return Step.Ignore
        if (!isFromPage(sender, isMainFrame)) return Step.Drop("message from another frame dropped")
        // A message over the limit is never parsed: a broken envelope, as on iOS.
        val body = raw?.takeIf(::fitsLimit)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }?.obj()
        val type = body?.str("type")
        if (body == null || body.str("adId") != adId || type == null) return drop("malformed message dropped", "envelope")
        Logger.debug { "frame ${adId.take(8)}: ← $type" }
        // Milliseconds into the ad video, a fraction rounded to the nearest (a page reads `currentTime * 1000`),
        // clamped to the video's duration (a day without one): a playhead past the end would fire every checkpoint
        // at once.
        val playhead = Math.round((body.double("playhead") ?: 0.0).coerceIn(0.0, playheadLimitMillis.toDouble()))
        return when (type) {
            "ad.loaded" -> {
                if (state != State.LOADING) return Step.Ignore
                state = State.LOADED
                Step.Loaded
            }
            "ad.click", "ad.open" -> click(type, body, now)
            "ad.close" -> endIfShown(FrameEvent.Close(FrameEvent.CloseReason.from(body.str("reason"))))
            "video.skip" -> endIfShown(FrameEvent.Skip(playhead))
            "video.complete" -> when {
                state != State.SHOWN -> Step.Ignore
                !isVideoStarted -> beforeStart(type)
                else -> Step.Deliver(FrameEvent.Complete(playhead))
            }
            "video.progress" -> progress(type, body, playhead)
            "ad.error" -> {
                // A VAST error code has three digits; any other code from the page goes as 900, undefined error.
                val given = body.double("code")
                val code = given?.let { if (it in 100.0..999.0) it.toInt() else UNDEFINED_ERROR } ?: failureCode
                state = State.ENDED
                Step.EndThenDeliver(FrameEvent.Error(code))
            }
            else -> {
                val event = type.takeIf { it.startsWith("video.") }?.let { FrameEvent.VideoEvent.from(it.removePrefix("video.")) }
                    ?: return Step.Drop("unknown message $type dropped")
                if (state != State.SHOWN) return Step.Ignore
                video(event, type, playhead)
            }
        }
    }

    /**
     * A message that is not text (an ArrayBuffer) is never read: its `data` accessor throws. From the page itself
     * it is a broken envelope.
     */
    fun acceptNonText(sender: FrameBridge.Origin?, isMainFrame: Boolean): Step {
        if (state == State.ENDED || !isFromPage(sender, isMainFrame)) return Step.Ignore
        return drop("a message that is not text dropped", "envelope")
    }

    /** The SDK shows the page: only a loaded page can be, once. Says whether it moved to [State.SHOWN]. */
    fun show(): Boolean {
        if (state != State.LOADED) return false
        state = State.SHOWN
        return true
    }

    /** The page ends: terminal. Says whether it was not ended already, so its teardown runs once. */
    fun end(): Boolean {
        if (state == State.ENDED) return false
        state = State.ENDED
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
        if (state == State.LOADING) return if (isOurs) NavigationDecision.ALLOW else NavigationDecision.EXTERNAL
        if (current != null && '#' in target && target.substringBefore('#') == current.substringBefore('#')) return NavigationDecision.ALLOW
        return if (isOurs) NavigationDecision.CANCEL else NavigationDecision.EXTERNAL
    }

    /** A navigation out of our origin is a click the page could not report: honoured only after a touch. */
    fun externalNavigation(target: String, now: Long): Step {
        if (state != State.SHOWN || !isAllowed(target) || !consumeTouch(now)) return Step.Drop("navigation to $target cancelled", isWarning = true)
        return Step.Deliver(FrameEvent.Click(target, null, VideoAd.Click.Area.Creative))
    }

    private fun isFromPage(sender: FrameBridge.Origin?, isMainFrame: Boolean) = isMainFrame && sender != null && sender == origin

    /** A click or an open: on screen, to a link that may open, right after a touch. */
    private fun click(type: String, body: JsonObject, now: Long): Step {
        if (state != State.SHOWN) return Step.Ignore
        val primary = body.str("url")?.takeIf(isAllowed)
        val fallback = if (type == "ad.click") body.str("fallbackUrl")?.takeIf(isAllowed) else null
        // A click whose link may not open still has its fallback, which then is the click-through.
        val target = primary ?: fallback ?: return Step.Ignore
        if (!consumeTouch(now)) return Step.Drop("$type to $target dropped, no touch before it", isWarning = true)
        return if (type == "ad.click") {
            Step.Deliver(FrameEvent.Click(target, fallback.takeIf { primary != null }, VideoAd.Click.Area.from(body.str("area"))))
        } else {
            Step.Deliver(FrameEvent.Open(target, body.str("purpose") ?: "unknown"))
        }
    }

    /** The page's own end (close, skip): only on screen. */
    private fun endIfShown(event: FrameEvent): Step {
        if (state != State.SHOWN) return Step.Ignore
        state = State.ENDED
        return Step.EndThenDeliver(event)
    }

    /**
     * A checkpoint of the video, in milliseconds within a day, a fraction rounded to the nearest, after
     * `video.start`; anything else would fire every checkpoint early.
     */
    private fun progress(type: String, body: JsonObject, playhead: Long): Step {
        if (state != State.SHOWN) return Step.Ignore
        if (!isVideoStarted) return beforeStart(type)
        val offset = (body["offset"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        if (offset == null || offset !in 0.0..DAY_MILLIS.toDouble()) {
            return drop("video.progress with a malformed offset dropped", "progress.offset")
        }
        return Step.Deliver(FrameEvent.Progress(minOf(Math.round(offset), playheadLimitMillis), playhead))
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

    /** Whether a message is within [MAX_MESSAGE_BYTES] in UTF-8; never fewer bytes than UTF-16 units, so a long one is out at once. */
    private fun fitsLimit(raw: String): Boolean = raw.length <= MAX_MESSAGE_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES

    /** One tap opens one URL: the first click, open or navigation it authorized spends it. */
    private fun consumeTouch(now: Long): Boolean {
        if (now - lastTouchAt > TOUCH_WINDOW_MILLIS) return false
        lastTouchAt = Long.MIN_VALUE / 2
        return true
    }

    companion object {
        const val TOUCH_WINDOW_MILLIS = 1_000L

        /** The largest page message parsed, in UTF-8 bytes; every message of the protocol is far smaller. */
        const val MAX_MESSAGE_BYTES = 1024 * 1024
        private const val DAY_MILLIS = 86_400_000L

        /** How far past the video's duration a page playhead may go: the page's rounding, not more. */
        private const val PLAYHEAD_GRACE_MILLIS = 1_000L

        /** VAST's undefined error: what a page's code outside 100-999 becomes. */
        private const val UNDEFINED_ERROR = 900
    }
}
