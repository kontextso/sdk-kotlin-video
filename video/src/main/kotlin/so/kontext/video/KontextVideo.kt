package so.kontext.video

import android.content.Context
import androidx.annotation.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import so.kontext.video.internal.Sdk

/**
 * The SDK's one entry point. Call [initialize] once, in `Application.onCreate`; then create a [VideoAds]
 * per episode. Every call is main-thread.
 */
public object KontextVideo {
    /** The SDK version. */
    public const val VERSION: String = BuildConfig.SDK_VERSION

    /** Where the SDK is: what `/config` said, or that it was not asked yet. */
    public sealed class State {
        /** [initialize] was not called. */
        public object NotInitialized : State() {
            override fun toString(): String = "NotInitialized"
        }

        /** `/config` is in flight. */
        public object Initializing : State() {
            override fun toString(): String = "Initializing"
        }

        /** Ads can be requested. */
        public object Enabled : State() {
            override fun toString(): String = "Enabled"
        }

        /**
         * The server switched the SDK off for this launch; or [initialize] ran in a process other than the
         * app's main one, where the SDK stays off ([reason] says which).
         */
        public class Disabled(public val reason: String) : State() {
            override fun equals(other: Any?): Boolean = other is Disabled && reason == other.reason

            override fun hashCode(): Int = reason.hashCode()

            override fun toString(): String = "Disabled($reason)"
        }

        /** `/config` failed; the next `format()` tries it again. */
        public object Failed : State() {
            override fun toString(): String = "Failed"
        }
    }

    /** Told once `/config` has answered or failed, on the main thread. */
    public fun interface InitializationListener {
        public fun onInitialized(state: State)
    }

    /** The current state. */
    @JvmStatic
    public val state: State get() = Sdk.state

    /**
     * What the publisher knows about the viewer. Set it whenever you learn it, for example after login; the
     * next ads request carries it. Ads already loaded keep the targeting they were requested with.
     */
    @JvmStatic
    public var user: User?
        get() = Sdk.user
        set(value) {
            Sdk.checkMainThread("KontextVideo.user")
            Sdk.user = value
        }

    /**
     * Privacy and consent signals. Set what your consent platform did not write; `coppa` can only come from
     * you. `null` clears your values only: the SDK still reads the consent platform's storage on every request.
     */
    @JvmStatic
    public var regulatory: Regulatory?
        get() = Sdk.regulatory
        set(value) {
            Sdk.checkMainThread("KontextVideo.regulatory")
            Sdk.regulatory = value
        }

    /** The publisher's A/B test variant, if any; sent with every later request. */
    @JvmStatic
    public var variantId: String?
        get() = Sdk.variantId
        set(value) {
            Sdk.checkMainThread("KontextVideo.variantId")
            Sdk.variantId = value
        }

    /**
     * Starts the SDK: stores the configuration and calls the server in the background. Call it once, at app
     * launch; later calls are ignored. [listener] is told once the server answered or failed.
     *
     * Only in the app's main process: `Application.onCreate` runs in every process of the app (a playback or
     * download service's too), and there the SDK does nothing (no server call, no WebView, whose data
     * directory one process holds). The state becomes [State.Disabled] and [listener] is told so; ads
     * requested there throw `Disabled`.
     */
    @MainThread
    @JvmStatic
    @JvmOverloads
    public fun initialize(context: Context, configuration: Configuration, listener: InitializationListener? = null) {
        Sdk.checkMainThread("KontextVideo.initialize()")
        Sdk.initialize(context, configuration, listener?.let { it::onInitialized })
    }

    /** Waits until `/config` has answered or failed, and returns the state; at once when it already has. Callable from any dispatcher. */
    public suspend fun awaitInitialization(): State = withContext(Dispatchers.Main.immediate) { Sdk.awaitInitialization() }

