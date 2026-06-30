package so.kontext.video.example

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.OrientationEventListener
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import coil.compose.AsyncImage
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import java.util.Locale

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
fun HomeScreen(
    isInPipMode: Boolean = false,
    onPlayerChanged: (Player?) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    // Fullscreen mirrors the device orientation: landscape = immersive player,
    // portrait = docked. The fullscreen button / Back force the opposite
    // orientation (orientationLock); rotating the device does the rest.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullscreen = isLandscape
    var orientationLock by remember { mutableStateOf<Int?>(null) }
    var current by remember { mutableStateOf(SAMPLE_FILMS.first().toChannel()) }
    var showSettings by remember { mutableStateOf(false) }
    // Search page (top-bar magnifier): a fresh page with the playing video
    // docked at the bottom (video left, description right). Same player, so
    // playback is continuous.
    var showSearch by remember { mutableStateOf(false) }
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
    // Selectable subtitle/CC tracks + the chosen one (per film); null = Off.
    var subtitleTracks by remember(current.id) { mutableStateOf(emptyList<TextTrack>()) }
    var selectedSubtitleId by remember(current.id) { mutableStateOf<String?>(null) }
    // Subtitle text size (session-wide); applied to the PlayerView's SubtitleView.
    var subtitleSize by remember { mutableStateOf(SubtitleSize.NORMAL) }
    // How aggressively to buffer ahead. Session-wide; changing it rebuilds the
    // player (LoadControl is fixed at build time) — resume keeps the position.
    var bufferProfile by remember { mutableStateOf(BufferProfile.DEFAULT) }
    // Bumped after clearing the disk cache so the "Cached: …" line re-reads.
    var cacheVersion by remember { mutableIntStateOf(0) }
    // Per-film playback position, remembered for this session so re-opening a
    // film resumes where it was left off (see rememberPosition).
    val resumePositions = remember { mutableMapOf<String, Long>() }

    // Casting: a CastContext (null if Google Play services / Cast is missing) and
    // a CastPlayer that drives a connected receiver via the same Player interface
    // as the local ExoPlayer. While a Cast session is live the docked video plays
    // on the TV instead of the phone (see the session listener below).
    val castContext = remember {
        runCatching { CastContext.getSharedInstance(context.applicationContext) }
            .onFailure { Log.w(TAG, "Cast unavailable", it) }
            .getOrNull()
    }
    val castPlayer = remember(castContext) { castContext?.let { CastPlayer(it) } }
    var isCasting by remember { mutableStateOf(castPlayer?.isCastSessionAvailable == true) }

    val exoPlayer = remember(current.id, bufferProfile) {
        Log.d(TAG, "create id=${current.id} url=${current.streamUrl} buffer=${bufferProfile.label}")
        ExoPlayer.Builder(context)
            // Read-through disk cache: replays / scrub-backs serve from disk.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(VideoCache.dataSourceFactory(context)),
            )
            // Buffer-ahead profile chosen in Settings.
            .setLoadControl(bufferProfile.toLoadControl())
            .build().apply {
            setMediaItem(current.toMediaItem())
            // Play once, then auto-advance to the next film (see STATE_ENDED
            // below) — no single-item repeat, so the stream actually ends.
            repeatMode = Player.REPEAT_MODE_OFF
            // Don't start locally if we're already casting — the receiver plays
            // it (a film switched mid-cast is loaded onto the TV, see below).
            playWhenReady = !isCasting
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
    // Keyed on the player instance so a rebuild (film switch OR buffer-profile
    // change) releases the old player and saves its position first.
    DisposableEffect(exoPlayer) {
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
                val parsedText = parseTextTracks(tracks)
                subtitleTracks = parsedText
                // Re-assert the chosen subtitle after a player rebuild (e.g. a
                // buffer-profile change keeps the film but builds a new player).
                // (Audio selection lives in the player's own track menu now.)
                selectedSubtitleId?.let { id ->
                    parsedText.firstOrNull { it.id == id }?.let { selectSubtitle(player, it) }
                }
            }
        }
        player.addListener(listener)
        availableHeights = availableVideoHeights(player.currentTracks)
        subtitleTracks = parseTextTracks(player.currentTracks)
        onDispose { player.removeListener(listener) }
    }

    // Report the active player to the Activity so it can drive Picture-in-Picture
    // (aspect ratio, the Play/Pause action, auto-enter while playing).
    LaunchedEffect(player) { onPlayerChanged(player) }

    // The listener is registered once (castPlayer is stable), but the handoff
    // needs the *current* film + local player — keep live refs so the callbacks
    // don't capture stale ones (both are recreated per film).
    val liveLocal = rememberUpdatedState(exoPlayer)
    val liveChannel = rememberUpdatedState(current)
    DisposableEffect(castPlayer) {
        val cp = castPlayer
        if (cp == null) {
            onDispose { }
        } else {
            val listener = object : SessionAvailabilityListener {
                // TV connected: pause locally, hand the film + position to the receiver.
                override fun onCastSessionAvailable() {
                    val local = liveLocal.value
                    val resumeAt = local.currentPosition
                    local.playWhenReady = false
                    cp.setMediaItem(liveChannel.value.toMediaItem(), resumeAt)
                    cp.playWhenReady = true
                    cp.prepare()
                    isCasting = true
                    Log.d(TAG, "cast: connected -> handing off ${liveChannel.value.id} at ${resumeAt}ms")
                }

                // TV disconnected: resume locally where the receiver left off.
                override fun onCastSessionUnavailable() {
                    val local = liveLocal.value
                    val resumeAt = cp.currentPosition.takeIf { it != C.TIME_UNSET } ?: 0L
                    local.seekTo(resumeAt)
                    local.playWhenReady = true
                    isCasting = false
                    Log.d(TAG, "cast: disconnected -> resuming local at ${resumeAt}ms")
                }
            }
            cp.setSessionAvailabilityListener(listener)
            onDispose {
                cp.setSessionAvailabilityListener(null)
                cp.release()
            }
        }
    }

    // Switching films while casting loads the new one onto the receiver. Keyed on
    // the film only: connecting (which flips isCasting) doesn't re-run this, so the
    // session listener's position-preserving handoff above isn't clobbered.
    LaunchedEffect(current.id) {
        if (isCasting && castPlayer != null) {
            castPlayer.setMediaItem(current.toMediaItem())
            castPlayer.prepare()
            castPlayer.playWhenReady = true
            Log.d(TAG, "cast: load ${current.id} on receiver")
        }
    }

    // Immersive system bars follow fullscreen; restore on leaving.
    LaunchedEffect(fullscreen) { applyImmersive(activity, fullscreen) }
    DisposableEffect(Unit) {
        onDispose { applyImmersive(activity, false) }
    }

    // While a button/Back override is pending, force that orientation; otherwise
    // follow the sensor so rotating the device drives fullscreen.
    LaunchedEffect(orientationLock) {
        activity?.requestedOrientation = when (orientationLock) {
            Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }
    // Hand control back to the sensor once the device physically reaches the
    // forced orientation, so the next real rotation toggles fullscreen again.
    DisposableEffect(activity) {
        val listener = activity?.let {
            object : OrientationEventListener(it) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                    val physical = physicalOrientation(orientation) ?: return
                    if (orientationLock != null && physical == orientationLock) {
                        orientationLock = null
                    }
                }
            }
        }
        listener?.takeIf { it.canDetectOrientation() }?.enable()
        onDispose { listener?.disable() }
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    if (fullscreen) {
        BackHandler { orientationLock = Configuration.ORIENTATION_PORTRAIT }
    }
    if (showSettings) {
        BackHandler { showSettings = false }
    }
    if (showSearch) {
        BackHandler { showSearch = false }
    }

    // The player the surface shows: the receiver-driving CastPlayer while casting,
    // otherwise the local one. Both implement Player, so the PlayerView and its
    // controls are agnostic to which is active.
    val surfacePlayer: Player = if (isCasting && castPlayer != null) castPlayer else player

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        when {
            // In the PiP floating window: just the video, no chrome or controls.
            isInPipMode -> PlayerSurface(
                player = surfacePlayer,
                subtitleSizeFraction = subtitleSize.fraction,
                showController = false,
                onToggleFullscreen = {},
                modifier = Modifier.fillMaxSize(),
            )

            showSettings -> {
                val cachedBytes = remember(cacheVersion) { VideoCache.cachedBytes(context) }
                SettingsScreen(
                    selectedHeight = maxVideoHeight,
                    onSelectQuality = { height ->
                        maxVideoHeight = height
                        applyQualityCap(player, height)
                    },
                    subtitleTracks = subtitleTracks,
                    selectedSubtitleId = selectedSubtitleId,
                    onSelectSubtitle = { track ->
                        selectSubtitle(player, track)
                        selectedSubtitleId = track?.id
                    },
                    subtitleSize = subtitleSize,
                    onSelectSubtitleSize = { subtitleSize = it },
                    bufferProfile = bufferProfile,
                    onSelectBufferProfile = { profile ->
                        // Save the live position so the rebuilt player resumes in place.
                        rememberPosition(resumePositions, current.id, player)
                        bufferProfile = profile
                    },
                    cachedBytes = cachedBytes,
                    onClearCache = {
                        VideoCache.clear(context)
                        cacheVersion++
                    },
                    onBack = { showSettings = false },
                )
            }

            showSearch -> SearchScreen(
                player = surfacePlayer,
                subtitleSizeFraction = subtitleSize.fraction,
                film = SAMPLE_FILMS.firstOrNull { it.toChannel().id == current.id },
                fallbackTitle = current.name,
                onBack = { showSearch = false },
            )

            fullscreen -> PlayerSurface(
                player = surfacePlayer,
                subtitleSizeFraction = subtitleSize.fraction,
                onToggleFullscreen = { orientationLock = Configuration.ORIENTATION_PORTRAIT },
                modifier = Modifier.fillMaxSize(),
            )

            else -> Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                TopBar(
                    castAvailable = castContext != null,
                    onOpenSearch = { showSearch = true },
                    onOpenSettings = { showSettings = true },
                )
                PlayerSurface(
                    player = surfacePlayer,
                    subtitleSizeFraction = subtitleSize.fraction,
                    onToggleFullscreen = { orientationLock = Configuration.ORIENTATION_LANDSCAPE },
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
    subtitleSizeFraction: Float,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    showController: Boolean = true,
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
                    // PiP windows are tiny — show only the video, no tap controls.
                    useController = showController
                    setPlayer(player)
                    setFullscreenButtonClickListener { onToggleFullscreen() }
                }
            },
            // Subtitle size lives on the SubtitleView, not the player, so it's
            // re-applied here whenever the chosen size changes.
            update = { it.subtitleView?.setFractionalTextSize(subtitleSizeFraction) },
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

