@file:JvmName("TTSDK")

package so.kontext.video.ttsdk

import android.os.Looper
import androidx.annotation.MainThread
import com.ss.ttvideoengine.TTVideoEngine
import so.kontext.video.VideoAds
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
    // Off the main thread or destroyed, the shared adapter is not touched: `track` refuses (and says why) with
    // a throwaway one, and the speed of the VideoAds that does track the engine stays.
    if (Looper.myLooper() != Looper.getMainLooper() || isDestroyed) {
        track(EnginePlayer(TTEngine(engine)))
        return
    }
    // One adapter per engine: the SDK tells an engine tracked by two `VideoAds` by the adapter it is given.
    // The speed belongs to the binding this call made: set once the adapter is followed and no newer `track` of
    // the engine won meanwhile, dropped with its last listener (an Activity the closure holds is not kept by the
    // shared registry).
    adapters.getOrPut(engine) { EnginePlayer(TTEngine(engine)) }.bind(playbackSpeed) { track(it) }
}

/** The adapter of each engine tracked, for as long as the engine lives. The adapter holds it weakly. */
private val adapters = WeakHashMap<TTVideoEngine, EnginePlayer>()
