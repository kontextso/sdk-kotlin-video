package so.kontext.video

/** The three ad formats. Every request asks for all of them; `ads.format()` returns one. */
public enum class AdFormat {
    /** A strip across the player while the content plays. */
    OVERLAY,

    /** A card, or the full player, while the content is paused. */
    PAUSE,

    /** A full-screen video at a moment the app picks. */
    LINEAR,
    ;

    /** The wire name: `overlay`, `pause`, `linear`. */
    internal val wireName: String get() = name.lowercase()

    internal companion object {
        fun fromWire(name: String?): AdFormat? = entries.firstOrNull { it.wireName == name }
    }
}