    /**
     * The options a publisher sets when initializing the SDK. They do not change during a run; what the viewer
     * is and consented to can change later and lives on [KontextVideo] (`user`, `regulatory`, `variantId`).
     *
     * Kotlin: `KontextVideo.Configuration("your-token-dev") { logLevel = LogLevel.DEBUG }`.
     * Java: `new KontextVideo.Configuration.Builder("your-token-dev").logLevel(LogLevel.DEBUG).build()`.
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

            /** `KontextVideo.Configuration("your-token-dev") { logLevel = LogLevel.DEBUG }`. */
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

    /**
     * The publisher's video the ads are for, "content" in ad-industry terms. Everything beyond [id] and
     * [language] is contextual signal: send what you have.
     *
     * Kotlin: `KontextVideo.Content(id = "100001-6", language = "en") { title = "Episode 6"; durationMillis = 92_000 }`.
     * Java: `new KontextVideo.Content.Builder("100001-6", "en").title("Episode 6").durationMillis(92_000L).build()`.
     */
    public class Content private constructor(builder: Builder) {
        /** The publisher's own id of the video, any scheme. Required and non-empty: the server refuses a request without it. */
        public val id: String = builder.id

        /** BCP-47, for example "en" or "pt-BR". Required and non-empty, as [id]. */
        public val language: String = builder.language

        /** Whether the video suits every advertiser; `null` when you do not know. */
        public val mediaRating: Rating? = builder.mediaRating

        /** The episode's public page. */
        public val url: String? = builder.url

        /** The episode's public name. */
        public val title: String? = builder.title

        /** IAB Content Taxonomy 3.0 ids. */
        public val categories: List<String>? = builder.categories?.toList()

        /** A short synopsis of the episode. */
        public val description: String? = builder.description

        /** The episode's length in milliseconds. Left out of the request when below 1. */
        public val durationMillis: Long? = builder.durationMillis

        /** At least 1; anything else is left out of the request. */
        public val episodeNumber: Int? = builder.episodeNumber

        /** Words that describe the video, in the publisher's own terms. */
        public val keywords: List<String>? = builder.keywords?.toList()

        /** For example "romance". */
        public val genre: String? = builder.genre

        /** The series the episode belongs to, when it is one. */
        public val series: Series? = builder.series

        /** A simplified rating: [EXPLICIT] content gets no ads that require safe content. */
        public enum class Rating {
            /** Suitable for every advertiser. */
            SAFE,

            /** Mature content: only ads that accept it. */
            EXPLICIT,
        }

        /** Sets what you know about the video; only `id` and `language` are required. */
        public class Builder(
            public var id: String,
            public var language: String,
        ) {
            public var mediaRating: Rating? = null
            public var url: String? = null
            public var title: String? = null
            public var categories: List<String>? = null
            public var description: String? = null
            public var durationMillis: Long? = null
            public var episodeNumber: Int? = null
            public var keywords: List<String>? = null
            public var genre: String? = null
            public var series: Series? = null

            public fun mediaRating(value: Rating?): Builder = apply { mediaRating = value }
            public fun url(value: String?): Builder = apply { url = value }
            public fun title(value: String?): Builder = apply { title = value }
            public fun categories(value: List<String>?): Builder = apply { categories = value }
            public fun description(value: String?): Builder = apply { description = value }
            public fun durationMillis(value: Long?): Builder = apply { durationMillis = value }
            public fun episodeNumber(value: Int?): Builder = apply { episodeNumber = value }
            public fun keywords(value: List<String>?): Builder = apply { keywords = value }
            public fun genre(value: String?): Builder = apply { genre = value }
            public fun series(value: Series?): Builder = apply { series = value }

            public fun build(): Content = Content(this)
        }

        public companion object {
            /** `KontextVideo.Content(id, language) { title = "…" }`: only `id` and `language` are required. */
            public operator fun invoke(id: String, language: String, block: Builder.() -> Unit = {}): Content =
                Builder(id, language).apply(block).build()
        }

        /** The series an episode belongs to. */
        public class Series private constructor(builder: Builder) {
            /** Required and non-empty; a series with an empty id is left out of the request. */
            public val id: String = builder.id

