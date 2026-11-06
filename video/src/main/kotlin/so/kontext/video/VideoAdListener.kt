package so.kontext.video

import androidx.annotation.MainThread

/**
 * The moments of an ad on screen, delivered on the main thread. Every method has an empty default, so
 * implement the ones you need (from Java too).
 *
 * Nothing is delivered before `show`: an ad that can no longer be shown says so when `load()` or `show`
 * throws. `show` either throws, and nothing happened, or the show is accepted: an overlay or pause
 * ad is on screen when it returns; a linear ad has started its screen. After that: the impression (at most
 * once, maybe never) and any clicks; then exactly one [onAdEnded], whoever ended it (the viewer, the
 * creative, the system, or the app with `dismiss()` or `destroy()`). There is no "shown" callback: `show`
 * returning is that signal.
 *
 * Decide playback in [onAdEnded], from the format, the reason and whether that content is still current:
 * a linear ad's end is normally where the content resumes; a pause ad closed with ✕ ([VideoAd.EndReason.CLOSED])
 * leaves the content paused, Resume ([VideoAd.EndReason.RESUMED]) means play. For an overlay or pause ad it
 * arrives synchronously, even from inside your own `dismiss()` or `destroy()`; for a linear ad once its
 * Activity is gone, so the next Activity can be started from it.
 *
 * The ad holds the listener until [onAdEnded] has run, then lets it go.
 */
public interface VideoAdListener {
    /**
     * The impression was counted: for an image or HTML creative at its first layout on screen, just after
     * `show` returns; at the first frame for a video.
     * It may never come: the ad can end before.
     */
    @MainThread
    public fun onAdImpression(ad: VideoAd) {}

    /**
     * The viewer tapped the ad and the SDK took the click: it fired the click beacons and is opening the
     * target. Neither which target opened nor whether it did is reported. Once per tap.
     */
    @MainThread
    public fun onAdClicked(ad: VideoAd, click: VideoAd.Click) {}

    /**
     * The ad broke after `show` returned: the creative failed or stalled, or the linear ad's Activity never
     * started ([VideoAdException.CannotShowNow]). [onAdEnded] with [VideoAd.EndReason.FAILED] follows.
     */
    @MainThread
    public fun onAdFailed(ad: VideoAd, error: VideoAdException) {}

    /** The ad's time on screen is over. Exactly once per successful `show`. */
    @MainThread
    public fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {}
}
