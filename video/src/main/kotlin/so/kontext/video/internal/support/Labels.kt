package so.kontext.video.internal.support

/**
 * The checks behind `VideoAds(content, labels)`: what the SDK sends, and what it drops with a warning. The
 * labels describe where the ads play, never the viewer.
 */
internal object Labels {
    /** At most this many pairs: the first in key order are kept. */
    const val MAX_COUNT = 20

    /** A key or a value of more UTF-8 bytes is dropped, not cut: a cut value would match another rule. */
    const val MAX_BYTES = 64

    fun checked(labels: Map<String, String>, videoId: String): Map<String, String> {
        val kept = linkedMapOf<String, String>()
        val dropped = mutableListOf<String>()
        for ((key, value) in labels.toSortedMap()) {
            when {
                !isValidKey(key) -> dropped += "key \"${key.take(MAX_BYTES)}\" (only a-z, 0-9 and _, at most $MAX_BYTES bytes)"
                value.isEmpty() || value.toByteArray().size > MAX_BYTES -> dropped += "$key (the value must be 1 to $MAX_BYTES bytes)"
                kept.size == MAX_COUNT -> dropped += "$key (more than $MAX_COUNT labels)"
                else -> kept[key] = value
            }
        }
        if (dropped.isNotEmpty()) Logger.warning { "labels of video $videoId: dropped ${dropped.joinToString()}" }
        return kept
    }

    fun isValidKey(key: String): Boolean = key.isNotEmpty() && key.toByteArray().size <= MAX_BYTES &&
        key.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
}
