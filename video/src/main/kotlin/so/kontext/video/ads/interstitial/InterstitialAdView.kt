package so.kontext.video.ads.interstitial

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import so.kontext.video.Constants
import so.kontext.video.model.VideoAd
import kotlin.math.roundToInt

/**
 * Full-screen video creative with an AD badge and a Close chip that counts
 * down `skip_after_ms`. Plays with sound unless the server says `muted`;
 * tapping the video opens the click-through. The video is letterboxed
 * inside the view (aspect fit).
 */
internal class InterstitialAdView(
    context: Context,
    private val ad: VideoAd,
    private val style: InterstitialAd.Style,
    private val onClick: () -> Unit,
    private val onFirstFrame: () -> Unit,
    /** 1 = first quartile, 2 = midpoint, 3 = third quartile. */
    private val onProgress: (Int) -> Unit,
    private val onClosable: () -> Unit,
    private val onCloseTapped: () -> Unit,
    /** The media failed to load or play. The ad cannot be shown. */
    private val onFailure: (String) -> Unit,
    private val onComplete: () -> Unit,
) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val player = MediaPlayer()
    private val textureView = TextureView(context)
    private val closeChip = TextView(context)
    private val handler = Handler(Looper.getMainLooper())

    private var surface: Surface? = null
    private var prepared = false
    private var surfaceReady = false
    private var startRequested = false
    private var started = false
    private var firstFrameReported = false
    private var reportedQuartile = 0
    private var closableReported = false
    private var ended = false
    private var failed = false
    private var stopped = false
    private var videoWidth = 0
    private var videoHeight = 0

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            onTick()
            handler.postDelayed(this, TICK_MS)
        }
    }
    private val startWatchdog = Runnable {
        if (!firstFrameReported) fail("interstitial ad video did not start within ${Constants.INTERSTITIAL_START_TIMEOUT_MS / 1000}s")
    }
    private val stallWatchdog = Runnable {
        if (!player.isPlayingSafely()) fail("interstitial ad video stalled for ${Constants.INTERSTITIAL_STALL_TIMEOUT_MS / 1000}s")
    }

    init {
        setBackgroundColor(style.backgroundColor)
        textureView.surfaceTextureListener = this
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        setOnClickListener { onClick() }
        addView(
            makeBadge(),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(20)
                leftMargin = dp(16)
            },
        )
        setUpCloseChip()
        addView(
            closeChip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(16)
                rightMargin = dp(16)
            },
        )
        updateCloseChip(elapsedMs = 0)
        setUpPlayer()
    }

    private fun setUpPlayer() {
        val url = ad.creative.video?.url
        if (url.isNullOrEmpty()) {
            // Callbacks are wired by the caller right after init returns, so
            // defer the failure a tick so it is delivered.
            handler.post { fail("interstitial ad has no playable video url") }
            return
        }
        player.setOnPreparedListener {
            prepared = true
            maybeStart()
        }
        player.setOnVideoSizeChangedListener { _, width, height ->
            videoWidth = width
            videoHeight = height
            applyVideoSize()
        }
        player.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> reportFirstFrame()
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> armStallWatchdog()
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> handler.removeCallbacks(stallWatchdog)
            }
            true
        }
        player.setOnCompletionListener { if (!ended) onComplete() }
        player.setOnErrorListener { _, what, extra ->
            fail("media error $what/$extra")
            true
        }
        if (ad.playback?.muted == true) player.setVolume(0f, 0f)
        try {
            player.setDataSource(url)
            player.prepareAsync()
        } catch (e: Exception) {
            handler.post { fail("media failed: ${e.message}") }
        }
    }

    fun play() {
        startRequested = true
        // The video must produce its first frame within the timeout, or the
        // SDK gives up (dead CDN, endless buffering) so the app can move on.
        handler.postDelayed(startWatchdog, Constants.INTERSTITIAL_START_TIMEOUT_MS)
        maybeStart()
    }

    /** The video ended: keep the last frame (the player pauses on it) and let the viewer close whenever they like. */
    fun showEndState() {
        ended = true
        closeChip.text = style.closeTitle
        closeChip.isEnabled = true
        closeChip.visibility = VISIBLE
    }

    /** Stops playback and releases the player. Always called before the view leaves the hierarchy. */
    fun stop() {
        if (stopped) return
        stopped = true
        handler.removeCallbacksAndMessages(null)
        runCatching { if (player.isPlaying) player.stop() }
        player.release()
        surface?.release()
        surface = null
    }

    private fun maybeStart() {
        if (!startRequested || !prepared || !surfaceReady || started || stopped) return
        started = true
        player.start()
        handler.post(tick)
    }

    private fun reportFirstFrame() {
        if (firstFrameReported) return
        firstFrameReported = true
        handler.removeCallbacks(startWatchdog)
        handler.removeCallbacks(stallWatchdog)
        onFirstFrame()
    }

    private fun armStallWatchdog() {
        if (failed || !firstFrameReported) return
        handler.removeCallbacks(stallWatchdog)
        handler.postDelayed(stallWatchdog, Constants.INTERSTITIAL_STALL_TIMEOUT_MS)
    }

    /** Single failure funnel: reports once, then the placement tears the ad down. */
    private fun fail(message: String) {
        if (failed) return
        failed = true
        onFailure(message)
    }

    private fun onTick() {
        val positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
        updateCloseChip(positionMs)
        val durationMs = runCatching { player.duration.toLong() }.getOrDefault(0L)
        if (durationMs <= 0) return
        val fraction = positionMs.toDouble() / durationMs
        val quartile = when {
            fraction >= 0.75 -> 3
            fraction >= 0.5 -> 2
            fraction >= 0.25 -> 1
            else -> 0
        }
        while (reportedQuartile < quartile) {
            reportedQuartile += 1
            onProgress(reportedQuartile)
        }
    }

    /** No `skip_after_ms` = closable only once the video ends, so the chip stays hidden until then. */
    private fun updateCloseChip(elapsedMs: Long) {
        if (ended) return
        val skipAfterMs = ad.playback?.skipAfterMs
        if (skipAfterMs == null) {
            closeChip.visibility = GONE
            return
        }
        val remainingMs = skipAfterMs - elapsedMs
        if (remainingMs > 0) {
            val seconds = ((remainingMs + 999) / 1000).toInt()
            closeChip.text = String.format(style.closeCountdownFormat, seconds)
            closeChip.isEnabled = false
        } else {
            closeChip.text = style.closeTitle
            closeChip.isEnabled = true
            if (!closableReported) {
                closableReported = true
                onClosable()
            }
        }
    }

    private fun makeBadge(): TextView = TextView(context).apply {
        text = style.badgeText
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(0xE6FFFFFF.toInt())
        }
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }

    private fun setUpCloseChip() {
        closeChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        closeChip.setTypeface(closeChip.typeface, Typeface.BOLD)
        closeChip.setTextColor(style.textColor)
        closeChip.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(style.chipBackgroundColor)
        }
        closeChip.setPadding(dp(14), dp(8), dp(14), dp(8))
        closeChip.isEnabled = false
        closeChip.setOnClickListener { if (closeChip.isEnabled) onCloseTapped() }
    }

    private fun applyVideoSize() {
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return
        val scale = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        val params = textureView.layoutParams as LayoutParams
        params.width = (videoWidth * scale).roundToInt()
        params.height = (videoHeight * scale).roundToInt()
        params.gravity = Gravity.CENTER
        textureView.layoutParams = params
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyVideoSize()
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        val surface = Surface(surfaceTexture)
        this.surface = surface
        if (!stopped) player.setSurface(surface)
        surfaceReady = true
        maybeStart()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        if (!stopped) runCatching { player.setSurface(null) }
        surface?.release()
        surface = null
        surfaceReady = false
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun MediaPlayer.isPlayingSafely(): Boolean = runCatching { isPlaying }.getOrDefault(false)

    private companion object {
        const val TICK_MS = 250L
    }
}
