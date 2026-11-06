package so.kontext.video

import androidx.annotation.MainThread

/** The ad from `format()` or `load()`, for Java or code without coroutines; on the main thread. */
public interface VideoAdCallback {
    /** The call succeeded: the ad `format()` returned, or the ad `load()` made ready. */
    @MainThread
    public fun onAd(ad: VideoAd)

    /** The call failed: the same exception the `suspend` variant throws. */
    @MainThread
    public fun onError(error: VideoAdException)
}
