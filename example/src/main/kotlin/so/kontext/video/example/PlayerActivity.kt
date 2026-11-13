package so.kontext.video.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import so.kontext.video.KontextVideo.Content
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAdListener
import so.kontext.video.VideoAds
import so.kontext.video.media3.track

/**
 * One episode: the player, the ad area over it, and the moment each format appears. The ad area covers the
 * whole screen, drawn behind the system bars; the SDK keeps a strip or a card clear of the bars, and the insets
 * keep it below the back button and above the title block; a full-screen pause ad fills it all, the button and
 * the title included. Taps outside an ad go through to the player.
 *
 * ```
 *   status bar
 *   16 dp, the back button (36 dp), then a gap: 24 dp for an overlay strip, 16 dp for a pause card
 *   ── the ad's top inset: 76 dp or 68 dp below the bar ──
 *        the ad's room
 *   ── the ad's bottom inset: the title block + 20 dp above the bar ──
 *   the title block: the title, the status line, 24 dp
 *   navigation bar
 * ```
 */
class PlayerActivity : ComponentActivity(), VideoAdListener {
    private lateinit var video: Video
    private lateinit var player: ExoPlayer
    private lateinit var ads: VideoAds
    private lateinit var adArea: FrameLayout
    private lateinit var status: TextView
    private lateinit var titleBlock: LinearLayout
    private var ad: VideoAd? = null
    private lateinit var playIcon: PlayIcon
    private var isPaused = false
        set(value) {
            field = value
            playIcon.visibility = if (value) View.VISIBLE else View.GONE
        }
    private var isLeaving = false
    private var startJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
        // While the content is paused, a play icon in the middle, as iOS shows; taps go through it to the player.
        playIcon = PlayIcon(this).apply { visibility = View.GONE }
        root.addView(playIcon, FrameLayout.LayoutParams(dp(PLAY_ICON_DP), dp(PLAY_ICON_DP), Gravity.CENTER))
        status = TextView(this).apply {
            setTextColor(Color.argb(204, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, 0)
            text = "preparing the ad"
        }
        titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
            addView(TextView(this@PlayerActivity).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                text = video.title
            })
            addView(status)
        }
        root.addView(titleBlock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val back = TextView(this).apply {
            text = "‹"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            contentDescription = "Back"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(102, 0, 0, 0))
            }
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(dp(BACK_SIZE_DP), dp(BACK_SIZE_DP), Gravity.TOP or Gravity.START))
        // Where the SDK puts overlay and pause ads: the whole screen, above the title and the back button.
        adArea = FrameLayout(this)
        root.addView(adArea, match())
        setContentView(root)
        // The button and the title block keep clear of the bars, as the SDK keeps the ad: the insets below are
        // measured from the bars' edges, which the SDK adds to them.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (back.layoutParams as FrameLayout.LayoutParams).setMargins(bars.left + dp(BACK_MARGIN_DP), bars.top + dp(BACK_MARGIN_DP), 0, 0)
            (titleBlock.layoutParams as FrameLayout.LayoutParams).setMargins(bars.left, 0, bars.right, bars.bottom)
            back.requestLayout()
            titleBlock.requestLayout()
            insets
        }

        // Creating it sends nothing; the request goes out in prepareAd(). The SDK reads the playhead from the
        // player and follows its playback: an overlay lives only while the content plays.
        ads = VideoAds(Content(video.id, "en") { title = video.title; genre = "romance" }, section = video.section)
        ads.track(player)
        player.play()
        startJob = lifecycleScope.launch {
            prepareAd()
            waitForPlaying(3_000)
            if (!isLeaving) moment()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isLeaving = true
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
            AdFormat.PAUSE, AdFormat.PAUSE_FULLSCREEN -> Unit // the viewer pauses; nothing happens on its own
        }
    }

    private fun toggle() = if (isPaused) resume() else pause()

    private fun pause() {
        player.pause()
        isPaused = true
        if (video.isPause) showInPlayer()
    }

    private fun resume() {
        if (video.isPause) {
            ad?.dismiss()
            lifecycleScope.launch { prepareAd() } // the next pause gets its own ad
        }
        player.play()
        isPaused = false
    }

    private fun showInPlayer() {
        val ad = ad?.takeIf { it.isReady } ?: return
        try {
            ad.show(adArea, adInsets())
        } catch (error: VideoAdException) {
            status.text = "show: ${error.message}"
        }
    }

    /**
     * Below the back button (its margin, its size, then a gap: wider for an overlay strip than for a pause card,
     * 76 and 68 dp as on iOS) and 20 dp above the title block. A full-screen pause ad ignores them.
     */
    private fun adInsets(): Insets {
        val gap = if (video.format == AdFormat.OVERLAY) OVERLAY_GAP_DP else PAUSE_CARD_GAP_DP
        return Insets.of(0, dp(BACK_MARGIN_DP + BACK_SIZE_DP + gap), 0, titleBlock.height + dp(20))
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
        if (isLeaving) return
        when (ad.format) {
            AdFormat.LINEAR -> player.play() // the content paused for the ad; every other way it ends lands here
            AdFormat.PAUSE_FULLSCREEN -> if (reason == VideoAd.EndReason.RESUMED) resume() // the viewer tapped Resume on the ad
            AdFormat.PAUSE, AdFormat.OVERLAY -> Unit
        }
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_VIDEO = "video"

        /** The back button: its margin from the bars' edges and its size. */
        private const val BACK_MARGIN_DP = 16
        private const val BACK_SIZE_DP = 36

        /** The gap between the back button and an ad at the top. */
        private const val OVERLAY_GAP_DP = 24
        private const val PAUSE_CARD_GAP_DP = 16
        /** The paused content's play icon, as large as iOS's (56 pt). */
        private const val PLAY_ICON_DP = 56
    }
}

/** A filled play triangle in 80 % white, iOS's `play.fill` over the paused content. */
private class PlayIcon(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(204, 255, 255, 255) }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset()
        path.moveTo(w * 0.22f, h * 0.12f)
        path.lineTo(w * 0.88f, h * 0.5f)
        path.lineTo(w * 0.22f, h * 0.88f)
        path.close()
        canvas.drawPath(path, paint)
    }
}
