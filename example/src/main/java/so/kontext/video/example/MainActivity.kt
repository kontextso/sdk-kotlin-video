package so.kontext.video.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Compose host, mirroring the Samsung TV+ / tvplus-test-player UI stack
 * (Kotlin + Jetpack Compose + Material3). Launches on an empty [HomeScreen]
 * with a link; tapping it opens the [PlayerScreen]. The video SDK
 * (`:video`) does nothing yet — this is just the scaffolding it plugs into.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var showPlayer by remember { mutableStateOf(false) }

                if (showPlayer) {
                    // Back returns to the empty screen, which disposes the
                    // player (releasing ExoPlayer) since PlayerScreen leaves
                    // the composition.
                    BackHandler { showPlayer = false }
                    PlayerScreen()
                } else {
                    HomeScreen(onOpenVideo = { showPlayer = true })
                }
            }
        }
    }
}