            /** The series' public name. */
            public val title: String? = builder.title

            /** A short synopsis of the series. */
            public val description: String? = builder.description

            /** Total number of episodes, at least 1; anything else is left out of the request. */
            public val episodeCount: Int? = builder.episodeCount

            /** Only `id` is required. */
            public class Builder(public var id: String) {
                public var title: String? = null
                public var description: String? = null
                public var episodeCount: Int? = null

                public fun title(value: String?): Builder = apply { title = value }
                public fun description(value: String?): Builder = apply { description = value }
                public fun episodeCount(value: Int?): Builder = apply { episodeCount = value }

                public fun build(): Series = Series(this)
            }

            public companion object {
                /** `Content.Series(id) { title = "…"; episodeCount = 40 }`. */
                public operator fun invoke(id: String, block: Builder.() -> Unit = {}): Series = Builder(id).apply(block).build()
            }

            override fun equals(other: Any?): Boolean = other is Series && id == other.id && title == other.title &&
                description == other.description && episodeCount == other.episodeCount

            override fun hashCode(): Int = listOf(id, title, description, episodeCount).hashCode()

            override fun toString(): String = "Series(id=$id, title=$title, episodeCount=$episodeCount)"
        }

        /** Required fields left empty: every request would fail at the server. */
        internal val missingRequired: List<String>
            get() = listOfNotNull(if (id.isEmpty()) "id is empty" else null, if (language.isEmpty()) "language is empty" else null)

        /** Optional metadata the request leaves out because the server would refuse it, one phrase each. */
        internal val leftOut: List<String>
            get() = listOfNotNull(
                durationMillis?.takeIf { it < 1 }?.let { "durationMillis $it left out" },
                episodeNumber?.takeIf { it < 1 }?.let { "episodeNumber $it left out" },
                series?.takeIf { it.id.isEmpty() }?.let { "series left out, its id is empty" },
                series?.episodeCount?.takeIf { it < 1 }?.let { "series.episodeCount $it left out" },
            )

        override fun equals(other: Any?): Boolean = other is Content && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()

        override fun toString(): String = "Content(id=$id, language=$language, title=$title, durationMillis=$durationMillis, " +
            "episodeNumber=$episodeNumber, series=$series)"

        private val fields: List<Any?>
            get() = listOf(id, language, mediaRating, url, title, categories, description, durationMillis, episodeNumber, keywords, genre, series)
    }

    /**
     * What the publisher knows about the viewer. Every field is optional. No demographics: OpenRTB 2.6 and
     * AdCOM deprecated `user.yob` and `user.gender`; [keywords] and the identity fields are what buyers still read.
     */
    public class User private constructor(builder: Builder) {
        /** The publisher's own unique identifier of the viewer. An empty id is left out of the request. */
        public val id: String? = builder.id

        /** Plain email address, when the viewer's consent allows it. Sent as given; the SDK neither normalizes nor hashes it. */
        public val email: String? = builder.email

        /**
         * A hash of the viewer's email address, sent as given. SHA-256 of the trimmed, lowercased address is
         * what we suggest. When [email] is set too, both are sent and the server matches on `email`.
         */
        public val emailHash: String? = builder.emailHash

        /** The viewer's interests, in the publisher's own words. */
        public val keywords: List<String>? = builder.keywords?.toList()

        /** Set what you know; every field may stay `null`. */
        public class Builder {
            public var id: String? = null
            public var email: String? = null
            public var emailHash: String? = null
            public var keywords: List<String>? = null

            public fun id(value: String?): Builder = apply { id = value }
            public fun email(value: String?): Builder = apply { email = value }
            public fun emailHash(value: String?): Builder = apply { emailHash = value }
            public fun keywords(value: List<String>?): Builder = apply { keywords = value }

            public fun build(): User = User(this)
        }

        public companion object {
            /** `User { id = "u-42" }`. */
            public operator fun invoke(block: Builder.() -> Unit): User = Builder().apply(block).build()
        }

