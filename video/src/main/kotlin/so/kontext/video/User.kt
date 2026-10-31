package so.kontext.video

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
