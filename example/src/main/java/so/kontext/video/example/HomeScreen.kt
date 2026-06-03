package so.kontext.video.example

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Empty launch screen with a single clickable link that opens the video
 * player. Nothing plays until the link is tapped.
 */
@Composable
fun HomeScreen(onOpenVideo: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Open video",
                style = TextStyle(textDecoration = TextDecoration.Underline),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onOpenVideo)
                    .padding(16.dp),
            )
        }
    }
}
