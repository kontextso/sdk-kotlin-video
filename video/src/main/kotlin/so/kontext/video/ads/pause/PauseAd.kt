package so.kontext.video.ads.pause

import android.content.Context
import android.view.View
import android.view.ViewGroup
import so.kontext.video.ads.AdPlacement
import so.kontext.video.ads.BannerAdView
import so.kontext.video.ads.BannerPosition
import so.kontext.video.ads.BannerStyle
import so.kontext.video.ads.mountBanner
import so.kontext.video.model.VideoAd
import so.kontext.video.model.VideoAdEvent
import so.kontext.video.model.VideoAdFormat

/** The `pause` placement of one video: an image banner shown over part of the paused player. */
public class PauseAd internal constructor(
    videoId: String,
    emit: (VideoAdEvent) -> Unit,
) : AdPlacement(VideoAdFormat.PAUSE, videoId, emit) {
    private var position = BannerPosition.TOP
    private var style = BannerStyle()

    /**
     * Shows the next ad as a banner inside `container` (the view that fills
     * the video area — any `ViewGroup` whose children may overlap, such as a
     * `FrameLayout`) and consumes it. Defaults to the top so the episode title
     * most apps draw at the bottom stays visible. Returns `false` when
     * [isReady] is `false`.
     */
    public fun show(
        container: ViewGroup,
        position: BannerPosition = BannerPosition.TOP,
        style: BannerStyle = BannerStyle(),
    ): Boolean {
        this.position = position
        this.style = style
        return present(container)
    }

    override fun makeView(context: Context, ad: VideoAd): View = BannerAdView(
        context = context,
        ad = ad,
        style = style,
        scope = scope,
        onClick = ::onClick,
        onImageLoaded = ::onCreativeRendered,
        onImageFailed = { onCreativeFailed("pause: $it") },
    )

    override fun mount(view: View, container: ViewGroup): View =
        mountBanner(view as BannerAdView, container, position, style)
}
