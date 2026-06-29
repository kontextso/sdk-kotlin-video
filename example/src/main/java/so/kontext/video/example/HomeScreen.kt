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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
    var showSettings by remember { mutableStateOf(false) }
    // Quality cap chosen in Settings (null = Auto). A standard ladder applied to
    // every film; the player picks the best rendition at or below it and falls
    // back gracefully when a stream can't honour the choice.
    var maxVideoHeight by remember { mutableStateOf<Int?>(null) }
    // The renditions the current stream actually exposes — shown (small) on the
    // playing film's row so it's clear which formats a stream supports.
    var availableHeights by remember(current.id) { mutableStateOf(emptyList<Int>()) }
    // The rendition actually on screen now (adaptive may change it over time) —
    // used to highlight which of the available formats is currently playing.
    var currentHeight by remember(current.id) { mutableStateOf<Int?>(null) }
    // Per-film playback position, remembered for this session so re-opening a
    // film resumes where it was left off (see rememberPosition).
    val resumePositions = remember { mutableMapOf<String, Long>() }

    val exoPlayer = remember(current.id) {
        Log.d(TAG, "create id=${current.id} url=${current.streamUrl}")
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(current.toMediaItem())
            // Play once, then auto-advance to the next film (see STATE_ENDED
            // below) — no single-item repeat, so the stream actually ends.
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            // Resume where this film was left off earlier this session.
            resumePositions[current.id]?.let { seekTo(it) }
            // Apply the session-wide quality cap to this film too.
            applyQualityCap(this, maxVideoHeight)
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    currentHeight = videoSize.height.takeIf { it > 0 }
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
        val savedId = current.id
        onDispose {
            rememberPosition(resumePositions, savedId, exoPlayer)
            Log.d(TAG, "release id=$savedId")
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

            // Skip-previous restarts the current film when past the threshold,
            // else steps to the previous film (standard player behavior).
            override fun seekToPrevious() {
                if (exoPlayer.currentPosition > RESTART_THRESHOLD_MS) exoPlayer.seekTo(0) else step(-1)
            }

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

    // Detect which renditions the current stream exposes (for the "Available:"
    // line). Detection only — quality selection is the static ladder in Settings.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                availableHeights = availableVideoHeights(tracks)
            }
        }
        player.addListener(listener)
        availableHeights = availableVideoHeights(player.currentTracks)
        onDispose { player.removeListener(listener) }
    }

    // Landscape + immersive while fullscreen; restore on exit / leaving.
    LaunchedEffect(fullscreen) { applyFullscreen(activity, fullscreen) }
    DisposableEffect(Unit) {
        onDispose { applyFullscreen(activity, false) }
    }
    if (fullscreen) {
        BackHandler { fullscreen = false }
    }
    if (showSettings) {
        BackHandler { showSettings = false }
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        when {
            showSettings -> SettingsScreen(
                selectedHeight = maxVideoHeight,
                onSelectQuality = { height ->
                    maxVideoHeight = height
                    applyQualityCap(player, height)
                },
                onBack = { showSettings = false },
            )

            fullscreen -> PlayerSurface(
                player = player,
                onToggleFullscreen = { fullscreen = false },
                modifier = Modifier.fillMaxSize(),
            )

            else -> Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                TopBar(onOpenSettings = { showSettings = true })
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
                    availableHeights = availableHeights,
                    currentHeight = currentHeight,
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
        AndroidView(
            modifier = modifier,
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
    }
}

/** Standard quality ladder offered (alongside Auto) for every film, high → low. */
private val QUALITY_OPTIONS = listOf(2160, 1440, 1080, 720, 480, 360, 240)

/**
 * Caps adaptive selection at [maxHeight] (null = Auto, no cap). Works on any
 * stream: the player picks the best rendition at or below the cap, and falls
 * back to the closest available when nothing fits — so an unsupported choice
 * degrades to default behavior rather than failing.
 */
private fun applyQualityCap(player: Player, maxHeight: Int?) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .apply {
            if (maxHeight == null) {
                clearVideoSizeConstraints()
            } else {
                setMaxVideoSize(Int.MAX_VALUE, maxHeight)
            }
        }
        .build()
}

/** Distinct video resolutions the current stream actually exposes, high → low. */
private fun availableVideoHeights(tracks: Tracks): List<Int> {
    val heights = mutableListOf<Int>()
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_VIDEO) continue
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val height = group.getTrackFormat(i).height
            if (height != Format.NO_VALUE) {
                heights += height
            }
        }
    }
    return heights.distinct().sortedDescending()
}

