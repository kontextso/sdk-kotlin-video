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
         * What this close does to an ad (see `closeByPage` for the table): the beacon it fires, if any, how the ad
         * ends, and whether it completes the ad. A linear ad closed before its video completed is a skip.
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
