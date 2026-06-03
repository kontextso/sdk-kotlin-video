package so.kontext.video.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Minimal demo screen: a single full-screen [PlayerView] that plays one
 * sample clip on a loop. The Kontext Video SDK (`:video`) does nothing
 * yet, so this is just the player scaffolding the SDK will later plug
 * into.
 *
 * `PlayerView` is part of Media3's `@UnstableApi` surface, hence the
 * class-level opt-in.
 */
@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player_view)
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun initializePlayer() {
        val exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer

        exoPlayer.setMediaItem(MediaItem.fromUri(SAMPLE_VIDEO_URL))
        exoPlayer.repeatMode = ExoPlayer.REPEAT_MODE_ALL
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()

        player = exoPlayer
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        playerView.player = null
    }

    private companion object {
        // W3C's public sample clip (Sintel trailer, ~52s). A plain
        // progressive MP4, so no extra Media3 extension module is needed.
        const val SAMPLE_VIDEO_URL =
            "https://media.w3.org/2010/05/sintel/trailer.mp4"
    }
}
