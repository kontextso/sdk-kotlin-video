package so.kontext.video.example

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * App palette, swapped wholesale between [DarkColors] and [LightColors] when the
 * user picks a theme in Settings. The UI reads these via [LocalAppColors] instead
 * of hardcoding black/white, so a single toggle re-themes the whole screen.
 *
 * (The video letterbox stays black in both themes — that's set directly on the
 * player surface, not from here.)
 */
data class AppColors(
    val background: Color,
    val bar: Color,
    val dock: Color,
    val rowHighlight: Color,
    val thumbnailBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val navUnselected: Color,
    val chipSelectedBg: Color,
    val chipSelectedText: Color,
    val chipUnselectedText: Color,
    val availableAccent: Color,
    val playingAccent: Color,
)

val DarkColors: AppColors = AppColors(
    background = Color(0xFF000000),
    bar = Color(0xFF0A0A0A),
    dock = Color(0xFF161616),
    rowHighlight = Color(0xFF2A2A2A),
    thumbnailBg = Color(0xFF222222),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF9E9E9E),
    textTertiary = Color(0xFF6E6E6E),
    navUnselected = Color(0xFF8A8A8A),
    chipSelectedBg = Color(0xFFFFFFFF),
    chipSelectedText = Color(0xFF000000),
    chipUnselectedText = Color(0xFFBBBBBB),
    availableAccent = Color(0xFF4FC3F7),
    playingAccent = Color(0xFFB3E5FC),
)

val LightColors: AppColors = AppColors(
    background = Color(0xFFFFFFFF),
    bar = Color(0xFFF5F5F5),
    dock = Color(0xFFEEEEEE),
    rowHighlight = Color(0xFFE3E3E3),
    thumbnailBg = Color(0xFFDDDDDD),
    textPrimary = Color(0xFF111111),
    textSecondary = Color(0xFF5F5F5F),
    textTertiary = Color(0xFF8A8A8A),
    navUnselected = Color(0xFF757575),
    chipSelectedBg = Color(0xFF1A1A1A),
    chipSelectedText = Color(0xFFFFFFFF),
    chipUnselectedText = Color(0xFF555555),
    availableAccent = Color(0xFF0277BD),
    playingAccent = Color(0xFF01579B),
)

/** Theme choice surfaced in Settings. */
enum class AppTheme(val label: String, val colors: AppColors) {
    DARK("Dark", DarkColors),
    LIGHT("Light", LightColors),
}

val LocalAppColors = staticCompositionLocalOf { DarkColors }
