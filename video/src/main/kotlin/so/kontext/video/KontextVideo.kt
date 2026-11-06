package so.kontext.video

import android.content.Context
import androidx.annotation.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import so.kontext.video.internal.SDK_VERSION
import so.kontext.video.internal.SdkInitializer
import so.kontext.video.internal.diagnostics.IntegrationGuard
import so.kontext.video.internal.state.SdkState

/**
 * The SDK's one entry point. Call [initialize] once, in `Application.onCreate`; then create a [VideoAds]
 * per episode. [initialize] and every setter are main-thread (from another thread they do nothing; in a
 * debuggable build that mistake stops the app); [awaitInitialization] may be called from any dispatcher.
 */
public object KontextVideo {
    /**
     * The SDK version running in the app, as sent with every request: read at run time, so an app built against
     * an older version reports the one it actually ships with.
     */
    @JvmField
    public val VERSION: String = SDK_VERSION

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

    /**
     * Told once `/config` has answered or failed, on the main thread: the optional "tell me when the SDK is
     * ready" of [initialize]. Rarely needed: ads wait for `/config` by themselves, so an app may initialize and
     * request ads at once. For an app that wants the result, to log it or to skip ad UI when the SDK is disabled.
     * From Kotlin, [awaitInitialization] does the same as a suspend call; this listener is its callback form.
     */
    public fun interface InitializationListener {
        /** `/config` answered or failed: [state] is where the SDK is now. */
        @MainThread
        public fun onInitialized(state: State)
    }

    /** The current state. */
    @JvmStatic
    public val state: State get() = SdkState.current.phase

    /**
     * What the publisher knows about the viewer. Set it whenever you learn it, for example after login; the
     * next ads request carries it. Ads already loaded keep the targeting they were requested with.
     */
    @JvmStatic
    @set:MainThread
    public var user: User?
        get() = SdkState.current.user
        set(value) {
            if (!IntegrationGuard.requireMainThread("KontextVideo.user")) return
            SdkState.current.recordUser(value)
            IntegrationGuard.checkUser(value)
        }

    /**
     * Privacy and consent signals. Set what your consent platform did not write; `coppa` can only come from
     * you. `null` clears your values only: the SDK still reads the consent platform's storage on every request.
     */
    @JvmStatic
    @set:MainThread
    public var regulatory: Regulatory?
        get() = SdkState.current.regulatory
        set(value) {
            if (!IntegrationGuard.requireMainThread("KontextVideo.regulatory")) return
            SdkState.current.recordRegulatory(value)
        }

    /** The publisher's A/B test variant, if any; sent with every later request. */
    @JvmStatic
    @set:MainThread
    public var variantId: String?
        get() = SdkState.current.variantId
        set(value) {
            if (!IntegrationGuard.requireMainThread("KontextVideo.variantId")) return
            SdkState.current.recordVariantId(value)
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
        if (!IntegrationGuard.requireMainThread("KontextVideo.initialize()")) return
        SdkInitializer.initialize(context, configuration, listener?.let { it::onInitialized })
    }

    /** Waits until `/config` has answered or failed, and returns the state; at once when it already has. Callable from any dispatcher. */
    public suspend fun awaitInitialization(): State = withContext(Dispatchers.Main.immediate) { SdkInitializer.awaitInitialization() }

    // The models below (Configuration, Content, User, Regulatory) share one shape, the builder pattern:
    //
    //   class Model private constructor(builder: Builder)   read-only `val`s, fixed once built
    //   class Builder(required…)                            a mutable draft: `var`s with defaults
    //     fun field(value): Builder = apply { … }           a chainable setter: sets, returns the builder (for Java)
    //     fun build(): Model                                freezes the draft
    //   companion object { operator fun invoke(…) }         Kotlin's `Model(required) { field = … }`: it makes a
    //                                                       Builder, runs the block on it, builds
    //
    //   Kotlin: KontextVideo.Content("episode-42", "en") { genre = "drama" }
    //   Java:   new KontextVideo.Content.Builder("episode-42", "en").genre("drama").build()
    //
    // Why not a constructor or a data class: binary compatibility. A new option is one more `var` with a default
    // in the Builder, and apps built against an older SDK keep working; a new constructor parameter (or a data
    // class's generated copy and componentN) would change signatures they call, and they would crash with a
    // method not found. So equals, hashCode and toString are written by hand.

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

        /**
         * Only `publisherToken` is required.
         * @property publisherToken [Configuration.publisherToken].
         */
        public class Builder(public var publisherToken: String) {
            /** [Configuration.logLevel]. */
            public var logLevel: LogLevel = LogLevel.WARNING

            /** [Configuration.adServerUrl]. */
            public var adServerUrl: String = DEFAULT_AD_SERVER_URL

            /** Sets [Configuration.logLevel]; returns this builder, for chaining. */
            public fun logLevel(value: LogLevel): Builder = apply { logLevel = value }

