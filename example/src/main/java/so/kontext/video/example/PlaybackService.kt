package so.kontext.video.example

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import okhttp3.OkHttpClient

/**
 * Media3 [MediaSessionService] that owns the [ExoPlayer] and exposes a
 * [MediaSession]. Mirrors Samsung TV+'s player architecture — a
 * MediaSession-backed foreground media-playback service
 * (`...library.player...PlayerMediaSession`) — so the UI controls the
 * player through a `MediaController` and playback survives backgrounding.
 *
 * Playback runs through an OkHttp data source (Samsung uses
 * `media3.datasource.okhttp`); `DefaultMediaSourceFactory` resolves HLS
 * and applies per-`MediaItem` DRM (Widevine/PlayReady).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val dataSourceFactory = OkHttpDataSource.Factory(OkHttpClient())
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
