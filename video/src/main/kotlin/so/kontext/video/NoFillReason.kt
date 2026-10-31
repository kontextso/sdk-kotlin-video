package so.kontext.video

/** Why the server had nothing for a format; [Other] is a reason this SDK version does not know, as sent. */
public sealed class NoFillReason(private val wireName: String) {
    /** No campaign matched the content, the viewer or the moment. */
    public object NoAdMatch : NoFillReason("noAdMatch")

    /** The content is not brand safe. */
    public object ContentUnsafe : NoFillReason("contentUnsafe")

    /** No configuration for this format in the Kontext dashboard: the integration, not the moment. */
    public object FormatNotEnabled : NoFillReason("formatNotEnabled")

    /** The viewer has seen enough ads for now. */
    public object FrequencyCap : NoFillReason("frequencyCap")

    /** Not served in this country. */
    public object GeoRestricted : NoFillReason("geoRestricted")

    /** The server's side; ask again later. */
    public object TemporaryError : NoFillReason("temporaryError")

    /** The SDK's own: the answer was an ad the app had just shown or rejected while the request ran. */
    public object AlreadyUsed : NoFillReason("alreadyUsed")

    /** The server gave no reason, or one the SDK could not read. */
    public object Unknown : NoFillReason("unknown")

    /** A reason this SDK version does not know, as the server sent it. */
    public class Other(public val raw: String) : NoFillReason(raw) {
        override fun equals(other: Any?): Boolean = other is Other && raw == other.raw

        override fun hashCode(): Int = raw.hashCode()
    }

    /** The wire name, for logs. */
    override fun toString(): String = wireName

    internal companion object {
        // Lazy: built while NoFillReason itself is still initializing, the list would hold nulls for its objects.
        private val known by lazy { listOf(NoAdMatch, ContentUnsafe, FormatNotEnabled, FrequencyCap, GeoRestricted, TemporaryError, AlreadyUsed, Unknown) }

        /** From the wire; `null` (left out or malformed) is [Unknown]. */
        fun from(raw: String?): NoFillReason = when (raw) {
            null -> Unknown
            else -> known.firstOrNull { it.wireName == raw } ?: Other(raw)
        }
    }
}
