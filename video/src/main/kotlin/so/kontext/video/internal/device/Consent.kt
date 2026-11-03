package so.kontext.video.internal.device

import android.content.Context
import so.kontext.kit.privacy.ConsentDataProvider
import so.kontext.video.KontextVideo.Regulatory

/**
 * The consent signals a consent management platform writes to the default `SharedPreferences` under the
 * IAB's keys (TCF v2, GPP, US Privacy), read by KontextKit's `ConsentDataProvider`. A usable CMP value wins:
 * it is the live consent. What the publisher set on `KontextVideo.regulatory` fills in where the CMP has none
 * usable; `coppa` can only come from the publisher. As the Swift SDK does.
 */
internal object Consent {
    fun read(context: Context, explicit: Regulatory?): Regulatory? {
        val stored = runCatching { ConsentDataProvider.collect(context) }.getOrNull()
        val merged = Regulatory.Builder()
            .gdpr(stored?.gdpr?.let { it == 1 } ?: explicit?.gdpr)
            .gdprConsent(stored?.gdprConsent ?: explicit?.gdprConsent)
            .coppa(explicit?.coppa)
            .gpp(stored?.gpp ?: explicit?.gpp)
            .gppSid(stored?.gppSid ?: explicit?.gppSid)
            .usPrivacy(stored?.usPrivacy ?: explicit?.usPrivacy)
            .build()
        val empty = merged.gdpr == null && merged.gdprConsent == null && merged.coppa == null && merged.gpp == null &&
            merged.gppSid == null && merged.usPrivacy == null
        return if (empty) null else merged
    }
}
