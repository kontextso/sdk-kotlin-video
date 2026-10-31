package so.kontext.video

/** The result of a callback-style call (for Java, or code without coroutines), delivered on the main thread. */
public interface VideoAdsCallback<T> {
    /** The call succeeded. */
    public fun onSuccess(result: T)

    /** The call failed: the same exception the `suspend` variant throws. */
    public fun onError(error: VideoAdException)
}
