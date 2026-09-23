package so.kontext.video

import so.kontext.video.model.VideoAdEvent

/**
 * Immutable configuration for a [VideoSession].
 *
 * The viewing-session ID is assigned by the server and managed by the SDK;
 * the publisher never has to touch it.
 *
 * @property publisherToken Publisher token issued by Kontext.
 * @property userId Publisher's stable user ID.
 * @property userEmail User email, when the publisher has one.
 * @property adServerUrl Ad server base URL. Override for development, e.g.
 *   the mock server at `http://10.0.2.2:5175` from the emulator.
 * @property onEvent Delivered on the main thread for every ad lifecycle event.
 */
public data class VideoSessionOptions(
    val publisherToken: String,
    val userId: String,
    val userEmail: String? = null,
    val adServerUrl: String = Constants.DEFAULT_AD_SERVER_URL,
    val onEvent: ((VideoAdEvent) -> Unit)? = null,
)
