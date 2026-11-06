package so.kontext.video.ttsdk

/** What the adapter reads from an engine, and the moments it hears: TTSDK's, or a test's. */
internal interface Engine {
    /** Gone: released, or collected. */
    val isGone: Boolean

    /** Milliseconds; negative before the engine is prepared. */
    val position: Int

    /** `TTVideoEngine.getPlaybackState()`. */
    val playbackState: Int

    /** The vid the app set, `null` for a plain URL without one. */
    val videoId: String?

    fun listen(events: Events)

    fun stopListening()

    interface Events {
        /** [playbackState]: the state the callback carried, not the engine's by the time the main thread reads it. */
        fun playbackStateChanged(playbackState: Int)

        fun prepare()

        fun completion()
    }
}

/** `TTVideoEngine.PLAYBACK_STATE_PLAYING`: the viewer has not paused or stopped it. */
internal const val PLAYBACK_STATE_PLAYING = 1

/** `TTVideoEngine.PLAYBACK_STATE_STOPPED`. */
internal const val PLAYBACK_STATE_STOPPED = 0
