package so.kontext.video.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

/**
 * Compose host, mirroring the Samsung TV+ / tvplus-test-player UI stack
 * (Kotlin + Jetpack Compose + Material3). The video SDK (`:video`) does
 * nothing yet — this is just the player scaffolding it will plug into.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerScreen()
            }
        }
    }
}
