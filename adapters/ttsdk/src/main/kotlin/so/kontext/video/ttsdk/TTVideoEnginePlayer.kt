@file:JvmName("TTSDK")

package so.kontext.video.ttsdk

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.ss.ttvideoengine.TTVideoEngine
import com.ss.ttvideoengine.VideoEngineCallback
import so.kontext.video.VideoAds
import so.kontext.video.VideoAdsPlayer
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Follows a TTSDK [engine] (1.40 or later): `ads.track(engine)`. Held weakly; the SDK listens with
 * `addVideoEngineCallback`, so the app's own callback stays as it is.
 *
 * In a feed, where each page has its own engine (or takes a pre-rendered one), track the page's engine with that
 * page's `VideoAds` once it has its source. TTSDK has no getter for the playback speed: pass [playbackSpeed] when
 * the app plays faster or slower than 1×; without it the SDK accepts up to about 4×.
 *
 * Show an episode's ads only in that episode: the same engine tracked by another `VideoAds` is let go of here,
 * and this one's overlay and pause ads then throw `Invalidated(ContentChanged)` at `show`; so do they once the
 * engine plays another video (another `getVideoID()`), or plays this one again after its end.
 */
@MainThread
@JvmOverloads
public fun VideoAds.track(engine: TTVideoEngine, playbackSpeed: (() -> Float)? = null) {
    // One adapter per engine: the SDK tells an engine tracked by two `VideoAds` by the adapter it is given.
    val adapter = adapters.getOrPut(engine) { EnginePlayer(TTEngine(engine)) }
    adapter.speed = playbackSpeed
    track(adapter)
}

/** The adapter of each engine tracked, for as long as the engine lives. The adapter holds it weakly. */
private val adapters = WeakHashMap<TTVideoEngine, EnginePlayer>()

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
        fun playbackStateChanged()

        fun prepare()

        fun completion()
    }
}

/** A TTSDK engine, held weakly, heard through a callback added beside the app's own. */
internal class TTEngine(engine: TTVideoEngine) : Engine {
    private val engine = WeakReference(engine)
    private var callback: VideoEngineCallback? = null

    private fun live(): TTVideoEngine? = engine.get()?.takeUnless { it.isReleased }

    override val isGone: Boolean get() = live() == null
    override val position: Int get() = live()?.currentPlaybackTime ?: 0
    override val playbackState: Int get() = live()?.playbackState ?: PLAYBACK_STATE_STOPPED
    override val videoId: String? get() = live()?.videoID

    override fun listen(events: Engine.Events) {
        val callback = object : VideoEngineCallback {
            override fun onPlaybackStateChanged(engine: TTVideoEngine, playbackState: Int) = events.playbackStateChanged()

            override fun onPrepare(engine: TTVideoEngine) = events.prepare()

            override fun onCompletion(engine: TTVideoEngine) = events.completion()
        }
        this.callback = callback
        live()?.addVideoEngineCallback(callback)
    }

    override fun stopListening() {
        callback?.let { engine.get()?.removeVideoEngineCallback(it) }
        callback = null
    }
}

/**
 * TTSDK keeps the viewer's play/pause (the playback state) apart from buffering (its load state): playing here is
 * the playback state alone, so buffering counts as playing, as the SDK's contract says.
 *
 * A new vid on the engine, or the end of the video, is another play. Seeks are not reported by TTSDK; the SDK
 * infers them from the position.
 */
internal class EnginePlayer(private val engine: Engine) : VideoAdsPlayer {
    private val listeners = mutableListOf<VideoAdsPlayer.Listener>()
    private var lastPlaying = false
    private var videoId: String? = null

    /** Ends of the video heard while followed: a replay after the end is another play. */
    private var plays = 0

    var speed: (() -> Float)? = null

    private val events = object : Engine.Events {
        override fun playbackStateChanged() = onMain { playingMayHaveChanged() }

        override fun prepare() = onMain { videoMayHaveChanged() }

        override fun completion() = onMain {
            plays += 1
            listeners.toList().forEach { it.onContentChanged() }
            playingMayHaveChanged()
        }
    }

    override val currentPositionMillis: Long get() = if (engine.isGone) 0 else engine.position.coerceAtLeast(0).toLong()

    override val isPlaying: Boolean get() = !engine.isGone && engine.playbackState == PLAYBACK_STATE_PLAYING

    override val playbackSpeed: Float? get() = speed?.let { runCatching { it() }.getOrNull() }

    /** The video now, and which play of it: `null` while the engine has no vid. */
    override val currentItem: Any? get() = if (engine.isGone) null else engine.videoId?.let { Play(it, plays) }

    /** One play of a video. */
    private data class Play(val videoId: String, val count: Int)

    override fun addListener(listener: VideoAdsPlayer.Listener) {
        if (listeners.isEmpty() && !engine.isGone) {
            lastPlaying = isPlaying
            videoId = engine.videoId
            engine.listen(events)
        }
        listeners += listener
    }

    override fun removeListener(listener: VideoAdsPlayer.Listener) {
        listeners -= listener
        if (listeners.isEmpty()) engine.stopListening()
    }

    private fun playingMayHaveChanged() {
        val playing = isPlaying
        if (playing == lastPlaying) return
        lastPlaying = playing
        listeners.toList().forEach { it.onIsPlayingChanged(playing) }
    }

    // The app gave the engine another video: what was on screen was over the one before.
    private fun videoMayHaveChanged() {
        val now = if (engine.isGone) null else engine.videoId
        if (now == videoId) return
        val before = videoId
        videoId = now
        if (before != null) listeners.toList().forEach { it.onContentChanged() }
    }

    // TTSDK calls back on the engine's looper, the main one unless the app chose another.
    private fun onMain(work: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) work() else Handler(Looper.getMainLooper()).post(work)
    }
}

/** `TTVideoEngine.PLAYBACK_STATE_PLAYING`: the viewer has not paused or stopped it. */
private const val PLAYBACK_STATE_PLAYING = 1

/** `TTVideoEngine.PLAYBACK_STATE_STOPPED`. */
private const val PLAYBACK_STATE_STOPPED = 0
