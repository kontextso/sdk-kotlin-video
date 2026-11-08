package so.kontext.video.txplayer

/** What the adapter reads from a player: a `TXVodPlayer`'s, or a test's. */
internal interface Vod {
    /** Collected: the app let go of the player. */
    val isGone: Boolean

    /** `TXVodPlayer.getCurrentPlaybackTime()`, in seconds. */
    val positionSeconds: Float

    /** `TXVodPlayer.isPlaying()`. */
    val isPlaying: Boolean
}

/**
 * The play events of `ITXVodPlayListener.onPlayEvent` the adapter acts on. The values are the same in
 * `TXLiveConstants.PLAY_EVT_*` and `TXVodConstants.VOD_PLAY_EVT_*`; written here, so the adapter does not depend on
 * which of the two classes an SDK edition has.
 */
internal object PlayEvent {
    /** Playback began (the first frame; a resume may send it again). */
    const val PLAY_BEGIN = 2004

    /** The video played to its end, without a loop. */
    const val PLAY_END = 2006

    /** Loading ended and the video plays on. */
    const val LOADING_END = 2014

    /** A seek finished (10.3 and later). */
    const val SEEK_COMPLETE = 2019

    /** One round of a loop (`setLoop(true)`) ended, and the next began; `PLAY_END` is not sent then. */
    const val LOOP_ONCE_COMPLETE = 6001
}