        /** The user without what identifies the viewer (id, email, email hash), for a COPPA request; `null` when nothing else is left. */
        internal val withoutIdentity: User?
            get() = keywords?.let { kept -> User { keywords = kept } }

        /** Fields set to an empty string, which the request leaves out. */
        internal val emptyFields: List<String>
            get() = listOf("id" to id, "email" to email, "emailHash" to emailHash).filter { it.second?.isEmpty() == true }.map { it.first }

        override fun equals(other: Any?): Boolean =
            other is User && id == other.id && email == other.email && emailHash == other.emailHash && keywords == other.keywords

        override fun hashCode(): Int = listOf(id, email, emailHash, keywords).hashCode()

        // No personal data in logs: the fields are only said to be set.
        override fun toString(): String =
            "User(id=${id?.let { "set" }}, email=${email?.let { "set" }}, emailHash=${emailHash?.let { "set" }}, keywords=${keywords?.size})"
    }

    /**
     * Privacy and consent signals sent with every ads request.
     *
     * Except for [coppa], the SDK reads these from the consent management platform's storage (the IAB keys in
     * the default `SharedPreferences`); a usable CMP value is the live consent and wins. A value set here fills
     * in where the CMP has none usable (a key it did not write, an empty or malformed one), for an app without
     * an IAB-writing CMP. The SDK carries the signals and interprets none. `null` everywhere means unknown,
     * never `false`.
     */
    public class Regulatory private constructor(builder: Builder) {
        /** Whether the request is subject to GDPR; `null` is unknown, which is not the same as `false`. */
        public val gdpr: Boolean? = builder.gdpr

        /** The IAB Transparency and Consent Framework consent string. */
        public val gdprConsent: String? = builder.gdprConsent

        /**
         * Whether the request is subject to COPPA; `null` is unspecified. Cannot be read from the device, so only the
         * publisher sets it. When `true`, ads requests carry no advertising id and no user id, email or email hash.
         */
        public val coppa: Boolean? = builder.coppa

        /** The IAB Global Privacy Platform consent string. */
        public val gpp: String? = builder.gpp

        /** The sections of the GPP string that apply to this request. */
        public val gppSid: List<Int>? = builder.gppSid?.toList()

        /** The legacy IAB US Privacy (CCPA) string. Some DSPs still read it. */
        public val usPrivacy: String? = builder.usPrivacy

        /** Set only what your consent platform did not write; `coppa` can only come from you. */
        public class Builder {
            public var gdpr: Boolean? = null
            public var gdprConsent: String? = null
            public var coppa: Boolean? = null
            public var gpp: String? = null
            public var gppSid: List<Int>? = null
            public var usPrivacy: String? = null

            public fun gdpr(value: Boolean?): Builder = apply { gdpr = value }
            public fun gdprConsent(value: String?): Builder = apply { gdprConsent = value }
            public fun coppa(value: Boolean?): Builder = apply { coppa = value }
            public fun gpp(value: String?): Builder = apply { gpp = value }
            public fun gppSid(value: List<Int>?): Builder = apply { gppSid = value }
            public fun usPrivacy(value: String?): Builder = apply { usPrivacy = value }

            public fun build(): Regulatory = Regulatory(this)
        }

        public companion object {
            /** `Regulatory { coppa = true }`. */
            public operator fun invoke(block: Builder.() -> Unit): Regulatory = Builder().apply(block).build()
        }

        override fun equals(other: Any?): Boolean = other is Regulatory && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()

        override fun toString(): String = "Regulatory(gdpr=$gdpr, gdprConsent=${gdprConsent?.let { "set" }}, coppa=$coppa, " +
            "gpp=${gpp?.let { "set" }}, gppSid=$gppSid, usPrivacy=$usPrivacy)"

        private val fields: List<Any?> get() = listOf(gdpr, gdprConsent, coppa, gpp, gppSid, usPrivacy)
    }
}

private val AD_SERVER_URL = Regex("^https?://[^/?#\\s]+.*$")
