package so.kontext.video.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Ads as the server sends them, as JSON text, with the fields a test changes. */
internal object Fixtures {
    const val TRACKING = """[
        {"event": "impression", "url": "https://t.test/i?cb=[CACHEBUSTING]&ph=[CONTENTPLAYHEAD]&fid=[KONTEXT_FIREID]"},
        {"event": "click", "url": "https://t.test/c"},
        {"event": "progress", "url": "https://t.test/p5", "offset": 5000},
        {"event": "progress", "url": "https://t.test/p2", "offset": 2000},
        {"event": "click", "url": "https://t.test/end", "part": "endCard"}
    ]"""

    fun overlay(
        id: String = "ad-1",
        expires: Long = 600_000,
        duration: String = "6000",
        frameUrl: String = "https://frames.test/v2/frame/ad-1",
        sizes: String = """[{"width": 320, "height": 80}, {"width": 640, "height": 160}]""",
        tracking: String = TRACKING,
    ) = """
        {"id": "$id", "format": "overlay", "creative": "image", "trigger": {"kind": "manual"},
         "price": {"value": 8.0, "currency": "USD", "precision": "exact"}, "expires": $expires,
         "rendering": {"kind": "frame", "url": "$frameUrl"}, "tracking": $tracking,
         "position": "bottom", "sizes": $sizes, "duration": $duration}
    """.trimIndent()

    fun pause(id: String = "ad-2", position: String = "top") = """
        {"id": "$id", "format": "pause", "creative": "image", "trigger": {"kind": "manual"},
         "price": {"value": 5.5, "currency": "EUR", "precision": "estimated"}, "expires": 600000,
         "rendering": {"kind": "frame", "url": "https://frames.test/v2/frame/$id"}, "tracking": [],
         "position": "$position", "sizes": [{"width": 300, "height": 250, "scalable": false}]}
    """.trimIndent()

    fun linear(id: String = "ad-3") = """
        {"id": "$id", "format": "linear", "creative": "video", "trigger": {"kind": "manual"},
         "price": {"value": 12.0, "currency": "USD", "precision": "exact"}, "expires": 600000,
         "rendering": {"kind": "frame", "url": "https://frames.test/v2/frame/$id"}, "tracking": [],
         "duration": 30000, "skipOffset": 5000}
    """.trimIndent()

    fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    fun ads(vararg entries: String) = json("""{"enabled": true, "formats": [${entries.joinToString()}]}""")

    fun entry(format: String, vararg ads: String, noFill: String? = null) =
        """{"format": "$format", "ads": [${ads.joinToString()}]${noFill?.let { ", \"noFillReason\": \"$it\"" } ?: ""}}"""
}
