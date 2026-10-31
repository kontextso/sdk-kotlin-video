package so.kontext.video.example

import android.graphics.Color
import so.kontext.video.AdFormat

/**
 * One video per situation in a short-drama app. Its labels say where the viewer is (`stage`) and how the
 * player looks (`layout`); the ad server's rules turn them into the one format it offers there. The app
 * still asks for the format it shows: the others come back as no fill.
 */
data class Video(
    val id: String,
    val title: String,
    val format: AdFormat,
    /** Sent with every ads request of this video: `VideoAds(content, labels)`. */
    val labels: Map<String, String>,
    val color: Int,
    /** What happens once the video plays. */
    val caption: String,
) {
    companion object {
        val all = listOf(
            Video("demo-pause", "Moonlit Contract", AdFormat.PAUSE, mapOf("stage" to "first_episode", "layout" to "inline"),
                Color.rgb(63, 81, 181), "Tap to pause: a pause ad shows, tap again to resume"),
            Video("demo-pause-fullscreen", "The Heir's Return", AdFormat.PAUSE, mapOf("stage" to "first_episode", "layout" to "fullscreen"),
                Color.rgb(0, 150, 136), "Tap to pause: a full-screen pause ad shows, its Resume plays on"),
            Video("demo-overlay", "Revenge of the Heiress", AdFormat.OVERLAY, mapOf("stage" to "free_episodes"),
                Color.rgb(255, 87, 34), "An overlay after 3 s of playing; pausing ends it"),
            Video("demo-linear", "The CEO's Secret", AdFormat.LINEAR, mapOf("stage" to "unlock"),
                Color.rgb(156, 39, 176), "A linear ad after 3 s of playing"),
        )
    }
}
