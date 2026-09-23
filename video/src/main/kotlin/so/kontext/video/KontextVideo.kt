package so.kontext.video

import android.content.Context

/**
 * Static entry point of the Kontext Video SDK.
 *
 * ```kotlin
 * val session = KontextVideo.createSession(
 *     context = applicationContext,
 *     options = VideoSessionOptions(publisherToken = "pub-xxxx", userId = "user-123"),
 * )
 * ```
 */
public object KontextVideo {
    /** Version of the SDK, as reported to the ad server. */
    public val version: String
        get() = SDKInfo.VERSION

    /**
     * Creates a new video session. One session per app user; it survives
     * across episodes and videos. The `context` is unwrapped to its
     * application context, so an Activity is never retained.
     */
    public fun createSession(context: Context, options: VideoSessionOptions): VideoSession =
        VideoSession(context.applicationContext, options)
}