            /** Sets [Configuration.adServerUrl]; returns this builder, for chaining. */
            public fun adServerUrl(value: String): Builder = apply { adServerUrl = value }

            /** The [Configuration], from the values set. */
            public fun build(): Configuration = Configuration(this)
        }

        override fun equals(other: Any?): Boolean = other is Configuration && publisherToken == other.publisherToken &&
            logLevel == other.logLevel && adServerUrl == other.adServerUrl

        override fun hashCode(): Int = listOf(publisherToken, logLevel, adServerUrl).hashCode()

        override fun toString(): String = "Configuration(publisherToken=$publisherToken, logLevel=$logLevel, adServerUrl=$adServerUrl)"

        /** Builds one in Kotlin, with a block; Java uses the `Builder`. */
        public companion object {
            /** Production ad server base URL. */
            public const val DEFAULT_AD_SERVER_URL: String = "https://server.megabrain.co"

            /** `KontextVideo.Configuration("your-token-dev") { logLevel = LogLevel.DEBUG }`. */
            public operator fun invoke(publisherToken: String, block: Builder.() -> Unit = {}): Configuration =
                Builder(publisherToken).apply(block).build()
        }
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
            /** [Content.id]. */
            public var id: String,
            /** [Content.language]. */
            public var language: String,
        ) {
            /** [Content.mediaRating]. */
            public var mediaRating: Rating? = null

            /** [Content.url]. */
            public var url: String? = null

            /** [Content.title]. */
            public var title: String? = null

            /** [Content.categories]. */
            public var categories: List<String>? = null

            /** [Content.description]. */
            public var description: String? = null

            /** [Content.durationMillis]. */
            public var durationMillis: Long? = null

            /** [Content.episodeNumber]. */
            public var episodeNumber: Int? = null

            /** [Content.keywords]. */
            public var keywords: List<String>? = null

            /** [Content.genre]. */
            public var genre: String? = null

            /** [Content.series]. */
            public var series: Series? = null

            /** Sets [Content.mediaRating]; returns this builder, for chaining. */
            public fun mediaRating(value: Rating?): Builder = apply { mediaRating = value }

            /** Sets [Content.url]; returns this builder, for chaining. */
            public fun url(value: String?): Builder = apply { url = value }

            /** Sets [Content.title]; returns this builder, for chaining. */
            public fun title(value: String?): Builder = apply { title = value }

            /** Sets [Content.categories]; returns this builder, for chaining. */
            public fun categories(value: List<String>?): Builder = apply { categories = value }

            /** Sets [Content.description]; returns this builder, for chaining. */
            public fun description(value: String?): Builder = apply { description = value }

            /** Sets [Content.durationMillis]; returns this builder, for chaining. */
            public fun durationMillis(value: Long?): Builder = apply { durationMillis = value }

            /** Sets [Content.episodeNumber]; returns this builder, for chaining. */
            public fun episodeNumber(value: Int?): Builder = apply { episodeNumber = value }

            /** Sets [Content.keywords]; returns this builder, for chaining. */
            public fun keywords(value: List<String>?): Builder = apply { keywords = value }

            /** Sets [Content.genre]; returns this builder, for chaining. */
            public fun genre(value: String?): Builder = apply { genre = value }

            /** Sets [Content.series]; returns this builder, for chaining. */
            public fun series(value: Series?): Builder = apply { series = value }

            /** The [Content], from the values set. */
            public fun build(): Content = Content(this)
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

            /**
             * Only `id` is required.
             * @property id [Series.id].
             */
            public class Builder(public var id: String) {
                /** [Series.title]. */
                public var title: String? = null

                /** [Series.description]. */
                public var description: String? = null

                /** [Series.episodeCount]. */
                public var episodeCount: Int? = null

                /** Sets [Series.title]; returns this builder, for chaining. */
                public fun title(value: String?): Builder = apply { title = value }

                /** Sets [Series.description]; returns this builder, for chaining. */
                public fun description(value: String?): Builder = apply { description = value }

                /** Sets [Series.episodeCount]; returns this builder, for chaining. */
                public fun episodeCount(value: Int?): Builder = apply { episodeCount = value }

                /** The [Series], from the values set. */
                public fun build(): Series = Series(this)
            }

            override fun equals(other: Any?): Boolean = other is Series && id == other.id && title == other.title &&
                description == other.description && episodeCount == other.episodeCount

            override fun hashCode(): Int = listOf(id, title, description, episodeCount).hashCode()

            override fun toString(): String = "Series(id=$id, title=$title, episodeCount=$episodeCount)"

            /** Builds one in Kotlin, with a block; Java uses the `Builder`. */
            public companion object {
                /** `Content.Series(id) { title = "…"; episodeCount = 40 }`. */
                public operator fun invoke(id: String, block: Builder.() -> Unit = {}): Series = Builder(id).apply(block).build()
            }
        }

        private val fields: List<Any?>
            get() = listOf(id, language, mediaRating, url, title, categories, description, durationMillis, episodeNumber, keywords, genre, series)

        override fun equals(other: Any?): Boolean = other is Content && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()

        override fun toString(): String = "Content(id=$id, language=$language, title=$title, durationMillis=$durationMillis, " +
            "episodeNumber=$episodeNumber, series=$series)"

        /** Builds one in Kotlin, with a block; Java uses the `Builder`. */
        public companion object {
            /** `KontextVideo.Content(id, language) { title = "…" }`: only `id` and `language` are required. */
            public operator fun invoke(id: String, language: String, block: Builder.() -> Unit = {}): Content =
                Builder(id, language).apply(block).build()
        }
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
            /** [User.id]. */
            public var id: String? = null

            /** [User.email]. */
            public var email: String? = null

            /** [User.emailHash]. */
            public var emailHash: String? = null

            /** [User.keywords]. */
            public var keywords: List<String>? = null

            /** Sets [User.id]; returns this builder, for chaining. */
            public fun id(value: String?): Builder = apply { id = value }

            /** Sets [User.email]; returns this builder, for chaining. */
            public fun email(value: String?): Builder = apply { email = value }

            /** Sets [User.emailHash]; returns this builder, for chaining. */
            public fun emailHash(value: String?): Builder = apply { emailHash = value }

            /** Sets [User.keywords]; returns this builder, for chaining. */
            public fun keywords(value: List<String>?): Builder = apply { keywords = value }

            /** The [User], from the values set. */
            public fun build(): User = User(this)
        }

        override fun equals(other: Any?): Boolean =
            other is User && id == other.id && email == other.email && emailHash == other.emailHash && keywords == other.keywords

        override fun hashCode(): Int = listOf(id, email, emailHash, keywords).hashCode()

        // No personal data in logs: the fields are only said to be set.
        override fun toString(): String =
            "User(id=${said(id)}, email=${said(email)}, emailHash=${said(emailHash)}, keywords=${keywords?.size})"

        /** Builds one in Kotlin, with a block; Java uses the `Builder`. */
        public companion object {
            /** `User { id = "u-42" }`. */
            public operator fun invoke(block: Builder.() -> Unit): User = Builder().apply(block).build()
        }
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
            /** [Regulatory.gdpr]. */
            public var gdpr: Boolean? = null

            /** [Regulatory.gdprConsent]. */
            public var gdprConsent: String? = null

            /** [Regulatory.coppa]. */
            public var coppa: Boolean? = null

            /** [Regulatory.gpp]. */
            public var gpp: String? = null

            /** [Regulatory.gppSid]. */
            public var gppSid: List<Int>? = null

            /** [Regulatory.usPrivacy]. */
            public var usPrivacy: String? = null

            /** Sets [Regulatory.gdpr]; returns this builder, for chaining. */
            public fun gdpr(value: Boolean?): Builder = apply { gdpr = value }

            /** Sets [Regulatory.gdprConsent]; returns this builder, for chaining. */
            public fun gdprConsent(value: String?): Builder = apply { gdprConsent = value }

            /** Sets [Regulatory.coppa]; returns this builder, for chaining. */
            public fun coppa(value: Boolean?): Builder = apply { coppa = value }

            /** Sets [Regulatory.gpp]; returns this builder, for chaining. */
            public fun gpp(value: String?): Builder = apply { gpp = value }

            /** Sets [Regulatory.gppSid]; returns this builder, for chaining. */
            public fun gppSid(value: List<Int>?): Builder = apply { gppSid = value }

            /** Sets [Regulatory.usPrivacy]; returns this builder, for chaining. */
            public fun usPrivacy(value: String?): Builder = apply { usPrivacy = value }

            /** The [Regulatory], from the values set. */
            public fun build(): Regulatory = Regulatory(this)
        }

        private val fields: List<Any?> get() = listOf(gdpr, gdprConsent, coppa, gpp, gppSid, usPrivacy)

        override fun equals(other: Any?): Boolean = other is Regulatory && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()

        override fun toString(): String = "Regulatory(gdpr=$gdpr, gdprConsent=${said(gdprConsent)}, coppa=$coppa, " +
            "gpp=${said(gpp)}, gppSid=$gppSid, usPrivacy=$usPrivacy)"

        /** Builds one in Kotlin, with a block; Java uses the `Builder`. */
        public companion object {
            /** `Regulatory { coppa = true }`. */
            public operator fun invoke(block: Builder.() -> Unit): Regulatory = Builder().apply(block).build()
        }
    }
}

/** A personal value as `toString()` may show it: only whether it is set. */
private fun said(value: String?): String? = if (value == null) null else "set"
