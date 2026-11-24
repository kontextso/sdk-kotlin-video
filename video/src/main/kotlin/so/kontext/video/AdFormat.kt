package so.kontext.video

/** The three ad formats. Every request asks for all of them; `ads.format()` returns one. */
public enum class AdFormat(
    /** The wire name: `overlay`, `pause`, `linear`. */
    internal val wireName: String,
) {
    /** A strip across the player while the content plays. */
    OVERLAY("overlay"),

    /**
     * Over the player while the content is paused: a card at its top or bottom, or the whole player with its own
     * Resume and ✕. The server decides which (`VideoAd.position`); `show(container)` shows either. The whole player ends `RESUMED` when
     * the viewer taps Resume, `INTERRUPTED` when the content plays again, `CLOSED` at its ✕.
     */
    PAUSE("pause"),

    /** A full-screen video at a moment the app picks. */
    LINEAR("linear"),
    ;

    internal companion object {
        fun fromWire(name: String?): AdFormat? = entries.firstOrNull { it.wireName == name }
    }
}
