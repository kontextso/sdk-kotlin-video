package so.kontext.video.ttsdk

import android.os.Handler
import android.os.Looper
import so.kontext.video.ContentPlayer

/**
 * TTSDK keeps the viewer's play/pause (the playback state) apart from buffering (its load state): playing here is
 * the playback state alone, so buffering counts as playing, as the SDK's contract says.
 *
 * A new vid on the engine, or the end of the video, is another play. Seeks are not reported by TTSDK; the SDK
 * infers them from the position.
 */
internal class EnginePlayer(
    private val engine: Engine,
    /** Runs a callback's work on the main thread; tests queue it to see what a late delivery does. */
    private val onMain: (() -> Unit) -> Unit = ::runOnMain,
) : ContentPlayer {
    private val listeners = mutableListOf<ContentPlayer.Listener>()
    private var wasPlaying = false
    private var videoId: String? = null

    /** Ends of the video heard while followed: a replay after the end is another play. */
    private var plays = 0

    /**
     * Which subscription to the engine is current: each `listen` starts a new one. Work posted by an earlier
     * subscription (a completion queued before the last listener left) is dropped, so it never reaches the
     * binding that followed.
     */
    private var subscription = 0

    /** The app's playback speed, for the current binding only; dropped with the last listener. */
    var speed: (() -> Float)? = null

    /** Someone follows the engine through this adapter now. */
    val isFollowed: Boolean get() = listeners.isNotEmpty()

    override val currentPositionMillis: Long get() = if (engine.isGone) 0 else engine.position.coerceAtLeast(0).toLong()

    override val isPlaying: Boolean get() = !engine.isGone && engine.playbackState == PLAYBACK_STATE_PLAYING

    override val playbackSpeed: Float? get() = speed?.let { runCatching { it() }.getOrNull() }

    /** The video now, and which play of it: `null` while the engine has no vid. */
    override val currentItem: Any? get() = if (engine.isGone) null else engine.videoId?.let { Play(it, plays) }

    /** One play of a video. */
    private data class Play(val videoId: String, val count: Int)

    override fun addListener(listener: ContentPlayer.Listener) {
        if (listeners.isEmpty() && !engine.isGone) {
            wasPlaying = isPlaying
            videoId = engine.videoId
            subscription += 1
            engine.listen(events(subscription))
        }
        listeners += listener
    }

    override fun removeListener(listener: ContentPlayer.Listener) {
        listeners -= listener
        if (listeners.isEmpty()) {
            engine.stopListening()
            subscription += 1
            speed = null
        }
    }

    /** What one subscription hears: each moment on the main thread, dropped there once the subscription is over. */
    private fun events(current: Int) = object : Engine.Events {
        override fun playbackStateChanged(playbackState: Int) = deliver(current) { playingChanged(playbackState == PLAYBACK_STATE_PLAYING) }

        override fun prepare() = deliver(current) { videoMayHaveChanged() }

        override fun completion() = deliver(current) {
            plays += 1
            listeners.toList().forEach { it.onContentChanged() }
            playingChanged(isPlaying)
        }
    }

    private fun deliver(current: Int, work: () -> Unit) = onMain { if (current == subscription) work() }

    /**
     * The callback's own state, as it was: a pause and a resume posted before the main thread runs are each
     * heard, where reading the engine then would find it playing twice.
     */
    private fun playingChanged(isPlayingNow: Boolean) {
        val isPlaying = isPlayingNow && !engine.isGone
        if (isPlaying == wasPlaying) return
        wasPlaying = isPlaying
        listeners.toList().forEach { it.onIsPlayingChanged(isPlaying) }
    }

    // The app gave the engine another video: what was on screen was over the one before.
    private fun videoMayHaveChanged() {
        val now = if (engine.isGone) null else engine.videoId
        if (now == videoId) return
        val before = videoId
        videoId = now
        if (before != null) listeners.toList().forEach { it.onContentChanged() }
    }
}

// TTSDK calls back on the engine's looper, the main one unless the app chose another.
private fun runOnMain(work: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) work() else Handler(Looper.getMainLooper()).post(work)
}
