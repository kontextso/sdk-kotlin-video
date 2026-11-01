package so.kontext.video.publisher

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import so.kontext.video.AdFormat
import so.kontext.video.Content
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAdListener
import so.kontext.video.VideoAds
import so.kontext.video.VideoAdsCallback
import so.kontext.video.media3.track

/**
 * The integration a publisher writes, after R8: an episode, its ads, an overlay requested, loaded and shown.
 * Each step logs under the tag "PublisherApp", so a run on a device shows how far the minified SDK got.
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

        ads = VideoAds(Content("publisher-app-episode-1", "en") { title = "Episode 1" })
        ads.track(player)
        ads.format(AdFormat.OVERLAY, object : VideoAdsCallback<VideoAd> {
            override fun onSuccess(result: VideoAd) {
                Log.i(TAG, "format: ${result.format} at ${result.price.value} ${result.price.currency}")
                result.listener = this@EpisodeActivity
                result.load(object : VideoAdsCallback<VideoAd> {
                    override fun onSuccess(result: VideoAd) {
                        Log.i(TAG, "load: ready")
                        // show throws for routine outcomes (expired, another ad showing): never uncaught.
                        try {
                            result.show(adArea)
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
        const val TAG = "PublisherApp"
        const val SAMPLE_VIDEO = "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
    }
}