/**
 * How aggressively the player buffers ahead. Maps to Media3's
 * [DefaultLoadControl] buffer durations (min, max, start-playback,
 * resume-after-rebuffer in ms). Low latency starts fast and holds little;
 * Large buffer rides out flaky networks at the cost of more memory / data.
 */
private enum class BufferProfile(
    val label: String,
    val minMs: Int,
    val maxMs: Int,
    val playbackMs: Int,
    val rebufferMs: Int,
) {
    LOW("Low latency", 5_000, 15_000, 1_000, 2_000),
    DEFAULT("Default", 50_000, 50_000, 2_500, 5_000),
    LARGE("Large buffer", 60_000, 120_000, 2_500, 5_000),
}

/** Builds a [LoadControl] with this profile's buffer durations. */
@OptIn(UnstableApi::class)
private fun BufferProfile.toLoadControl(): LoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(minMs, maxMs, playbackMs, rebufferMs)
        .build()

/** Human-readable byte count for the cache size line, e.g. "12.3 MB". */
private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) {
        String.format(Locale.US, "%.2f GB", mb / 1024)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

/** Human-readable name for a track: explicit label, else language, else index. */
private fun trackLabel(format: Format, index: Int): String {
    format.label?.let { return it }
    val lang = format.language?.takeIf { it.isNotBlank() && it != "und" }
    return lang?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } } ?: "Track ${index + 1}"
}

