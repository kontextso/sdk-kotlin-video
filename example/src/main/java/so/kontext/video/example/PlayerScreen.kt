package so.kontext.video.example

import android.content.ComponentName
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView

/**
 * Full-screen player. Connects to [PlaybackService] via a [MediaController]
 * (the recommended Media3 architecture, and how Samsung TV+ drives its
 * MediaSession-backed player), loads the [channel] on a loop, and renders
 * it through Media3's [PlayerView]. The controller is released when the
 * screen leaves the composition.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(channel: Channel = DEFAULT_CHANNEL) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(channel.id) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                val c = future.get()
                c.setMediaItem(channel.toMediaItem())
                c.repeatMode = Player.REPEAT_MODE_ALL
                c.playWhenReady = true
                c.prepare()
                controller = c
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view -> view.player = controller },
        )
    }
}
