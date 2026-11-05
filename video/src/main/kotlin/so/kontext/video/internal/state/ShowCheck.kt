package so.kontext.video.internal.state

import so.kontext.video.VideoAdException

/**
 * Whether `show` may go on, as a rule over plain facts: the first reason it may not, or `null`. The checks come in
 * this order, the first that fails wins:
 *
 * ```
 *   #  fact                                     throws
 *   1  shown already                            AlreadyShown
 *   2  not usable: destroyed, disabled,         its error (Destroyed, Disabled, Invalidated)
 *      invalidated, expired, episode changed
 *   3  another ad on screen in the app          AnotherAdShowing
 *   4  not loaded                               NotLoaded
 *   5  an overlay over paused content           ContentPaused
 * ```
 *
 * `VideoAd` gathers the facts, with the side effects they have today and in the same order (an ad on screen
 * over content that no longer fits it ends first; an expired ad or a changed episode ends the ad as it is found),
 * then throws what this says.
 */
internal object ShowCheck {
    /** The first reason `show` may not go on, or `null`. [usableError]: why the ad is no longer usable, if so. */
    fun refusal(
        wasShown: Boolean,
        usableError: VideoAdException?,
        isAnotherAdOnScreen: Boolean,
        isLoaded: Boolean,
        isContentPaused: Boolean,
    ): VideoAdException? = when {
        wasShown -> VideoAdException.AlreadyShown()
        usableError != null -> usableError
        isAnotherAdOnScreen -> VideoAdException.AnotherAdShowing()
        !isLoaded -> VideoAdException.NotLoaded()
        isContentPaused -> VideoAdException.ContentPaused()
        else -> null
    }
}
