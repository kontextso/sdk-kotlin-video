@file:JvmName("VideoAdCompose")

package so.kontext.video.compose

import android.widget.FrameLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import so.kontext.video.KontextVideo.Content
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAdListener
import so.kontext.video.VideoAds
import so.kontext.video.internal.rendering.VideoAdSlot

/**
 * The `VideoAds` of one episode, for as long as this composition shows it: created for [content] (a new one when
 * its `id` changes), [setUp] run once on it (`ads.track(player)`), and destroyed when the composition leaves
 * or the episode changes. Destroying ends the ad on screen, a linear ad too: move to the next episode after a
 * post-roll's `onEnded`.
 *
 * For ads that must survive a rotation, keep the `VideoAds` in a `ViewModel` instead and destroy it in
 * `onCleared()`.
 */
@Composable
public fun rememberVideoAds(
    content: Content,
    labels: Map<String, String> = emptyMap(),
    setUp: (VideoAds) -> Unit = {},
): VideoAds {
    val ads = remember(content.id) { VideoAds(content, labels).also(setUp) }
    DisposableEffect(ads) { onDispose { ads.destroy() } }
    return ads
}

/**
 * Shows [ad] where this is placed, usually over the player in a `Box` with `Modifier.fillMaxSize()`. Set it to a
 * loaded ad to show it, to `null` (or another ad) to dismiss the one shown; it is dismissed too when this
 * leaves the composition. A linear ad is shown full screen from the Activity hosting the composition and is
 * not dismissed: it ends by itself or by the viewer.
 *
 * The host owns the ad's listener: hear the ad through [onImpression], [onClicked] and [onEnded] (set your
 * state's ad back to `null` there). [onShowFailed] means it could not be shown, for the reasons `show` throws
 * (`AnotherAdShowing`, `ContentPaused`, …): nothing was shown, and [onEnded] does not follow. [insets] keep an
 * overlay or pause ad clear of your own controls.
 */
@Composable
public fun VideoAdHost(
    ad: VideoAd?,
    modifier: Modifier = Modifier,
    insets: PaddingValues = PaddingValues(),
    onShowFailed: (VideoAd, VideoAdException) -> Unit = { _, _ -> },
    onImpression: (VideoAd) -> Unit = {},
    onClicked: (VideoAd, VideoAd.Click) -> Unit = { _, _ -> },
    onEnded: (VideoAd, VideoAd.EndReason) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val host = remember { FrameLayout(context) }
    val slot = remember { VideoAdSlot(host) }
    val currentOnShowFailed by rememberUpdatedState(onShowFailed)
    val currentOnImpression by rememberUpdatedState(onImpression)
    val currentOnClicked by rememberUpdatedState(onClicked)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val pixels = with(density) {
        val start = insets.calculateStartPadding(direction).roundToPx()
        val end = insets.calculateEndPadding(direction).roundToPx()
        val ltr = direction == LayoutDirection.Ltr
        Insets.of(if (ltr) start else end, insets.calculateTopPadding().roundToPx(), if (ltr) end else start, insets.calculateBottomPadding().roundToPx())
    }

    DisposableEffect(slot) {
        slot.onShowFailed = { failed, error -> currentOnShowFailed(failed, error) }
        slot.listener = object : VideoAdListener {
            override fun onAdImpression(ad: VideoAd) = currentOnImpression(ad)

            override fun onAdClicked(ad: VideoAd, click: VideoAd.Click) = currentOnClicked(ad, click)

            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) = currentOnEnded(ad, reason)
        }
        onDispose { slot.set(null) }
    }
    SideEffect {
        slot.insets = pixels
        slot.set(ad)
    }
    AndroidView(factory = { host }, modifier = modifier)
}
