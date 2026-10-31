package so.kontext.video

/**
 * The options a publisher sets when initializing the SDK. They do not change during a run; what the viewer
 * is and consented to can change later and lives on [KontextVideo] (`user`, `regulatory`, `variantId`).
 *
 * Kotlin: `Configuration("netshort-dev") { logLevel = LogLevel.DEBUG }`.
 * Java: `new Configuration.Builder("netshort-dev").logLevel(LogLevel.DEBUG).build()`.
 */
public class Configuration private constructor(builder: Builder) {
    /**
     * Identifies the publisher. Not a secret. Required and non-empty: the server refuses requests without it.
     * Publishers typically have a developer token for testing, `{publisher}-dev`, and a production token.
     */
    public val publisherToken: String = builder.publisherToken

    /** How much the SDK prints to Logcat (tag `KontextVideo`). Logcat only: [LogLevel.NONE] does not stop the debug reporting the server can switch on. */
    public val logLevel: LogLevel = builder.logLevel

    /**
     * The ad server base URL, by default the production server: absolute, `https` (or `http` for a local
     * mock), without a path the SDK adds itself (`/v2/...`). Override it only for testing against a staging
     * or mock server.
     */
    public val adServerUrl: String = builder.adServerUrl

    /** How much the SDK prints. Each level includes the ones above it. */
    public enum class LogLevel {
        /** Silent. */
        NONE,

        /** Only errors. */
        ERROR,

        /** Errors and warnings. */
        WARNING,

        /** Errors, warnings and other useful information. */
        INFO,

        /** Detailed information about each step. */
        DEBUG,
    }

    /** Only `publisherToken` is required. */
    public class Builder(public var publisherToken: String) {
        public var logLevel: LogLevel = LogLevel.WARNING
        public var adServerUrl: String = DEFAULT_AD_SERVER_URL

        public fun logLevel(value: LogLevel): Builder = apply { logLevel = value }
        public fun adServerUrl(value: String): Builder = apply { adServerUrl = value }

        public fun build(): Configuration = Configuration(this)
    }

    public companion object {
        /** Production ad server base URL. */
        public const val DEFAULT_AD_SERVER_URL: String = "https://server.megabrain.co"

        /** `Configuration("netshort-dev") { logLevel = LogLevel.DEBUG }`. */
        public operator fun invoke(publisherToken: String, block: Builder.() -> Unit = {}): Configuration =
            Builder(publisherToken).apply(block).build()
    }

    /** What is wrong with the options, one phrase each; empty when they are usable. */
    internal val problems: List<String>
        get() = listOfNotNull(
            if (publisherToken.isEmpty()) "publisherToken is empty" else null,
            if (!AD_SERVER_URL.matches(adServerUrl)) "adServerUrl $adServerUrl is not absolute" else null,
        )

    override fun equals(other: Any?): Boolean = other is Configuration && publisherToken == other.publisherToken &&
        logLevel == other.logLevel && adServerUrl == other.adServerUrl

    override fun hashCode(): Int = listOf(publisherToken, logLevel, adServerUrl).hashCode()

    override fun toString(): String = "Configuration(publisherToken=$publisherToken, logLevel=$logLevel, adServerUrl=$adServerUrl)"
}

private val AD_SERVER_URL = Regex("^https?://[^/?#\\s]+.*$")
