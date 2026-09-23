package so.kontext.video.example

import android.net.Uri
import androidx.compose.ui.graphics.Color

data class Episode(val id: String, val number: Int, val title: String) {
    /** Every episode plays the same bundled portrait sample clip — no network needed. */
    val videoUri: Uri
        get() = Uri.parse("android.resource://so.kontext.video.example/raw/sample_episode")
}

data class Story(
    val id: String,
    val title: String,
    val genre: String,
    val color: Color,
    val episodes: List<Episode>,
) {
    companion object {
        val samples: List<Story> = listOf(
            story("ceo-secret", "The CEO's Secret", "Romance", Color(0xFF7B2CBF), "The Interview", "Late Night", "The Gala"),
            story("moonlit-vow", "Moonlit Vow", "Fantasy", Color(0xFF1B4965), "The Curse", "The Forest", "The Vow"),
            story("second-chance", "Second Chance", "Drama", Color(0xFFB5651D), "Homecoming", "The Letter", "The Reunion"),
        )

        private fun story(id: String, title: String, genre: String, color: Color, vararg titles: String) = Story(
            id = id,
            title = title,
            genre = genre,
            color = color,
            episodes = titles.mapIndexed { index, t -> Episode(id = "$id-ep${index + 1}", number = index + 1, title = t) },
        )
    }
}