/** A selectable subtitle / CC track within the current tracks. */
private data class TextTrack(val id: String, val label: String, val group: TrackGroup, val trackIndex: Int)

/** Selectable subtitle tracks the current stream exposes, labelled by language. */
private fun parseTextTracks(tracks: Tracks): List<TextTrack> {
    val result = mutableListOf<TextTrack>()
    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            result += TextTrack(
                id = "$groupIndex:$i",
                label = trackLabel(group.getTrackFormat(i), i),
                group = group.mediaTrackGroup,
                trackIndex = i,
            )
        }
    }
    return result
}

/** Shows [track]'s subtitles, or turns them off (null disables the text type). */
private fun selectSubtitle(player: Player, track: TextTrack?) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)
        .apply {
            if (track != null) {
                addOverride(TrackSelectionOverride(track.group, listOf(track.trackIndex)))
            }
        }
        .build()
}

/** Subtitle text size as a fraction of the view height (Media3 default ≈ 0.0533). */
private enum class SubtitleSize(val label: String, val fraction: Float) {
    SMALL("Small", 0.04f),
    NORMAL("Normal", 0.0533f),
    LARGE("Large", 0.08f),
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

/** Hides the system bars in fullscreen (immersive), shows them otherwise. */
private fun applyImmersive(activity: Activity?, fullscreen: Boolean) {
    activity ?: return
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (fullscreen) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

/** Maps a device rotation angle (0–359°) to a portrait/landscape bucket. */
private fun physicalOrientation(angle: Int): Int? = when (angle) {
    in 0..30, in 330..360 -> Configuration.ORIENTATION_PORTRAIT
    in 60..120 -> Configuration.ORIENTATION_LANDSCAPE
    in 150..210 -> Configuration.ORIENTATION_PORTRAIT
    in 240..300 -> Configuration.ORIENTATION_LANDSCAPE
    else -> null
}

/**
 * Search page reached from the top-bar magnifier. A fresh page — a (placeholder)
 * search box filling the top — with the currently-playing video docked at the
 * bottom: video on the left, its description on the right. It renders the same
 * [player], so moving here keeps playback going (only the surface re-attaches).
 */
@OptIn(UnstableApi::class)
@Composable
private fun SearchScreen(
    player: Player,
    subtitleSizeFraction: Float,
    film: Film?,
    fallbackTitle: String,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        // Top: back + search box + (empty) results area, filling the page.
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                    text = "Search",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search films…") },
                leadingIcon = {
                    Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (query.isBlank()) "Type to search films" else "No results for \"$query\"",
                    color = Color(0xFF6E6E6E),
                    fontSize = 14.sp,
                )
            }
        }

