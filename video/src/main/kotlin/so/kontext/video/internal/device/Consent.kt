package so.kontext.video.internal.device

import android.content.Context
import so.kontext.kit.privacy.TCFDataProvider
import so.kontext.video.KontextVideo.Regulatory

/**
 * The consent signals a consent management platform writes to the default `SharedPreferences` under the
 * IAB's keys (TCF v2, GPP, US Privacy). A usable CMP value wins: it is the live consent. What the
 * publisher set on `KontextVideo.regulatory` fills in where the CMP has none usable; `coppa` can only
 * come from the publisher.
 */
internal object Consent {
    fun read(context: Context, explicit: Regulatory?): Regulatory? {
        val tcf = runCatching { TCFDataProvider.collect(context) }.getOrNull()
        // The default SharedPreferences, where the IAB says a CMP writes.
        val preferences = runCatching {
            context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
        }.getOrNull()
        val gpp = preferences?.string("IABGPP_HDR_GppString")
        val gppSid = preferences?.let { sections(runCatching { it.all["IABGPP_GppSID"] }.getOrNull()) }
        val usPrivacy = preferences?.string("IABUSPrivacy_String")
        val merged = Regulatory.Builder()
            .gdpr(tcf?.gdpr?.let { it == 1 } ?: explicit?.gdpr)
            .gdprConsent(tcf?.gdprConsent ?: explicit?.gdprConsent)
            .coppa(explicit?.coppa)
            .gpp(gpp ?: explicit?.gpp)
            .gppSid(gppSid ?: explicit?.gppSid)
            .usPrivacy(usPrivacy ?: explicit?.usPrivacy)
            .build()
        val empty = merged.gdpr == null && merged.gdprConsent == null && merged.coppa == null && merged.gpp == null &&
            merged.gppSid == null && merged.usPrivacy == null
        return if (empty) null else merged
    }

    /** `IABGPP_GppSID` as CMPs write it: `"2_7"`, `"2,7"`, or one section stored as a number. */
    private fun sections(raw: Any?): List<Int>? = when (raw) {
        is Int -> listOf(raw)
        is Long -> raw.toInt().takeIf { it.toLong() == raw }?.let(::listOf)
        is String -> raw.takeIf { it.isNotBlank() }?.let(::parseSections)
        else -> null
    }

    /** `"2_7"` (or `"2,7"`), as a CMP writes the section ids; `null` when any part is not a number. */
    fun parseSections(raw: String): List<Int>? =
        raw.split('_', ',').map { it.trim().toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    private fun android.content.SharedPreferences.string(key: String): String? =
        runCatching { getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() }
}
