package so.kontext.video.example

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Full-screen player. Builds an [ExoPlayer], loops one HLS sample, and
 * renders it through Media3's [PlayerView] via [AndroidView] interop —
 * the same Compose + Media3 pattern the tvplus-test-player uses. Media3's
 * HLS source set (added in `build.gradle.kts`) handles the `.m3u8`
 * manifest; DASH is wired in too for future DRM vectors.
 *
 * The player is created with the composition and released in
 * [DisposableEffect]'s `onDispose`, so it survives recomposition but is
 * torn down when the screen leaves.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen() {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(SAMPLE_HLS_URL))
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
        )
    }
}

// Apple's public advanced (fMP4) multi-variant HLS test stream — a plain
// FAST-style `.m3u8`, the format Samsung TV+ streams. Swap in a
// server-stitched (SSAI) manifest to exercise stitched-ad parsing later.
private const val SAMPLE_HLS_URL =
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
