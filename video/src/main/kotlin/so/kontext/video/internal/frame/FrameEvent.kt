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

        companion object {
            fun from(raw: String?): CloseReason = when (raw) {
                "resume" -> RESUME
                "complete" -> COMPLETE
                else -> CLOSE
            }
        }
    }
}
