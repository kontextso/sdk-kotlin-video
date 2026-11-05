package so.kontext.video.internal.frame

import so.kontext.video.VideoAd

/** What the frame page told the bridge, accepted after the bridge's checks. */
internal sealed class FrameEvent {
    object Loaded : FrameEvent()
    class Click(val url: String, val fallbackUrl: String?, val area: VideoAd.Click.Area) : FrameEvent()
    class Open(val url: String, val purpose: String) : FrameEvent()
    class Close(val reason: CloseReason) : FrameEvent()
    class Skip(val playheadMillis: Long) : FrameEvent()
    class Video(val event: VideoEvent, val playheadMillis: Long) : FrameEvent()
    class Progress(val offsetMillis: Long, val playheadMillis: Long) : FrameEvent()
    class Complete(val playheadMillis: Long) : FrameEvent()
    class Error(val code: Int) : FrameEvent()

    /** The video moments a page reports (`video.<name>`), named as their VAST beacons. */
    enum class VideoEvent(val wire: String) {
        START("start"), FIRST_QUARTILE("firstQuartile"), MIDPOINT("midpoint"), THIRD_QUARTILE("thirdQuartile"),
        PAUSE("pause"), RESUME("resume"), MUTE("mute"), UNMUTE("unmute");

        companion object {
            fun from(name: String): VideoEvent? = entries.firstOrNull { it.wire == name }
        }
    }

    /** Why the page ended itself. A reason the SDK does not know reads as close. */
    enum class CloseReason {
        CLOSE, RESUME, COMPLETE;

        /**
         * What a page's `ad.close` does to an ad: the beacon it fires, if any, how the ad ends, and whether it completes it.
         * This is the close reason the page gave: CLOSE (the viewer's close button), RESUME (a pause ad's Resume button) or
         * COMPLETE (the page ended itself after the video, with no end card). [isLinear]: a linear (video) ad. [isCompleted]:
         * the video already reached its end, which the SDK knows from the page's `complete` event, never from a close.
         *
         * The rows are checked top to bottom; the first that matches wins:
         *
         * ```
         *   #  when                          beacon  ends as       meaning
         *   1  linear and not completed      skip    SKIPPED       the viewer left a video ad before it finished: a skip for
         *      (any reason)                                        tracking, by the close button or anything else
         *   2  reason COMPLETE               none    COMPLETED     the ad ended by itself after its video ran out (it also
         *                                            (completes)   marks the ad completed); nobody closed it, so no close
         *   3  reason RESUME                 close   RESUMED       a pause ad's Resume button: closed to continue watching
         *   4  anything else (CLOSE)         close   CLOSED        closed with the close button: an overlay, a pause ad, or a
         *                                                          linear ad's end card after the video finished
         * ```
         *
         * Examples:
         * - A linear ad closed at second 10 of 30: row 1, `skip`, SKIPPED.
         * - A linear ad, video finished, the end card's close button: not row 1 (completed); reason CLOSE, row 4: `close`, CLOSED.
         * - A linear ad, video finished, no end card: the page sends COMPLETE, row 2: no beacon, COMPLETED.
         * - An overlay's close button: not linear, row 4: `close`, CLOSED.
         * - A pause ad's Resume: row 3: `close`, RESUMED.
         *
         * Row 1 comes first on purpose: a linear ad whose page says COMPLETE without the SDK having seen the video's
         * `complete` is a skip. The SDK trusts its own record of what was watched, which is what advertisers pay for.
         */
        fun outcome(isLinear: Boolean, isCompleted: Boolean): Outcome = when {
            isLinear && !isCompleted -> Outcome("skip", VideoAd.EndReason.SKIPPED)
            this == COMPLETE -> Outcome(null, VideoAd.EndReason.COMPLETED, completes = true)
            this == RESUME -> Outcome("close", VideoAd.EndReason.RESUMED)
            else -> Outcome("close", VideoAd.EndReason.CLOSED)
        }

        /** A page's close as the ad carries it out: [beacon] fired, then the end with [endReason]. */
        data class Outcome(val beacon: String?, val endReason: VideoAd.EndReason, val completes: Boolean = false)

        companion object {
            fun from(raw: String?): CloseReason = when (raw) {
                "resume" -> RESUME
                "complete" -> COMPLETE
                else -> CLOSE
            }
        }
    }
}
