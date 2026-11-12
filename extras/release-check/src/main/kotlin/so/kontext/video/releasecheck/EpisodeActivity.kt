package so.kontext.video.releasecheck

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import so.kontext.video.AdFormat
import so.kontext.video.KontextVideo.Content
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAdListener
import so.kontext.video.VideoAds
import so.kontext.video.VideoAdCallback
import so.kontext.video.media3.track

/**
 * The integration a publisher writes, after R8: an episode, its ads, an overlay requested, loaded and shown.
 * Each step logs under the tag "ReleaseCheckApp", so a run on a device shows how far the minified SDK got.
 */
class EpisodeActivity : Activity(), VideoAdListener {
    private lateinit var player: ExoPlayer
    private lateinit var ads: VideoAds
    private lateinit var adArea: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(SAMPLE_VIDEO))
            prepare()
            play()
        }
        val root = FrameLayout(this)
        root.addView(PlayerView(this).apply { player = this@EpisodeActivity.player }, match())
        adArea = FrameLayout(this)
        root.addView(adArea, match())
        setContentView(root)

        ads = VideoAds(Content("release-check-episode-1", "en") { title = "Episode 1" }, section = "release-check")
        ads.track(player)
        ads.format(AdFormat.OVERLAY, object : VideoAdCallback {
            override fun onAd(ad: VideoAd) {
                Log.i(TAG, "format: ${ad.format} at ${ad.price.value} ${ad.price.currency}")
                ad.listener = this@EpisodeActivity
                ad.load(object : VideoAdCallback {
                    override fun onAd(ad: VideoAd) {
                        Log.i(TAG, "load: ready")
                        // show throws for routine outcomes (expired, another ad showing): never uncaught.
                        try {
                            ad.show(adArea)
                        } catch (error: VideoAdException) {
                            Log.w(TAG, "show: $error")
                        }
                    }

                    override fun onError(error: VideoAdException) {
                        Log.w(TAG, "load: $error")
                    }
                })
            }

            override fun onError(error: VideoAdException) {
                Log.w(TAG, "format: $error")
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        // The screen owns its ads: they go with it. configChanges keeps it, and them, across a rotation.
        ads.destroy()
        player.release()
    }

    override fun onAdImpression(ad: VideoAd) {
        Log.i(TAG, "impression")
    }

    override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
        Log.i(TAG, "ended: $reason")
    }

    override fun onAdFailed(ad: VideoAd, error: VideoAdException) {
        Log.w(TAG, "failed: $error")
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private companion object {
        const val TAG = "ReleaseCheck"
        const val SAMPLE_VIDEO = "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
    }
}
