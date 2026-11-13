package so.kontext.video.example

import android.graphics.Color
import so.kontext.video.AdFormat

/**
 * One video per ad format in a short-drama app. Its section says where in the app the player is; the ad server
 * answers every format of that section, and the app asks for the one it shows.
 */
data class Video(
    val id: String,
    val title: String,
    val format: AdFormat,
    /** Sent with every ads request of this video: `VideoAds(content, section = …)`. */
    val section: String,
    val color: Int,
    /** What happens once the video plays. */
    val caption: String,
) {
    /** The format is shown while the content is paused: a card, or the whole player. */
    val isPause: Boolean get() = format == AdFormat.PAUSE || format == AdFormat.PAUSE_FULLSCREEN

    companion object {
        /** The home list, in this order. */
        val all = listOf(
            Video("demo-overlay", "Revenge of the Heiress", AdFormat.OVERLAY, "free_episodes",
                Color.rgb(255, 87, 34), "An overlay after 3 s of playing; pausing ends it"),
            Video("demo-linear", "The CEO's Secret", AdFormat.LINEAR, "unlock",
                Color.rgb(156, 39, 176), "A linear ad after 3 s of playing"),
            Video("demo-pause-fullscreen", "The Heir's Return", AdFormat.PAUSE_FULLSCREEN, "first_episode",
                Color.rgb(0, 150, 136), "Tap to pause: a full-screen pause ad shows, its Resume plays on"),
            Video("demo-pause", "Moonlit Contract", AdFormat.PAUSE, "first_episode",
                Color.rgb(63, 81, 181), "Tap to pause: a pause ad shows, tap again to resume"),
        )

        /**
         * The video an id opens: a video's own id, or a format's name (`overlay`, `linear`, `pause`,
         * `pause-fullscreen`), which opens its `demo-<format>` video. `null` when none matches.
         */
        fun find(id: String): Video? = all.firstOrNull { it.id == id || it.id == "demo-$id" }
            ?: all.firstOrNull { it.format.name.equals(id, ignoreCase = true) }
    }
}
