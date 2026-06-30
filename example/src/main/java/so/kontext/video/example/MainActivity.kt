package so.kontext.video.example

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.media3.common.Player

/**
 * Compose host. Renders the Samsung-TV+-style [HomeScreen], mirroring the
 * real app's UI stack (Kotlin + Jetpack Compose + Material3).
 *
 * Also owns Picture-in-Picture: [HomeScreen] reports its active player up via
 * [bindPlayer], and this activity builds the PiP params (16:9 aspect, a
 * Play/Pause action) and arms auto-enter so leaving the app while a video
 * plays slides it into a floating window. [HomeScreen] reads [isInPip] to hide
 * its chrome (top bar / list / controls) and show only the video in PiP.
 */
class MainActivity : ComponentActivity() {

    private var player: Player? = null
    private val isInPip = mutableStateOf(false)
    private var playPauseReceiver: BroadcastReceiver? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Keep the action icon (play vs pause) and auto-enter arming current.
            updatePipParams()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerPlayPauseReceiver()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(
                    isInPipMode = isInPip.value,
                    onPlayerChanged = ::bindPlayer,
                )
            }
        }
    }

    /** [HomeScreen] hands us the current player (recreated per film); rebind. */
    private fun bindPlayer(newPlayer: Player?) {
        if (player === newPlayer) return
        player?.removeListener(playerListener)
        player = newPlayer
        newPlayer?.addListener(playerListener)
        updatePipParams()
    }

    private fun updatePipParams() {
        val playing = player?.isPlaying == true
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(playPauseAction(playing)))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Auto-enter PiP on the home gesture, but only while playing.
                    setAutoEnterEnabled(playing)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
        runCatching { setPictureInPictureParams(params) }
    }

    private fun playPauseAction(isPlaying: Boolean): RemoteAction {
        val iconRes = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val title = if (isPlaying) "Pause" else "Play"
        val intent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_PLAY_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(this, iconRes), title, title, intent)
    }

    private fun registerPlayPauseReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_PLAY_PAUSE) return
                player?.let { if (it.isPlaying) it.pause() else it.play() }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ACTION_PLAY_PAUSE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        playPauseReceiver = receiver
    }

    /** Pre-API-31 fallback: auto-enter isn't available, so enter on home press. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            player?.isPlaying == true &&
            !isInPictureInPictureMode
        ) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Drives HomeScreen: video-only in PiP, full chrome when expanded back.
        isInPip.value = isInPictureInPictureMode
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.removeListener(playerListener)
        playPauseReceiver?.let { runCatching { unregisterReceiver(it) } }
    }

    private companion object {
        const val ACTION_PLAY_PAUSE = "so.kontext.video.example.PLAY_PAUSE"
    }
}
