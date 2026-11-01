package so.kontext.video.example

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.graphics.Insets
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.kontext.video.AdFormat
import so.kontext.video.Content
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAdListener
import so.kontext.video.VideoAds
import so.kontext.video.media3.track

/**
 * One episode: the player, the ad area over it, and the moment each format appears. The ad area covers the
 * whole screen above the title; the SDK keeps a strip or a card clear of the system bars, and the insets
 * keep it above the title; a full-screen pause ad fills it all. Taps outside an ad go through to the player.
 */
class PlayerActivity : ComponentActivity(), VideoAdListener {
    private lateinit var video: Video
    private lateinit var player: ExoPlayer
    private lateinit var ads: VideoAds
    private lateinit var adArea: FrameLayout
    private lateinit var status: TextView
    private lateinit var titleBlock: TextView
    private var ad: VideoAd? = null
    private var isPaused = false
    private var leaving = false
    private var startJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = Video.all.first { it.id == intent.getStringExtra(EXTRA_VIDEO) }
        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri("android.resource://$packageName/${R.raw.sample_episode}"))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            player = this@PlayerActivity.player
            setOnClickListener { toggle() }
        }, match())
        titleBlock = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dp(16), 0, dp(16), dp(32))
            text = video.title
        }
        status = TextView(this).apply {
            setTextColor(Color.argb(204, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), 0, dp(16), dp(12))
            text = "preparing the ad"
        }
        root.addView(titleBlock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dp(60)
        })
        // Where the SDK puts overlay and pause ads: the whole screen, above the title.
        adArea = FrameLayout(this)
        root.addView(adArea, match())
        setContentView(root)

        // Creating it sends nothing; the request goes out in prepareAd(). The SDK reads the playhead from the
        // player and follows its playback: an overlay lives only while the content plays.
        ads = VideoAds(Content(video.id, "en") { title = video.title; genre = "romance" }, video.labels)
        ads.track(player)
        player.play()
        startJob = lifecycleScope.launch {
            prepareAd()
            waitForPlaying(3_000)
            if (!leaving) moment()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        leaving = true
        startJob?.cancel()
        // The screen owns its ads: they go with it. configChanges keeps it, and them, across a rotation.
        ads.destroy()
        player.release()
    }

    private suspend fun waitForPlaying(millis: Long) {
        var played = 0L
        while (played < millis) {
            delay(250)
            if (player.isPlaying) played += 250
        }
    }

    private suspend fun prepareAd() {
        try {
            val ad = ads.format(video.format)
            ad.load()
            ad.listener = this
            this.ad = ad
            status.text = "${video.format} ad ready, ${ad.price.value} ${ad.price.currency} CPM"
        } catch (error: VideoAdException) {
            status.text = "no ${video.format} ad: ${error.message}"
        }
    }

    private fun moment() {
        when (video.format) {
            AdFormat.LINEAR -> showLinear()
            AdFormat.OVERLAY -> showInPlayer() // ends by itself after its duration
            AdFormat.PAUSE -> Unit // the viewer pauses; nothing happens on its own
        }
    }

    private fun toggle() = if (isPaused) resume() else pause()

    private fun pause() {
        player.pause()
        isPaused = true
        if (video.format == AdFormat.PAUSE) showInPlayer()
    }

    private fun resume() {
        if (video.format == AdFormat.PAUSE) {
            ad?.dismiss()
            lifecycleScope.launch { prepareAd() } // the next pause gets its own ad
        }
        player.play()
        isPaused = false
    }

    private fun showInPlayer() {
        val ad = ad?.takeIf { it.isReady } ?: return
        try {
            ad.show(adArea, Insets.of(0, 0, 0, titleBlock.height + dp(12)))
        } catch (error: VideoAdException) {
            status.text = "show: ${error.message}"
        }
    }

    private fun showLinear() {
        val ad = ad?.takeIf { it.isReady } ?: return
        player.pause()
        try {
            ad.show(this)
        } catch (error: VideoAdException) {
            player.play() // never started: undo here, no listener call follows
            status.text = "show: ${error.message}"
        }
    }

    override fun onAdImpression(ad: VideoAd) {
        status.text = "${ad.format} ad: impression"
    }

    override fun onAdClicked(ad: VideoAd, click: VideoAd.Click) {
        status.text = "${ad.format} ad: click (${click.area})"
    }

    override fun onAdFailed(ad: VideoAd, error: VideoAdException) {
        status.text = "${ad.format} ad failed: ${error.message}"
    }

    override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
        status.text = "${ad.format} ad ended: $reason"
        if (ad === this.ad) this.ad = null
        // The screen decides playback, not the ad: after the viewer left (DESTROYED), nothing resumes.
        if (leaving) return
        when (ad.format) {
            AdFormat.LINEAR -> player.play() // the content paused for the ad; every other way it ends lands here
            AdFormat.PAUSE -> if (reason == VideoAd.EndReason.RESUMED) resume() // the viewer tapped Resume on the ad
            AdFormat.OVERLAY -> Unit
        }
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_VIDEO = "video"
    }
}
