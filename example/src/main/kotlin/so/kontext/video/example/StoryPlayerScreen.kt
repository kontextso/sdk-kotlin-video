package so.kontext.video.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import so.kontext.video.VideoAds
import so.kontext.video.model.VideoAdFormat
import so.kontext.video.model.VideoSeries

/**
 * Fullscreen vertical feed of a story's episodes — swipe up/down to change
 * episode. Each episode gets its own [VideoAds] handle. The handle for the
 * *next* episode is created and loaded while the current one plays, so its
 * ads are ready the moment the viewer swipes; the previous episode's handle
 * is destroyed when the viewer moves on.
 */
@Composable
fun StoryPlayerScreen(story: Story, autoPauseLoop: Boolean, onBack: () -> Unit) {
    val pagerState = rememberPagerState { story.episodes.size }
    val handles = remember { mutableStateMapOf<String, VideoAds>() }
    var activeIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(pagerState.settledPage) {
        val index = pagerState.settledPage
        val keep = listOfNotNull(story.episodes.getOrNull(index), story.episodes.getOrNull(index + 1))
        for (episode in keep) {
            if (handles[episode.id] == null) handles[episode.id] = prepareAds(story, episode)
        }
        for (id in handles.keys.toList()) {
            if (keep.none { it.id == id }) handles.remove(id)?.destroy()
        }
        activeIndex = index
    }

    DisposableEffect(Unit) {
        onDispose {
            for (handle in handles.values) handle.destroy()
            handles.clear()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val episode = story.episodes[page]
            EpisodePlayerPage(
                story = story,
                episode = episode,
                isActive = activeIndex == page,
                ads = if (activeIndex == page) handles[episode.id] else null,
                autoPauseLoop = autoPauseLoop,
            )
        }
        // A back chevron, not an X — the X is reserved for closing ads.
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 8.dp, top = 8.dp)
                .align(Alignment.TopStart)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape),
        ) {
            Icon(imageVector = chevronLeft(), contentDescription = "Back", tint = Color.White)
        }
    }
}

private fun prepareAds(story: Story, episode: Episode): VideoAds {
    val handle = Ads.session.createAds(
        videoId = episode.id,
        title = episode.title,
        durationMs = 20_500,
        language = "en",
        episodeNumber = episode.number,
        series = VideoSeries(
            id = story.id,
            title = story.title,
            description = "A short-drama series from the demo catalog.",
            keywords = listOf(story.genre.lowercase()),
            episodeCount = story.episodes.size,
        ),
    )
    handle.load(listOf(VideoAdFormat.PAUSE, VideoAdFormat.INTERSTITIAL, VideoAdFormat.OVERLAY))
    return handle
}

private fun chevronLeft(): ImageVector = ImageVector.Builder(
    name = "chevronLeft",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(15.41f, 7.41f)
        lineTo(14f, 6f)
        lineTo(8f, 12f)
        lineTo(14f, 18f)
        lineTo(15.41f, 16.59f)
        lineTo(10.83f, 12f)
        close()
    }
}.build()
