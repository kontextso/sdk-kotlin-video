package so.kontext.video.example

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

private const val TAG = "VideoExample"

/**
 * Samsung-TV+-style home screen:
 *  - branded top bar (logo + title + cast / search / overflow)
 *  - docked 16:9 player that auto-plays on open
 *  - scrollable category chips
 *  - scrollable film list (tap a row to play it)
 *  - fullscreen: tap the player's fullscreen button -> landscape immersive;
 *    Back returns to the docked layout.
 *
 * One fresh [ExoPlayer] per film (keyed by channel id) so switching never
 * bleeds the old video into the new one; the player survives the
 * docked <-> fullscreen switch so playback is continuous.
 */
@OptIn(UnstableApi::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    var current by remember { mutableStateOf(SAMPLE_FILMS.first().toChannel()) }
    var fullscreen by remember { mutableStateOf(false) }

    val player = remember(current.id) {
        Log.d(TAG, "create id=${current.id} url=${current.streamUrl}")
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(current.toMediaItem())
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    Log.d(TAG, "[${current.id}] videoSize=${videoSize.width}x${videoSize.height}")
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "[${current.id}] error=${error.errorCodeName}", error)
                }
            })
            prepare()
        }
    }
    DisposableEffect(current.id) {
        onDispose {
            Log.d(TAG, "release id=${current.id}")
            player.release()
        }
    }

    // Landscape + immersive while fullscreen; restore on exit / leaving.
    LaunchedEffect(fullscreen) { applyFullscreen(activity, fullscreen) }
    DisposableEffect(Unit) {
        onDispose { applyFullscreen(activity, false) }
    }
    if (fullscreen) {
        BackHandler { fullscreen = false }
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        if (fullscreen) {
            PlayerSurface(
                player = player,
                isFullscreen = true,
                onToggleFullscreen = { fullscreen = false },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                TopBar()
                PlayerSurface(
                    player = player,
                    isFullscreen = false,
                    onToggleFullscreen = { fullscreen = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .aspectRatio(16f / 9f)
                        .background(Color.Black),
                )
                Spacer(modifier = Modifier.height(8.dp))
                CategoryChips()
                Spacer(modifier = Modifier.height(8.dp))
                FilmList(
                    films = SAMPLE_FILMS,
                    onClick = { film -> current = film.toChannel() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Renders [player] in a Media3 [PlayerView]. Keyed by the player instance so a
 * new film (new player) gets a fresh view that re-fits to its video size, and
 * so re-hosting between docked / fullscreen re-attaches cleanly. Media3 draws
 * the fullscreen button in the controls; [onToggleFullscreen] handles the tap.
 */
@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(
    player: ExoPlayer,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    key(player) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.view_player, null) as PlayerView).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setPlayer(player)
                    setFullscreenButtonClickListener {
                        Log.d(TAG, "fullscreenButtonClick requested=$it (wasFullscreen=$isFullscreen)")
                        onToggleFullscreen()
                    }
                }
            },
            onRelease = { it.player = null },
        )
    }
}

private fun applyFullscreen(activity: Activity?, fullscreen: Boolean) {
    Log.d(TAG, "applyFullscreen fullscreen=$fullscreen activity=${activity != null}")
    activity ?: return
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (fullscreen) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.samsung_tv_plus_logo),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Samsung TV Plus",
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Cast / search / overflow — visual only for now.
        TopBarIcon(R.drawable.ic_cast, "Cast")
        TopBarIcon(R.drawable.ic_search, "Search")
        TopBarIcon(R.drawable.ic_more_vert, "More")
    }
}

@Composable
private fun TopBarIcon(iconRes: Int, contentDescription: String) {
    IconButton(onClick = { }) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color.White,
        )
    }
}

/**
 * Horizontally-scrollable category chips below the player (Featured /
 * Favorites / Recent / …). Selection is cosmetic for now.
 */
@Composable
private fun CategoryChips(modifier: Modifier = Modifier) {
    var selected by remember { mutableIntStateOf(1) }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(CATEGORIES) { index, label ->
            CategoryChip(
                label = label,
                selected = selected == index,
                onClick = { selected = index },
            )
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.Black else Color(0xFFBBBBBB),
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Vertically-scrollable list of films; tapping a row plays it. */
@Composable
private fun FilmList(films: List<Film>, onClick: (Film) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(films) { film ->
            FilmRow(film = film, onClick = { onClick(film) })
        }
    }
}

@Composable
private fun FilmRow(film: Film, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = film.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 96.dp, height = 56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF222222)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = film.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = film.subtitle,
                color = Color(0xFF9E9E9E),
                fontSize = 13.sp,
            )
        }
    }
}

private val CATEGORIES = listOf(
    "Featured",
    "Favorites",
    "Recent",
    "News & Opinion",
    "Sports",
    "Movies",
    "Entertainment",
)
