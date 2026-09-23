package so.kontext.video.ads

import androidx.annotation.ColorInt

/**
 * Appearance of an image banner (pause ad, playing overlay). Everything has a
 * default; sizes are dp.
 *
 * @property marginTopDp Space between the banner and the container's top edge, for [BannerPosition.TOP].
 * @property marginBottomDp Space between the banner and the container's bottom edge, for [BannerPosition.BOTTOM].
 * @property maxWidthDp Caps the banner width (centered) so it doesn't stretch across wide players. `null` = full width.
 * @property placeholderColor Shown until the image has loaded.
 */
public data class BannerStyle(
    val marginTopDp: Int = 16,
    val marginBottomDp: Int = 16,
    val marginStartDp: Int = 16,
    val marginEndDp: Int = 16,
    val maxWidthDp: Int? = null,
    val cornerRadiusDp: Int = 12,
    @ColorInt val placeholderColor: Int = 0x4D000000,
)

/** Where a banner sits inside the container. */
public enum class BannerPosition {
    TOP,
    BOTTOM,
    ;

    internal companion object {
        /** The server's `placement.position`, when it names one. */
        fun fromServer(value: String?): BannerPosition? = when (value) {
            "top" -> TOP
            "bottom" -> BOTTOM
            else -> null
        }
    }
}
