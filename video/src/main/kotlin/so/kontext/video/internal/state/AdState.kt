package so.kontext.video.internal.state

import so.kontext.video.VideoAd.InvalidationReason
import so.kontext.video.VideoAdException
import so.kontext.video.internal.state.AdLifecycle.Phase

/**
 * One source of truth: the one place every fact of an ad's life lives (where it is, and what has happened to it),
 * and the only way those facts change. Everyone else asks it: every public flag of the ad (`isLoaded`,
 * `wasShown`, `hasEnded`, `isCompleted`, `invalidationReason`) reads from here, live, and no other part of the
 * SDK keeps its own copy. The data only: which moves are legal is [AdLifecycle]'s rule, which [moveTo] asks. No
 * Android, no side effects; `VideoAd` acts on the answer (the shell: the page, the screen, the beacons, the
 * listener).
 *
 * Beside the phase, the milestones ([isLoaded], [isCompleted], [hasImpression], [wasOnScreen], [failure]): each
 * recorded once and never undone, and none of them moves the phase.
 *
 * The ad's state is not its frame's phase (`FramePhase`, where the ad's web page is, moved by `FrameProtocol`),
 * and both are needed: the page's rules are checked before the ad hears a message at all; the page can end itself
 * (close, skip, error) before the ad knows; a page can be loaded for an ad only offered, never shown; and an ad can
 * be reserved (`show` accepted) while its page is not shown yet. They move together at fixed points: the page's
 * `ad.loaded` marks the ad loaded; the ad reaching the screen ([Phase.OnScreen]) shows the page (LOADED → SHOWN);
 * a page that ends itself hands the ad its event, and the ad ends; the ad's end, or its invalidation, hides the
 * page (→ ENDED).
 */
internal class AdState {
    /** Where the ad is now. It moves only through [moveTo]. */
    var phase: Phase = Phase.Offered
        private set

    /**
     * The creative reached the screen (the phase was [Phase.OnScreen]): `show` attached it and nothing ended it
     * during the attach. Recorded by [moveTo]; stays true after the end: an ad that was on screen fires its end
     * beacons.
     */
    var wasOnScreen: Boolean = false
        private set

    /** `load()` finished: the creative is rendered off screen. Set by [markLoaded]. */
    var isLoaded: Boolean = false
        private set

    /** The creative ran to its end: an overlay's duration elapsed, a linear video played through. Set by [markCompleted]. */
    var isCompleted: Boolean = false
        private set

    /** The `impression` beacon fired. Set by [markImpression]. */
    var hasImpression: Boolean = false
        private set

    /** Why a shown ad failed: the error of its `onAdFailed`, delivered before `onAdEnded(FAILED)`. Set by [markFailed]. */
    var failure: VideoAdException? = null
        private set

    /**
     * The ad's video is playing: from the page's `video.start` or `resume` to its `pause` or `complete`. Not a
     * milestone: it goes back and forth. The viewability meter counts time only while it holds. Set by
     * [markVideoPlaying].
     */
    var isVideoPlaying: Boolean = false
        private set

    /** `show` was accepted and not taken back (a container that refuses the ad returns it to offered). */
    val wasShown: Boolean get() = phase !is Phase.Offered && phase !is Phase.Invalidated

    /** The ad began to end, or has ended. */
    val hasEnded: Boolean get() = phase is Phase.Ending || phase is Phase.Ended

    /** Why the ad ended before it was shown, once it did. */
    val invalidationReason: InvalidationReason? get() = (phase as? Phase.Invalidated)?.reason

    /**
     * Moves to [next] when [AdLifecycle.canMove] allows it, and says whether it did; a refused move changes nothing.
     * The first move to [Phase.OnScreen] records [wasOnScreen].
     */
    fun moveTo(next: Phase): Boolean {
        if (!AdLifecycle.canMove(phase, next)) return false
        phase = next
        if (next == Phase.OnScreen) wasOnScreen = true
        return true
    }

    /** The page said `loaded`. Once; never undone. */
    fun markLoaded() {
        isLoaded = true
    }

    /** The creative ran to its end. Once; never undone. */
    fun markCompleted() {
        isCompleted = true
    }

    /** The page said its video plays, or stopped. */
    fun markVideoPlaying(isPlaying: Boolean) {
        isVideoPlaying = isPlaying
    }

    /** Records why the shown ad failed. The first failure stays; never undone. */
    fun markFailed(error: VideoAdException) {
        if (failure == null) failure = error
    }

    /** Records the impression, and says whether this was the first time: only the first fires the beacon. */
    fun markImpression(): Boolean {
        if (hasImpression) return false
        hasImpression = true
        return true
    }
}
