package so.kontext.video.internal.content

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.measurement.OnScreenTimeClock
import so.kontext.video.internal.platform.AppActivities
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.InlineScreen

/**
 * An overlay lives only while the content plays, when the `VideoAds` tracks a player: a pause, the end of
 * the content, another player or a player that is gone ends it as INTERRUPTED; its duration counts
 * milliseconds of video; and it cannot be shown over paused content. Without a tracked player it runs on
 * its own time (the clock's duration). A pause while the app is in the background (apps pause their player in
 * `onStop`) only freezes it, as the background freezes its clock: back in front, it goes on if the content
 * plays again within [returnGraceMillis], and ends as INTERRUPTED otherwise. A pause while the in-app browser of
 * its own click is open is the same: the return is the browser's close. Another Activity of the
 * app over it is not the background: it ends the overlay (see `InlineScreen.followHoldingActivity`), and so does a
 * pause there.
 */
internal class OverlayRule(private val ad: VideoAd) : ContentRule() {
    /** The overlay's duration in milliseconds of video, once shown over a tracked player. */
    var contentClock: ContentTimeClock? = null
        private set

    /**
     * The content paused while the overlay was away (the app in the background, or on its way there): it
     * stays, frozen, and [returnCheck] decides once the app is back.
     */
    var isContentPausedAway = false
        private set

    /** The check `returnGraceMillis` after the return, for an overlay whose content paused while away. */
    var returnCheck: Job? = null
        private set

    override val wallClockDurationMillis: Long? get() = if (ad.isTrackingContent) null else ad.durationMillis

    /**
     * The overlay is away: the app is in the background, or on its way there (the Activity holding it paused
     * and no Activity of the app resumed: a player paused in `onStop`, or at the lifecycle's ON_STOP, pauses
     * before the SDK hears the stop), or the in-app browser of its own click is open (a player paused in
     * `onAdClicked` pauses while the Activity is still resumed). Another Activity of the app over it is
     * resumed, so a pause then is no pause away.
     */
    private val isAway: Boolean
        get() {
            if (!Environment.isAppInForeground() || ad.screen?.inAppBrowser?.isOpen == true) return true
            val holder = (ad.screen as? InlineScreen)?.holdingActivity?.get() ?: return false
            return AppActivities.isResumed(holder) == false && !AppActivities.hasResumedActivity
        }

    override fun refusesShow(): Boolean {
        if (!ad.isTrackingContent || ad.contentPlayback?.isPlaying != false) return false
        Logger.warning { "ad ${ad.id.take(8)}: cannot show the overlay, the content is paused" }
        return true
    }

    /**
     * Its duration counts from the playhead now, and again from it whenever its window comes back. Shown with
     * no player tracked, it says so: its duration then is time on screen, which an app that calls `track`
     * after `show` may not expect.
     */
    override fun started(clock: OnScreenTimeClock?) {
        if (!ad.isTrackingContent) Logger.warning { "overlay ${ad.id.take(8)} shown before track(): its duration counts on-screen time, not video time" }
        val duration = ad.durationMillis ?: return
        val playback = ad.contentPlayback ?: return
        if (!ad.isTrackingContent) return
        contentClock = ContentTimeClock(duration, playback.playheadMillis)
        clock?.onActivate = { contentClock?.resync(playback.playheadMillis) }
    }

    /**
     * A pause while the overlay's view is being attached ends it before any impression; one while it is away
     * waits for the return (see [waitsOutPause]).
     */
    override fun contentDidChange(change: ContentPlayback.Change) {
        val playback = ad.contentPlayback ?: return
        if (!ad.isTrackingContent || !ad.wasShown || ad.hasEnded) return
        if (change == ContentPlayback.Change.Seeked) {
            contentClock?.resync(playback.playheadMillis)
            return
        }
        if (change == ContentPlayback.Change.ContentChanged) {
            Logger.info { "ad ${ad.id.take(8)}: the content ended or changed; the overlay ends" }
            ad.end(EndReason.INTERRUPTED)
            return
        }
        if (!contentPlays(change, playback)) return
        val progress = contentClock ?: return
        if (change != ContentPlayback.Change.Tick) return
        if (progress.advance(playback.playheadMillis, isCounting = ad.onScreenClock?.isCounting == true, speed = playback.playbackSpeed)) {
            Logger.debug { "ad ${ad.id.take(8)}: overlay duration of ${progress.durationMillis} ms of video elapsed" }
            ad.state.markCompleted()
            ad.end(EndReason.COMPLETED)
        }
    }

    override fun endIfContentStopped() {
        if (!ad.wasShown || ad.hasEnded || !ad.isTrackingContent) return
        if (ad.contentPlayback?.isPlaying != false) return
        // Away, the overlay only freezes; back in front, another ad's show needs the screen, the grace or not.
        if (isAway) {
            isContentPausedAway = true
            return
        }
        Logger.info { "ad ${ad.id.take(8)}: the content paused; the overlay ends" }
        ad.end(EndReason.INTERRUPTED)
    }

    /** An overlay whose content paused while away checks it again after the grace. */
    override fun holdingActivityResumed() {
        if (isContentPausedAway) checkAfterReturn()
    }

    override fun cancel() {
        returnCheck?.cancel()
        returnCheck = null
    }

    /**
     * Whether the overlay's content plays. Paused, or its player gone, the overlay ends, unless the pause came
     * while it is away ([waitsOutPause]); playing, in the background or back in front, a pause seen away is over.
     */
    private fun contentPlays(change: ContentPlayback.Change, playback: ContentPlayback): Boolean {
        if (change is ContentPlayback.Change.PlayingChanged && change.isPlaying == false || !playback.isPlaying) {
            if (waitsOutPause()) return false
            Logger.info { "ad ${ad.id.take(8)}: the content paused or its player is gone; the overlay ends" }
            ad.end(EndReason.INTERRUPTED)
            return false
        }
        isContentPausedAway = false
        returnCheck?.cancel()
        returnCheck = null
        return true
    }

    /**
     * A pause the overlay does not end on now: one seen while away, which freezes it, or any while it waits for
     * the check after the return. Back in front with no check under way (the app came back without the holding
     * Activity resuming, which arms it), the check starts here, so a pause seen away never keeps it forever.
     */
    private fun waitsOutPause(): Boolean {
        if (isAway) {
            if (!isContentPausedAway) Logger.info { "ad ${ad.id.take(8)}: the content paused while away (the background, or the click's browser open); the overlay waits for the return" }
            isContentPausedAway = true
            return true
        }
        if (!isContentPausedAway) return false
        if (returnCheck == null) checkAfterReturn()
        return true
    }

    /**
     * [returnGraceMillis] after the return, the content decides: playing, the overlay goes on where it
     * stopped; still paused, it ends as INTERRUPTED, as on any pause. Away again by then, the next return checks.
     */
    private fun checkAfterReturn() {
        returnCheck?.cancel()
        returnCheck = Environment.scope.launch {
            delay(returnGraceMillis)
            returnCheck = null
            if (ad.hasEnded || !isContentPausedAway || isAway) return@launch
            isContentPausedAway = false
            if (!ad.isTrackingContent || ad.contentPlayback?.isPlaying != false) return@launch
            Logger.info { "ad ${ad.id.take(8)}: the content is still paused after the return; the overlay ends" }
            ad.end(EndReason.INTERRUPTED)
        }
    }

    companion object {
        /**
         * How long after the app's return an overlay whose content paused in the background waits for the
         * content to play again before it ends. Tests shorten it.
         */
        var returnGraceMillis = 1_000L
    }
}
