package so.kontext.video

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
