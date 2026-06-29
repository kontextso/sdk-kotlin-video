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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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

    val exoPlayer = remember(current.id) {
        Log.d(TAG, "create id=${current.id} url=${current.streamUrl}")
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(current.toMediaItem())
            // Play once, then auto-advance to the next film (see STATE_ENDED
            // below) — no single-item repeat, so the stream actually ends.
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    Log.d(TAG, "[${current.id}] videoSize=${videoSize.width}x${videoSize.height}")
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        Log.d(TAG, "[${current.id}] ended -> autoplay next")
                        current = filmRelativeTo(current.id, 1).toChannel()
                    }
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
            exoPlayer.release()
        }
    }

    // Wrap the player so the controller's skip-previous / skip-next buttons
    // step through the film list instead of seeking within the single item.
    // Recreated per film, so it always steps relative to the current one; the
    // commands are forced available so the buttons stay enabled on a 1-item
    // timeline.
    val player = remember(exoPlayer) {
        object : ForwardingPlayer(exoPlayer) {
            private fun step(delta: Int) {
                current = filmRelativeTo(current.id, delta).toChannel()
            }

            override fun seekToNext() = step(1)
            override fun seekToNextMediaItem() = step(1)
            override fun seekToPrevious() = step(-1)
            override fun seekToPreviousMediaItem() = step(-1)

            override fun isCommandAvailable(command: Int): Boolean = when (command) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                else -> super.isCommandAvailable(command)
            }

            override fun getAvailableCommands(): Player.Commands =
                Player.Commands.Builder()
                    .addAll(super.getAvailableCommands())
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
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
                onToggleFullscreen = { fullscreen = false },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                TopBar()
                PlayerSurface(
                    player = player,
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
                    selectedId = current.id,
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
    player: Player,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    key(player) {
        var qualities by remember { mutableStateOf(emptyList<VideoQuality>()) }
        var selectedHeight by remember { mutableStateOf<Int?>(null) } // null = Auto (adaptive)
        var menuOpen by remember { mutableStateOf(false) }

        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    qualities = videoQualities(tracks)
                }
            }
            player.addListener(listener)
            qualities = videoQualities(player.currentTracks)
            onDispose { player.removeListener(listener) }
        }

        Box(modifier = modifier) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.view_player, null) as PlayerView).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setPlayer(player)
                        setFullscreenButtonClickListener { onToggleFullscreen() }
                    }
                },
                onRelease = { it.player = null },
            )

            // Quality picker — only when the stream offers more than one
            // resolution. ExoPlayer is adaptive ("Auto"); picking a height
            // pins that rendition via a track-selection override.
            if (qualities.size > 1) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Text(
                        text = "Quality · " + (selectedHeight?.let { "${it}p" } ?: "Auto"),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC000000))
                            .clickable { menuOpen = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Auto") },
                            onClick = {
                                selectQuality(player, null)
                                selectedHeight = null
                                menuOpen = false
                            },
                        )
                        qualities.forEach { quality ->
                            DropdownMenuItem(
                                text = { Text("${quality.height}p") },
                                onClick = {
                                    selectQuality(player, quality)
                                    selectedHeight = quality.height
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A selectable video rendition (one resolution) within the current tracks. */
private data class VideoQuality(val height: Int, val group: TrackGroup, val trackIndex: Int)

/** Distinct video resolutions the player can currently switch to, high → low. */
private fun videoQualities(tracks: Tracks): List<VideoQuality> {
    val result = mutableListOf<VideoQuality>()
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_VIDEO) continue
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val height = group.getTrackFormat(i).height
            if (height != Format.NO_VALUE) {
                result += VideoQuality(height, group.mediaTrackGroup, i)
            }
        }
    }
    return result.distinctBy { it.height }.sortedByDescending { it.height }
}

/** Pins [quality]'s rendition, or clears the override (null = back to Auto). */
private fun selectQuality(player: Player, quality: VideoQuality?) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        .apply {
            if (quality != null) {
                addOverride(TrackSelectionOverride(quality.group, listOf(quality.trackIndex)))
            }
        }
        .build()
}

/** The film [delta] steps from the one with [fromId] in [SAMPLE_FILMS], wrapping around. */
private fun filmRelativeTo(fromId: String, delta: Int): Film {
    val size = SAMPLE_FILMS.size
    val index = SAMPLE_FILMS.indexOfFirst { it.toChannel().id == fromId }.coerceAtLeast(0)
    return SAMPLE_FILMS[((index + delta) % size + size) % size]
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

/**
 * Vertically-scrollable list of films; tapping a row plays it. The row whose
 * channel is currently playing ([selectedId]) gets a highlighted background.
 */
@Composable
private fun FilmList(
    films: List<Film>,
    selectedId: String,
    onClick: (Film) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(films) { film ->
            FilmRow(
                film = film,
                selected = film.toChannel().id == selectedId,
                onClick = { onClick(film) },
            )
        }
    }
}

@Composable
private fun FilmRow(film: Film, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF2A2A2A) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = film.tech,
                color = Color(0xFF6E6E6E),
                fontSize = 11.sp,
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
