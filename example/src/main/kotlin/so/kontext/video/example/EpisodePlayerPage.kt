package so.kontext.video.example

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import so.kontext.video.VideoAds
import so.kontext.video.ads.BannerStyle

/**
 * A single fullscreen episode page: video + title overlay, tap to play/pause.
 * Drives the pause, overlay and interstitial ads from the same place
 * playback is controlled; the feed owns the [VideoAds] handle.
 */
@Composable
fun EpisodePlayerPage(story: Story, episode: Episode, isActive: Boolean, ads: VideoAds?, autoPauseLoop: Boolean) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val container = remember { FrameLayout(context) }
    val currentAds by rememberUpdatedState(ads)
    var isPaused by remember { mutableStateOf(false) }
    var didShowInterstitial by remember { mutableStateOf(false) }
    var isInterstitialShowing by remember { mutableStateOf(false) }

    // The player view ignores the safe area: keep banners clear of the
    // status bar (top) and the episode title (bottom).
    val bannerStyle = BannerStyle(marginTopDp = 72, marginBottomDp = 120)

    // The demo's interstitial moment: 3 s into the first playback of an
    // episode the content pauses, the SDK presents the ad over the app, and
    // playback resumes when it is dismissed. Once per episode — the ad is
    // consumed by `show`.
    fun showInterstitialIfDue(positionMs: Long) {
        val ads = currentAds ?: return
        val activity = context.findActivity() ?: return
        if (positionMs < 3_000 || didShowInterstitial || !player.isPlaying || ads.interstitial.nextAd == null) return
        didShowInterstitial = true
        isInterstitialShowing = true
        ads.overlay.hide()
        player.pause()
        ads.interstitial.show(activity) {
            isInterstitialShowing = false
            player.play()
        }
    }

    fun showPauseAd() {
        val ads = currentAds ?: return
        // `nextAd` is where an app with its own auction would read `ecpm`; the demo shows whatever is ready.
        ads.pause.nextAd ?: return
        ads.pause.show(container, style = bannerStyle)
    }

    fun pause() {
        player.pause()
        isPaused = true
        currentAds?.overlay?.hide()
        showPauseAd()
    }

    fun resume() {
        val ads = currentAds
        ads?.pause?.hide()
        ads?.overlay?.hide()
        player.play()
        isPaused = false
        // The shown ad is consumed; ask for the next one while the episode
        // keeps playing. The server answers with a new adId (or a noFillReason).
        if (ads != null && !ads.pause.isReady) ads.pause.load()
    }

    DisposableEffect(Unit) {
        player.setMediaItem(MediaItem.fromUri(episode.videoUri))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare()
        onDispose { player.release() }
    }

    LaunchedEffect(isActive) {
        isPaused = false
        currentAds?.pause?.hide()
        if (isActive) {
            player.seekTo(0)
            player.play()
        } else {
            player.pause()
        }
    }

    // The overlay glue: the SDK reads the playhead 4× a second and calls back
    // when a window opens; showing stays this app's call, and the SDK takes
    // the banner down when the window ends. The interstitial moment is
    // checked from this page's own loop.
    LaunchedEffect(isActive, ads) {
        val ads = ads ?: return@LaunchedEffect
        if (!isActive) return@LaunchedEffect
        // A window can open while the content is paused (the pause ad is up): overlays only play over playing video.
        ads.overlay.onWindowOpen = { ad -> if (player.isPlaying) ads.overlay.show(ad, container, style = bannerStyle) }
        ads.overlay.trackPlayhead { player.currentPosition }
        try {
            while (true) {
                showInterstitialIfDue(player.currentPosition)
                delay(250)
            }
        } finally {
            // The page went off screen (or the handle changed); its handle may live on for a swipe back.
            ads.overlay.stopTrackingPlayhead()
        }
    }

    // Debug/CI hook: pause and resume every 3 s so the pause → ad → pings →
    // resume → reload cycle can be observed from logcat.
    LaunchedEffect(isActive, autoPauseLoop) {
        if (!isActive || !autoPauseLoop) return@LaunchedEffect
        while (isActive) {
            delay(3_000)
            // The interstitial pauses the content itself; toggling underneath
            // it would show a pause ad behind the ad screen.
            if (!isInterstitialShowing) {
                if (player.isPlaying) pause() else resume()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                val playerView = PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    this.player = player
                    setOnClickListener { if (player.isPlaying) pause() else resume() }
                }
                container.addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                container
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (isPaused) {
            Text("▶", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.displayMedium, modifier = Modifier.align(Alignment.Center))
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 48.dp),
        ) {
            Text(story.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text("Episode ${episode.number} · ${episode.title}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
