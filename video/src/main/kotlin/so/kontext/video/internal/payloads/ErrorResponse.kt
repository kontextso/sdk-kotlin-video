package so.kontext.video.internal.payloads

import kotlinx.serialization.json.Json
import so.kontext.video.internal.network.arr
import so.kontext.video.internal.network.obj
import so.kontext.video.internal.network.str

/**
 * The ad server's error body (JSON:API-shaped, see the API's Common page). Each item's fields are read leniently:
 * a missing, null or wrongly typed field is `null`, so an item with less than the contract still reaches the log.
 * The list itself is not: an entry that is not an object fails the whole body, which then counts as none, and the
 * client reports the HTTP status alone, which still decides everything. The SDK acts on no code today; the item
 * goes to the log as its `title`, else `detail`, else `code`. As the Swift SDK's `ErrorResponse`.
 */
internal object ErrorResponse {
    class Item(val status: String?, val code: String?, val title: String?, val detail: String?) {
        /**
         * The item as one log phrase; `null` when the server sent none of the three. The first one present wins
         * even when it is empty, on purpose: the contract always sets `title`, so an empty one is a server bug, and
         * `HTTP 400: ` in the log shows it where a quiet fallback to `detail` would hide it.
         */
        val summary: String? get() = title ?: detail ?: code
    }

    /** The items of an error body; empty when it is not one. */
    fun parse(text: String): List<Item> = runCatching {
        Json.parseToJsonElement(text).obj()?.get("errors")?.arr()?.map { element ->
            val item = element.obj() ?: error("not an object")
            Item(item.str("status"), item.str("code"), item.str("title"), item.str("detail"))
        }
    }.getOrNull() ?: emptyList()
}
