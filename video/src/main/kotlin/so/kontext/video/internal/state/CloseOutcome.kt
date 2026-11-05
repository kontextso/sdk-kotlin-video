package so.kontext.video.internal.state

import so.kontext.video.VideoAd
import so.kontext.video.internal.frame.FrameEvent

/**
 * What a page's `ad.close` does to an ad: the beacon it fires, if any, how the ad ends, and whether it completes it.
 * [reason] is the close reason the page gave: CLOSE (the viewer's close button), RESUME (a pause ad's Resume button) or
 * COMPLETE (the page ended itself after the video, with no end card). [isLinear]: a linear (video) ad. [isCompleted]:
 * the video already reached its end, which the SDK knows from the page's `complete` event, never from a close.
 *
 * The rows are checked top to bottom; the first that matches wins:
 *
 * ```
 *   #  when                          beacon  ends as       meaning
 *   1  linear and not completed      skip    SKIPPED       the viewer left a video ad before it finished: a skip for
 *      (any reason)                                        tracking, by the close button or anything else
 *   2  reason COMPLETE               none    COMPLETED     the ad ended by itself after its video ran out (it also
 *                                            (completes)   marks the ad completed); nobody closed it, so no close
 *   3  reason RESUME                 close   RESUMED       a pause ad's Resume button: closed to continue watching
 *   4  anything else (CLOSE)         close   CLOSED        closed with the close button: an overlay, a pause ad, or a
 *                                                          linear ad's end card after the video finished
 * ```
 *
 * Examples:
 * - A linear ad closed at second 10 of 30: row 1, `skip`, SKIPPED.
 * - A linear ad, video finished, the end card's close button: not row 1 (completed); reason CLOSE, row 4: `close`, CLOSED.
 * - A linear ad, video finished, no end card: the page sends COMPLETE, row 2: no beacon, COMPLETED.
 * - An overlay's close button: not linear, row 4: `close`, CLOSED.
 * - A pause ad's Resume: row 3: `close`, RESUMED.
 *
 * Row 1 comes first on purpose: a linear ad whose page says COMPLETE without the SDK having seen the video's
 * `complete` is a skip. The SDK trusts its own record of what was watched, which is what advertisers pay for.
 */
internal fun closeOutcome(reason: FrameEvent.CloseReason, isLinear: Boolean, isCompleted: Boolean): CloseOutcome = when {
    isLinear && !isCompleted -> CloseOutcome("skip", VideoAd.EndReason.SKIPPED)
    reason == FrameEvent.CloseReason.COMPLETE -> CloseOutcome(null, VideoAd.EndReason.COMPLETED, completesAd = true)
    reason == FrameEvent.CloseReason.RESUME -> CloseOutcome("close", VideoAd.EndReason.RESUMED)
    else -> CloseOutcome("close", VideoAd.EndReason.CLOSED)
}

/** A page's close as the ad carries it out: [beacon] fired, then the end with [endReason]. */
internal data class CloseOutcome(val beacon: String?, val endReason: VideoAd.EndReason, val completesAd: Boolean = false)
