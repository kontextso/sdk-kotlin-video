package so.kontext.video

/**
 * The publisher's video the ads are for, "content" in ad-industry terms. Everything beyond [id] and
 * [language] is contextual signal: send what you have.
 *
 * Kotlin: `Content(id = "100001-6", language = "en") { title = "Episode 6"; durationMillis = 92_000 }`.
 * Java: `new Content.Builder("100001-6", "en").title("Episode 6").durationMillis(92_000L).build()`.
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
        /** `Content(id, language) { title = "…" }`: only `id` and `language` are required. */
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
