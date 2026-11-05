package so.kontext.video.internal.content

import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.internal.measurement.OnScreenTimeClock

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
    open fun started(clock: OnScreenTimeClock?) = Unit

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

/** The playback of the ad's `VideoAds`, while it has one: what the rules read the content from. */
internal val VideoAd.contentPlayback: ContentPlayback? get() = owner.get()?.playback

/** The ad's `VideoAds` tracks a player, now. */
internal val VideoAd.isTrackingContent: Boolean get() = contentPlayback?.isTracking == true
