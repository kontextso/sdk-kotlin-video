package so.kontext.video.ttsdk

import com.ss.ttvideoengine.TTVideoEngine
import com.ss.ttvideoengine.VideoEngineCallback
import java.lang.ref.WeakReference

/** A TTSDK engine, held weakly, heard through a callback added beside the app's own. */
internal class TTEngine(engine: TTVideoEngine) : Engine {
    private val engine = WeakReference(engine)
    private var callback: VideoEngineCallback? = null

    override val isGone: Boolean get() = live() == null
    override val position: Int get() = live()?.currentPlaybackTime ?: 0
    override val playbackState: Int get() = live()?.playbackState ?: PLAYBACK_STATE_STOPPED
    override val videoId: String? get() = live()?.videoID

    private fun live(): TTVideoEngine? = engine.get()?.takeUnless { it.isReleased }

    override fun listen(events: Engine.Events) {
        val callback = object : VideoEngineCallback {
            override fun onPlaybackStateChanged(engine: TTVideoEngine, playbackState: Int) = events.playbackStateChanged(playbackState)

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
