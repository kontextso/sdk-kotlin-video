package so.kontext.video.example

import android.content.Context
import android.util.Log
import so.kontext.video.KontextVideo
import so.kontext.video.VideoSession
import so.kontext.video.VideoSessionOptions
import so.kontext.video.model.VideoAdEvent

/**
 * The example talks to the mock ad server
 * (github.com/kontextso/sdk-video-mock-server) running on the host: `pnpm dev`
 * there, then run the app in the emulator, which reaches the host at
 * 10.0.2.2. For a physical device replace it with the host's LAN IP.
 */
object Ads {
    private const val MOCK_SERVER_URL = "http://10.0.2.2:5175"
    const val TAG = "VideoExample"

    lateinit var session: VideoSession
        private set

    fun init(context: Context) {
        if (::session.isInitialized) return
        session = KontextVideo.createSession(
            context = context,
            options = VideoSessionOptions(
                publisherToken = "pub-example",
                userId = "user-example",
                adServerUrl = MOCK_SERVER_URL,
                onEvent = ::logEvent,
            ),
        )
    }

    private fun logEvent(event: VideoAdEvent) {
        val line = when (event) {
            is VideoAdEvent.Loaded -> event.data.results.joinToString("\n") { r ->
                val reason = r.noFillReason?.let { " ($it)" } ?: ""
                val ads = r.ads.joinToString(", ") { "${it.id.take(8)} eCPM ${it.ecpm}" }
                "loaded ${r.format} ${r.ads.size}$reason $ads"
            }
            is VideoAdEvent.Shown -> "shown ${event.ad.format} bid=${event.ad.adId}"
            is VideoAdEvent.Clicked -> "clicked ${event.ad.format} bid=${event.ad.adId}"
            is VideoAdEvent.Closed -> "closed ${event.ad.format} bid=${event.ad.adId}"
            is VideoAdEvent.Completed -> "completed ${event.ad.format} bid=${event.ad.adId}"
            is VideoAdEvent.Error -> "error ${event.error.message}"
        }
        Log.d(TAG, line)
    }
}
