package so.kontext.video

/** The four ad formats. Every request asks for all of them; `ads.format()` returns one. */
public enum class AdFormat(
    /** The wire name: `overlay`, `pause`, `pauseFullscreen`, `linear`. */
    internal val wireName: String,
) {
    /** A strip across the player while the content plays. */
    OVERLAY("overlay"),

    /** A card at the top or bottom of the player while the content is paused. */
    PAUSE("pause"),

    /**
     * The whole player while the content is paused, with its own Resume: shown with `show(container)` as a pause
     * ad, it ends `RESUMED` when the viewer taps Resume, `INTERRUPTED` when the content plays again, `CLOSED` at its ✕.
     */
    PAUSE_FULLSCREEN("pauseFullscreen"),

    /** A full-screen video at a moment the app picks. */
    LINEAR("linear"),
    ;

    internal companion object {
        fun fromWire(name: String?): AdFormat? = entries.firstOrNull { it.wireName == name }
    }
}
