package so.kontext.video.internal.playback

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.internal.measurement.ActiveTimeClock
import so.kontext.video.internal.support.ActivityTracker
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.Logger

/**
 * How an ad on screen follows the content under it: the one place where the three formats differ in that.
 *
 * Why: an overlay lives while the content plays, a pause ad while it is paused, a linear ad covers the content
 * and ignores it. Kept in one class per format, `VideoAd` and `VideoAds` ask "the rule" and never the format.
 *
 * How: `VideoAd` makes its rule once ([of], by format) and calls it at fixed moments:
 *
 * ```
 * show ──► refusesShow()        overlay over paused content: ContentPaused, the ad stays ready
 *      ──► checksEpisode        overlay and pause: an ad of another episode ends as ContentChanged
 * on screen ──► wallClockDurationMillis   the clock's duration (overlay with no tracked player only)
 *           ──► started(clock)            overlay: counts video time from the playhead now
 * player ──► contentDidChange(change)     a pause, a play, a seek, a tick, another item (from VideoAds)
 *        ──► endIfContentStopped()        before another ad's show, and once the view is attached
 * Activity resumed ──► holdingActivityResumed()   overlay: the check after a return from the background
 * end ──► cancel()                         the pending return check goes
 * ```
 *
 * What each rule decides, by the player's change and the rule's state, for an ad on screen over a tracked
 * player (without one, nothing below applies: an overlay runs on its clock, a pause ad until the viewer or the
 * app ends it). end = ends as INTERRUPTED; freeze = stays, its time stopped; wait = stays, decided later.
 *
 * ```
 * OverlayRule          in front, playing      away (frozen)          back, within the grace
 * paused / gone        end                    freeze                 wait (the check runs)
 * plays                go on                  go on, unfrozen        go on, check cancelled
 * tick                 count video time;      as paused or plays     as paused or plays
 *                      COMPLETED at duration
 * seek (jumped)        recount from here      recount from here      recount from here
 * content changed      end                    end                    end
 * another show, paused end                    freeze                 end
 * the grace is over    -                      -                      still paused: end; else go on
 *
 * PauseRule            not yet seen paused    seen paused
 * paused (or a tick)   note it                note it
 * plays (or a tick)    ignore                 end
 * content changed      end                    end
 * seek (jumped)        ignore                 ignore
 *
 * NoContentRule        ignores every change (a linear ad covers the content)
 * ```
 *
 * Whether a player is tracked is read at each call, never fixed at show: `VideoAds.track` may come later.
 */
internal sealed class ContentRule {
    /**
     * Whether an ad of another episode ends before show (`ContentChanged`). A linear ad covers the content: not
     * checked.
     */
    open val checksEpisode: Boolean get() = true

    /** The clock's duration, wall time on screen; `null` when the clock only counts the active time. */
    open val wallClockDurationMillis: Long? get() = null

    /** Whether `show` refuses the ad because of the content now (`ContentPaused`). Reads the player. */
    open fun refusesShow(): Boolean = false

    /** The ad is on screen, its [clock] (inline formats) running. */
    open fun started(clock: ActiveTimeClock?) = Unit

    /** The tracked player changed or a tick came. */
    open fun contentDidChange(change: ContentPlayback.Change) = Unit

    /**
     * An ad over content that no longer fits it ends: before another ad's `show`, so an overlay shown right after
     * the content plays again finds the screen free of the pause ad (a player without a listener says so only on
     * the next tick); and, for an overlay, once its own view is attached, for a pause no callback reported while
     * that ran.
     */
    open fun endIfContentStopped() = Unit

    /** The Activity holding the ad resumed. */
    open fun holdingActivityResumed() = Unit

    /** The ad ends: what the rule still has pending goes. */
    open fun cancel() = Unit

