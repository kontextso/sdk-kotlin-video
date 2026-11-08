package so.kontext.video.releasecheck

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.tencent.rtmp.ITXVodPlayListener
import com.tencent.rtmp.TXVodPlayer
import com.tencent.rtmp.ui.TXCloudVideoView
import so.kontext.video.KontextVideo.Content
import so.kontext.video.VideoAds
import so.kontext.video.txplayer.KontextTXVodPlayer
import so.kontext.video.txplayer.track

/**
 * The Tencent Cloud Player integration, after R8: a `TXVodPlayer` tracked, its play events handed on.
 * `adb shell am start -n so.kontext.video.releasecheck/.TXPlayerEpisodeActivity`; it logs each play event under
 * the tag "ReleaseCheck". Playback needs the app's Player SDK license, which this app does not have.
 */
class TXPlayerEpisodeActivity : Activity() {
    private lateinit var player: TXVodPlayer
    private lateinit var ads: VideoAds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TXCloudVideoView(this)
        setContentView(view)
        player = TXVodPlayer(this).apply {
            setPlayerView(view)
            setVodListener(object : ITXVodPlayListener {
                override fun onPlayEvent(player: TXVodPlayer, event: Int, param: Bundle?) {
                    Log.i(TAG, "play event $event")
                    KontextTXVodPlayer.onPlayEvent(player, event)
                }

                override fun onNetStatus(player: TXVodPlayer, status: Bundle?) = Unit
            })
        }
        ads = VideoAds(Content("release-check-episode-2", "en") { title = "Episode 2" })
        ads.track(player, currentItem = { "episode-2" })
        player.startVodPlay(SAMPLE_VIDEO)
    }

    override fun onDestroy() {
        super.onDestroy()
        ads.destroy()
        player.stopPlay(true)
    }

    private companion object {
        const val TAG = "ReleaseCheck"
        const val SAMPLE_VIDEO = "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
    }
}
