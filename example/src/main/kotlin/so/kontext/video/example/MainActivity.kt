package so.kontext.video.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Home grid of stories → fullscreen vertical episode player.
 *
 * Debug/CI hooks, as intent extras (`adb shell am start … --ez auto_open_story true`):
 * `auto_open_story` jumps straight into the first story; `auto_pause_loop`
 * pauses/resumes every 3 s so the pause → ad → pings → resume → reload
 * cycle shows up in logcat.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ads.init(this)
        val autoOpenStory = intent.getBooleanExtra(EXTRA_AUTO_OPEN_STORY, false)
        val autoPauseLoop = intent.getBooleanExtra(EXTRA_AUTO_PAUSE_LOOP, false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var story by remember { mutableStateOf(if (autoOpenStory) Story.samples.first() else null) }
                val current = story
                if (current == null) {
                    HomeScreen(onOpen = { story = it })
                } else {
                    BackHandler { story = null }
                    StoryPlayerScreen(story = current, autoPauseLoop = autoPauseLoop, onBack = { story = null })
                }
            }
        }
    }

    companion object {
        const val EXTRA_AUTO_OPEN_STORY = "auto_open_story"
        const val EXTRA_AUTO_PAUSE_LOOP = "auto_pause_loop"
    }
}