    /**
     * An overlay lives only while the content plays, when the `VideoAds` tracks a player: a pause, the end of
     * the content, another player or a player that is gone ends it as INTERRUPTED; its duration counts
     * milliseconds of video; and it cannot be shown over paused content. Without a tracked player it runs on
     * its own time (the clock's duration). A pause while the app is in the background (apps pause their player in
     * `onStop`) only freezes it, as the background freezes its clock: back in front, it goes on if the content
     * plays again within `VideoAd.returnGraceMillis`, and ends as INTERRUPTED otherwise. Another screen of the
     * app over it is not the background: it ends the overlay (see `VideoAd.followHoldingActivity`), and so does a
     * pause there.
     */
    class OverlayRule(private val ad: VideoAd) : ContentRule() {
        /** The overlay's duration in milliseconds of video, once shown over a tracked player. */
        var contentProgress: ContentProgress? = null
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

        override val wallClockDurationMillis: Long? get() = if (isTracking) null else ad.durationMillis

        /** The ad's `VideoAds` tracks a player, now. */
        private val isTracking: Boolean get() = ad.owner.get()?.playback?.isTracking == true

        /**
         * The overlay is away: the app is in the background, or on its way there (the Activity holding it paused
         * and no Activity of the app resumed: a player paused in `onStop`, or at the lifecycle's ON_STOP, pauses
         * before the SDK hears the stop). Another Activity of the app over it is resumed, so a pause then is no
         * pause away.
         */
        private val isAway: Boolean
            get() {
                if (!Environment.isAppInForeground()) return true
                val holder = ad.holdingActivity?.get() ?: return false
                return ActivityTracker.isResumed(holder) == false && !ActivityTracker.hasResumedActivity
            }

        override fun refusesShow(): Boolean {
            if (!isTracking || ad.owner.get()?.playback?.isPlaying != false) return false
            Logger.warning { "ad ${ad.id.take(8)}: cannot show the overlay, the content is paused" }
            return true
        }

        /** Its duration counts from the playhead now, and again from it whenever its window comes back. */
        override fun started(clock: ActiveTimeClock?) {
            val duration = ad.durationMillis ?: return
            val playback = ad.owner.get()?.playback ?: return
            if (!isTracking) return
            contentProgress = ContentProgress(duration, playback.playheadMillis)
            clock?.onActivate = { contentProgress?.resync(playback.playheadMillis) }
        }

        /**
         * A pause while the overlay's view is being attached ends it before any impression; one while it is away
         * waits for the return (see [waitsOutPause]).
         */
        override fun contentDidChange(change: ContentPlayback.Change) {
            val playback = ad.owner.get()?.playback ?: return
            if (!isTracking || !ad.wasShown || ad.hasEnded) return
            if (change == ContentPlayback.Change.Jumped) {
                contentProgress?.resync(playback.playheadMillis)
                return
            }
            if (change == ContentPlayback.Change.ContentChanged) {
                Logger.info { "ad ${ad.id.take(8)}: the content ended or changed; the overlay ends" }
                ad.end(EndReason.INTERRUPTED)
                return
            }
            if (!contentPlays(change, playback)) return
            val progress = contentProgress ?: return
            if (change != ContentPlayback.Change.Tick) return
            if (progress.advance(playback.playheadMillis, isCounting = ad.clock?.isCounting == true, speed = playback.playbackSpeed)) {
                Logger.debug { "ad ${ad.id.take(8)}: overlay duration of ${progress.durationMillis} ms of video elapsed" }
                ad.state.markCompleted()
                ad.end(EndReason.COMPLETED)
            }
        }

        override fun endIfContentStopped() {
            if (!ad.wasShown || ad.hasEnded || !isTracking) return
            if (ad.owner.get()?.playback?.isPlaying != false) return
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
            if (change is ContentPlayback.Change.Playback && change.isPlaying == false || !playback.isPlaying) {
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
                if (!isContentPausedAway) Logger.info { "ad ${ad.id.take(8)}: the content paused in the background; the overlay waits for the return" }
                isContentPausedAway = true
                return true
            }
            if (!isContentPausedAway) return false
            if (returnCheck == null) checkAfterReturn()
            return true
        }

