package so.kontext.video.ads

import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import so.kontext.video.model.VideoAd
import kotlin.math.roundToInt

/**
 * The creative image as a rounded banner styled by [BannerStyle], shared by
 * the pause ad and the playing overlay. Tapping it opens the click-through.
 */
internal class BannerAdView(
    context: Context,
    private val ad: VideoAd,
    private val style: BannerStyle,
    scope: CoroutineScope,
    onClick: () -> Unit,
    onImageLoaded: () -> Unit,
    onImageFailed: (String) -> Unit,
) : FrameLayout(context) {
    private val imageView = ImageView(context)

    init {
        val radius = dp(style.cornerRadiusDp).toFloat()
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.setBackgroundColor(style.placeholderColor)
        imageView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        imageView.clipToOutline = true
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnClickListener { onClick() }
        val image = ad.creative.image
        if (image == null) {
            onImageFailed("ad has no image")
        } else {
            AdImageLoader.load(image.url, imageView, scope, onImageLoaded, onImageFailed)
        }
    }

    /** Height for a given width, from the creative's declared image size. */
    fun heightForWidth(width: Int): Int {
        val image = ad.creative.image
        if (image == null || image.w <= 0 || image.h <= 0) return (width * FALLBACK_ASPECT).roundToInt()
        return (width.toFloat() * image.h / image.w).roundToInt()
    }

    fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val FALLBACK_ASPECT = 0.6f
    }
}

/**
 * Lays a banner out inside `container`: a full-size transparent layer is
 * added to the container (so any `ViewGroup` whose children may overlap
 * works), and the banner sits in it at `position` with `style`'s margins.
 */
internal fun mountBanner(banner: BannerAdView, container: ViewGroup, position: BannerPosition, style: BannerStyle): View {
    val layer = FrameLayout(container.context)
    container.addView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val containerWidth = if (container.width > 0) container.width else container.resources.displayMetrics.widthPixels
    var width = (containerWidth - banner.dp(style.marginStartDp) - banner.dp(style.marginEndDp)).coerceAtLeast(0)
    style.maxWidthDp?.let { width = minOf(width, banner.dp(it)) }
    val params = FrameLayout.LayoutParams(width, banner.heightForWidth(width)).apply {
        gravity = Gravity.CENTER_HORIZONTAL or if (position == BannerPosition.TOP) Gravity.TOP else Gravity.BOTTOM
        topMargin = banner.dp(style.marginTopDp)
        bottomMargin = banner.dp(style.marginBottomDp)
        leftMargin = (banner.dp(style.marginStartDp) - banner.dp(style.marginEndDp)) / 2
    }
    layer.addView(banner, params)
    return layer
}
