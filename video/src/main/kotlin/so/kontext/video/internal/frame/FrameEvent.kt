package so.kontext.video.internal.frame

import so.kontext.video.VideoAd

/** What the frame page told the bridge, accepted after the bridge's checks. */
internal sealed class FrameEvent {
    /** The creative is loaded and can be shown (the VAST `loaded` moment). */
    object Loaded : FrameEvent()

    /** The viewer clicked the ad. */
    class Click(val url: String, val fallbackUrl: String?, val area: VideoAd.Click.Area) : FrameEvent()

    /** The page needs a URL opened that is not an ad click (`purpose`: `adChoices`, ...). Fires no `click`. */
    class Open(val url: String, val purpose: String) : FrameEvent()

    /** The page's own end: the viewer's close control, or the page ending itself. */
    class Close(val reason: CloseReason) : FrameEvent()

    /** The viewer dismissed a video before it completed. */
    class Skip(val playheadMillis: Long) : FrameEvent()

    /** A moment of the ad video; the SDK fires the beacon of the same name. */
    class Video(val event: VideoEvent, val playheadMillis: Long) : FrameEvent()

    /**
     * A progress checkpoint the page reported, both values milliseconds into the ad video. The SDK fires every
     * unfired `progress` entry up to the greater of the offset and the playhead, so a checkpoint the page skipped
     * still fires.
     */
    class Progress(val offsetMillis: Long, val playheadMillis: Long) : FrameEvent()

    /** The video played to the end. The SDK fires `complete`. */
    class Complete(val playheadMillis: Long) : FrameEvent()

    /**
     * The creative failed to load or play, with the VAST error code: the page's own (`ad.error`), or the bridge's
     * when it decided itself (400 for a video, 500 otherwise, 405 after show).
     */
    class Error(val code: Int) : FrameEvent()

    /**
     * The video moments a page reports (`video.<name>`), named as their VAST beacons. Skip, progress and complete
     * carry more and are events of their own.
     */
    enum class VideoEvent(val wire: String) {
        START("start"), FIRST_QUARTILE("firstQuartile"), MIDPOINT("midpoint"), THIRD_QUARTILE("thirdQuartile"),
        PAUSE("pause"), RESUME("resume"), MUTE("mute"), UNMUTE("unmute");

        companion object {
            fun from(name: String): VideoEvent? = entries.firstOrNull { it.wire == name }
        }
    }

    /** Why the page ended itself. A reason the SDK does not know reads as close. */
    enum class CloseReason {
        /** The viewer tapped the close control (✕). */
        CLOSE,

        /** The viewer tapped Resume on a full-screen pause ad. */
        RESUME,

        /** The video completed and there is no end card: nobody closed anything. */
        COMPLETE,
        ;

        companion object {
            fun from(raw: String?): CloseReason = when (raw) {
                "resume" -> RESUME
                "complete" -> COMPLETE
                else -> CLOSE
            }
        }
    }
}