        /**
         * `VideoAd.returnGraceMillis` after the return, the content decides: playing, the overlay goes on where it
         * stopped; still paused, it ends as INTERRUPTED, as on any pause. Away again by then, the next return checks.
         */
        private fun checkAfterReturn() {
            returnCheck?.cancel()
            returnCheck = Environment.scope.launch {
                delay(VideoAd.returnGraceMillis)
                returnCheck = null
                if (ad.hasEnded || !isContentPausedAway || isAway) return@launch
                isContentPausedAway = false
                if (!isTracking || ad.owner.get()?.playback?.isPlaying != false) return@launch
                Logger.info { "ad ${ad.id.take(8)}: the content is still paused after the return; the overlay ends" }
                ad.end(EndReason.INTERRUPTED)
            }
        }
    }

    /**
     * A pause ad lives only while the content is paused, when the `VideoAds` tracks a player: the content playing
     * again ends it as INTERRUPTED, wherever the viewer resumed (the media notification, the lock screen, a
     * headset, the picture-in-picture window); RESUMED stays the viewer's tap on the ad's own Resume. Only a
     * return to playing after a pause seen while it is on screen counts: a pause ad shown a moment before the app
     * pauses the player stays. The background needs no rule of its own here: an app pausing its player in
     * `onStop` changes nothing for content already paused, and content playing again, on the return or in the
     * background, ends the pause ad as it should. Its clock never has a duration.
     */
    class PauseRule(private val ad: VideoAd) : ContentRule() {
        /** The pause ad saw the content paused while on screen: the content playing again ends it. */
        var wasContentPaused = false
            private set

        /** The ad's `VideoAds` tracks a player, now. */
        private val isTracking: Boolean get() = ad.owner.get()?.playback?.isTracking == true

        /** It notes whether the content is paused now, so it ends once it plays after a pause. */
        override fun started(clock: ActiveTimeClock?) {
            if (!isTracking) return
            wasContentPaused = ad.owner.get()?.playback?.isPlaying == false
        }

        /**
         * A pause ad ends when the tracked content plays again after it was seen paused (a listener's call or a
         * tick), and at once when its episode is gone from the player: another VideoAds took the player (the next
         * page of a feed sharing one player) or it plays another item. Shown on, it would hold the app's one slot
         * over content it is not for.
         */
        override fun contentDidChange(change: ContentPlayback.Change) {
            val playback = ad.owner.get()?.playback ?: return
            if (!isTracking || !ad.wasShown || ad.hasEnded) return
            val isPlaying = when (change) {
                ContentPlayback.Change.ContentChanged -> {
                    Logger.info { "ad ${ad.id.take(8)}: the content changed or another VideoAds took the player; the pause ad ends" }
                    return ad.end(EndReason.INTERRUPTED)
                }
                is ContentPlayback.Change.Playback -> change.isPlaying ?: playback.isPlaying
                ContentPlayback.Change.Tick -> playback.isPlaying
                else -> return
            }
            endIfContentResumed(isPlaying)
        }

        override fun endIfContentStopped() {
            val playback = ad.owner.get()?.playback ?: return
            if (!isTracking || !ad.state.wasOnScreen || ad.hasEnded) return
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

    /** A linear ad covers the content in its own screen: the content changes nothing for it, nor its episode. */
    object NoContentRule : ContentRule() {
        override val checksEpisode: Boolean get() = false
    }

    companion object {
        /** The rule of [ad]'s format. */
        fun of(ad: VideoAd): ContentRule = when (ad.format) {
            AdFormat.OVERLAY -> OverlayRule(ad)
            AdFormat.PAUSE -> PauseRule(ad)
            AdFormat.LINEAR -> NoContentRule
        }
    }
}
