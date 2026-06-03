package so.kontext.video.example

import android.view.LayoutInflater
import android.view.ViewGroup
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import okhttp3.OkHttpClient

/**
 * Samsung-TV+-style home screen. Built up step by step:
 *  1. Branded top bar (logo + "Samsung TV Plus")        ✓
 *  2. Docked video player that auto-plays on open        ✓
 *  3. Top-bar action icons (search / cast / menu)        ✓
 *  4. Scrollable category chips                          ✓
 *  5. Scrollable film list — tapping a row plays it      ✓ ← current
 */
@Composable
fun HomeScreen() {
    // The channel currently in the docked player. Tapping a film swaps it.
    var current by remember { mutableStateOf(SAMPLE_FILMS.first().toChannel()) }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar()
            DockedPlayer(
                channel = current,
                // No rounded-corner clip on the player: clipping forces the
                // video SurfaceView into an offscreen layer, which composites
                // incorrectly (the previous film "ghosts" under the new one).
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

        // Cast / search / overflow — actions wired up in later steps.
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
 * Docked (non-fullscreen) player pinned under the top bar. Connects to
 * [PlaybackService] via a long-lived [MediaController]; whenever [channel]
 * changes (a film is tapped) it swaps the media item and keeps playing —
 * mirroring Samsung TV+'s inline player.
 */
@OptIn(UnstableApi::class)
@Composable
private fun DockedPlayer(channel: Channel, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Local ExoPlayer attached directly to the PlayerView — the surface is
    // set synchronously, avoiding the cross-process surface re-handoff a
    // MediaController/MediaSessionService does (which intermittently left a
    // stale frame). Playback still runs through an OkHttp data source, and
    // DefaultMediaSourceFactory applies per-MediaItem DRM.
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(OkHttpClient())))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true
            }
    }
    LaunchedEffect(channel.id) {
        player.setMediaItem(channel.toMediaItem())
        player.prepare()
        player.play()
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // Recreate the PlayerView for each film. Reusing one TextureView across
    // videos of different resolutions leaves a stale scale/transform (the
    // new film renders zoomed/cropped); a fresh view re-fits to the new
    // video size. The single ExoPlayer persists across switches.
    key(channel.id) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.view_player, null) as PlayerView).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setKeepContentOnPlayerReset(false)
                    setPlayer(player)
                }
            },
            onRelease = { it.player = null },
        )
    }
}

/**
 * Horizontally-scrollable category chips below the player, mirroring
 * Samsung TV+'s row (Featured / Favorites / Recent / …). Selection is
 * cosmetic for now; it doesn't filter the film list yet.
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