/** The film [delta] steps from the one with [fromId] in [SAMPLE_FILMS], wrapping around. */
private fun filmRelativeTo(fromId: String, delta: Int): Film {
    val size = SAMPLE_FILMS.size
    val index = SAMPLE_FILMS.indexOfFirst { it.toChannel().id == fromId }.coerceAtLeast(0)
    return SAMPLE_FILMS[((index + delta) % size + size) % size]
}

/** Past this position, skip-previous restarts the current film instead of stepping back. */
private const val RESTART_THRESHOLD_MS = 3_000L

/** Below this, a position is "the start" — not worth resuming to. */
private const val RESUME_MIN_MS = 3_000L

/** Within this of the end, a film counts as watched — restart from 0 next time. */
private const val RESUME_END_MARGIN_MS = 3_000L

/**
 * Remembers [player]'s position for [id] so re-opening the film resumes there.
 * Drops the entry (→ restart from 0) when the film finished, is near its end,
 * or is still near the start.
 */
private fun rememberPosition(positions: MutableMap<String, Long>, id: String, player: Player) {
    val position = player.currentPosition
    val duration = player.duration
    val finished = player.playbackState == Player.STATE_ENDED ||
        (duration > 0 && position >= duration - RESUME_END_MARGIN_MS)
    if (finished || position < RESUME_MIN_MS) {
        positions.remove(id)
    } else {
        positions[id] = position
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
private fun TopBar(onOpenSettings: () -> Unit) {
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

        // Cast / search are visual only; the overflow (⋮) opens the settings page.
        TopBarIcon(R.drawable.ic_cast, "Cast")
        TopBarIcon(R.drawable.ic_search, "Search")
        IconButton(onClick = onOpenSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = "Settings",
                tint = Color.White,
            )
        }
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
 * Full-screen settings page reached from the top bar's ⋮ icon (like Samsung
 * TV+). Currently one section — Quality: a fixed standard ladder (Auto + the
 * [QUALITY_OPTIONS] heights) offered for every film, with the current choice
 * checked. More sections can be added here as the app grows.
 */
@Composable
private fun SettingsScreen(
    selectedHeight: Int?,
    onSelectQuality: (Int?) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text(text = "←", color = Color.White, fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Settings",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        SettingsSectionHeader("Quality")
        SettingRow(label = "Auto", selected = selectedHeight == null) { onSelectQuality(null) }
        QUALITY_OPTIONS.forEach { height ->
            SettingRow(
                label = "${height}p",
                selected = selectedHeight == height,
            ) { onSelectQuality(height) }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFF9E9E9E),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(text = "✓", color = Color.White, fontSize = 16.sp)
        }
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
    availableHeights: List<Int>,
    currentHeight: Int?,
    onClick: (Film) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(films) { film ->
            val isSelected = film.toChannel().id == selectedId
            FilmRow(
                film = film,
                selected = isSelected,
                // Detected renditions are only known for the playing stream.
                availableHeights = if (isSelected) availableHeights else emptyList(),
                currentHeight = if (isSelected) currentHeight else null,
                onClick = { onClick(film) },
            )
        }
    }
}

@Composable
private fun FilmRow(
    film: Film,
    selected: Boolean,
    availableHeights: List<Int>,
    currentHeight: Int?,
    onClick: () -> Unit,
) {
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
            // Actual renditions detected in the playing stream — for testing
            // which formats a source exposes. The one currently on screen
            // (adaptive picks it) is highlighted brighter + bold.
            if (availableHeights.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = AVAILABLE_BLUE)) { append("Available: ") }
                        availableHeights.forEachIndexed { index, height ->
                            if (index > 0) {
                                withStyle(SpanStyle(color = AVAILABLE_BLUE)) { append(" · ") }
                            }
                            val playing = height == currentHeight
                            withStyle(
                                SpanStyle(
                                    color = if (playing) PLAYING_BLUE else AVAILABLE_BLUE,
                                    fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                                ),
                            ) {
                                append("${height}p")
                            }
                        }
                    },
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** "Available:" formats line: base blue for renditions, brighter for the one playing now. */
private val AVAILABLE_BLUE = Color(0xFF4FC3F7)
private val PLAYING_BLUE = Color(0xFFB3E5FC)

private val CATEGORIES = listOf(
    "Featured",
    "Favorites",
    "Recent",
    "News & Opinion",
    "Sports",
    "Movies",
    "Entertainment",
)