        // Bottom dock: the playing video (left) + its description (right).
        // Tapping anywhere on it returns home (the video has its own overlay
        // below, since the PlayerView would otherwise swallow the touch).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161616))
                .clickable { onBack() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                PlayerSurface(
                    player = player,
                    subtitleSizeFraction = subtitleSizeFraction,
                    showController = false,
                    onToggleFullscreen = {},
                    modifier = Modifier.fillMaxSize(),
                )
                // Transparent overlay on top of the PlayerView (which would
                // otherwise swallow the touch): tapping the video returns home.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { onBack() },
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = film?.title ?: fallbackTitle,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (film != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = film.subtitle, color = Color(0xFF9E9E9E), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = film.tech, color = Color(0xFF6E6E6E), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    castAvailable: Boolean,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
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

        // Real Cast button (device picker + connection state) when Cast is
        // available; search stays visual; the overflow (⋮) opens settings.
        if (castAvailable) {
            CastButton()
        } else {
            TopBarIcon(R.drawable.ic_cast, "Cast")
        }
        TopBarIcon(R.drawable.ic_search, "Search", onClick = onOpenSearch)
        IconButton(onClick = onOpenSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = "Settings",
                tint = Color.White,
            )
        }
    }
}

/**
 * The Google Cast button — a framework [MediaRouteButton] (device picker +
 * connection state) hosted in Compose. The Activity theme sets `mediaRouteTheme`
 * so the button and its picker dialog style correctly; the dialog also needs the
 * host Activity to be a FragmentActivity (see [MainActivity]).
 */
@Composable
private fun CastButton() {
    AndroidView(
        modifier = Modifier.size(48.dp),
        factory = { ctx ->
            // The button derives its colours from a MediaRouter theme; the
            // Compose context doesn't carry the Activity theme's attrs, so wrap
            // it explicitly (its picker dialog uses the Activity context, which
            // does carry them — see Theme.KontextVideo).
            val themed = ContextThemeWrapper(ctx, R.style.Theme_KontextVideo_Cast)
            MediaRouteButton(themed).apply {
                CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, this)
            }
        },
    )
}

@Composable
private fun TopBarIcon(iconRes: Int, contentDescription: String, onClick: () -> Unit = {}) {
    IconButton(onClick = onClick) {
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
    subtitleTracks: List<TextTrack>,
    selectedSubtitleId: String?,
    onSelectSubtitle: (TextTrack?) -> Unit,
    subtitleSize: SubtitleSize,
    onSelectSubtitleSize: (SubtitleSize) -> Unit,
    bufferProfile: BufferProfile,
    onSelectBufferProfile: (BufferProfile) -> Unit,
    cachedBytes: Long,
    onClearCache: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
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

        // (Audio language selection lives in the player's own track menu — the
        // gear in the playback controls — so it isn't duplicated here.)

        // Subtitles / CC — only when the stream carries text tracks. Off by default.
        if (subtitleTracks.isNotEmpty()) {
            SettingsSectionHeader("Subtitles")
            SettingRow(label = "Off", selected = selectedSubtitleId == null) { onSelectSubtitle(null) }
            subtitleTracks.forEach { track ->
                SettingRow(
                    label = track.label,
                    selected = track.id == selectedSubtitleId,
                ) { onSelectSubtitle(track) }
            }

            SettingsSectionHeader("Subtitle size")
            SubtitleSize.entries.forEach { size ->
                SettingRow(
                    label = size.label,
                    selected = size == subtitleSize,
                ) { onSelectSubtitleSize(size) }
            }
        }

        // How much to buffer ahead (rebuilds the player; resumes in place).
        SettingsSectionHeader("Buffering")
        BufferProfile.entries.forEach { profile ->
            SettingRow(
                label = profile.label,
                selected = profile == bufferProfile,
            ) { onSelectBufferProfile(profile) }
        }

        // Disk cache — replays serve from disk. Shows current size; tap to clear.
        SettingsSectionHeader("Cache")
        SettingRow(label = "Clear cache · ${formatBytes(cachedBytes)}", selected = false) { onClearCache() }
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
