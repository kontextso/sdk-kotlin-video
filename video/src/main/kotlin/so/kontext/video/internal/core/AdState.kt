package so.kontext.video.internal.core

import so.kontext.video.VideoAd.InvalidationReason
import so.kontext.video.VideoAdException
import so.kontext.video.internal.core.AdLifecycle.Phase

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

    /** `show` was accepted once. */
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
