package so.kontext.video.internal.content

import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.measurement.ActiveTimeClock

/**
 * A pause ad lives only while the content is paused, when the `VideoAds` tracks a player: the content playing
 * again ends it as INTERRUPTED, wherever the viewer resumed (the media notification, the lock screen, a
 * headset, the picture-in-picture window); RESUMED stays the viewer's tap on the ad's own Resume. Only a
 * return to playing after a pause seen while it is on screen counts: a pause ad shown a moment before the app
 * pauses the player stays. The background needs no rule of its own here: an app pausing its player in
 * `onStop` changes nothing for content already paused, and content playing again, on the return or in the
 * background, ends the pause ad as it should. Its clock never has a duration.
 */
internal class PauseRule(private val ad: VideoAd) : ContentRule() {
    /** The pause ad saw the content paused while on screen: the content playing again ends it. */
    var wasContentPaused = false
        private set

    /** It notes whether the content is paused now, so it ends once it plays after a pause. */
    override fun started(clock: ActiveTimeClock?) {
        if (!ad.isTrackingContent) return
        wasContentPaused = ad.contentPlayback?.isPlaying == false
    }

    /**
     * A pause ad ends when the tracked content plays again after it was seen paused (a listener's call or a
     * tick), and at once when its episode is gone from the player: another VideoAds took the player (the next
     * page of a feed sharing one player) or it plays another item. Shown on, it would hold the app's one slot
     * over content it is not for.
     */
    override fun contentDidChange(change: ContentPlayback.Change) {
        val playback = ad.contentPlayback ?: return
        if (!ad.isTrackingContent || !ad.wasShown || ad.hasEnded) return
        val isPlaying = when (change) {
            ContentPlayback.Change.ContentChanged -> {
                Logger.info { "ad ${ad.id.take(8)}: the content changed or another VideoAds took the player; the pause ad ends" }
                return ad.end(EndReason.INTERRUPTED)
            }
            is ContentPlayback.Change.PlayingChanged -> change.isPlaying ?: playback.isPlaying
            ContentPlayback.Change.Tick -> playback.isPlaying
            else -> return
        }
        endIfContentResumed(isPlaying)
    }

    override fun endIfContentStopped() {
        val playback = ad.contentPlayback ?: return
        if (!ad.isTrackingContent || !ad.state.wasOnScreen || ad.hasEnded) return
        endIfContentResumed(playback.isPlaying)
    }

    /**
     * Content seen playing after it was seen paused while the pause ad is on screen ends it. A listener's call
     * to playing counts only after a pause too: a resume queued behind the pause that ended an overlay,
     * delivered once that overlay's `onAdEnded` showed this pause ad over playing content, is no resume of this
     * one.
     */
    private fun endIfContentResumed(isPlaying: Boolean) {
        if (!isPlaying) {
            wasContentPaused = true
            return
        }
        if (!wasContentPaused) return
        Logger.info { "ad ${ad.id.take(8)}: the content plays again; the pause ad ends" }
        ad.end(EndReason.INTERRUPTED)
    }
}
