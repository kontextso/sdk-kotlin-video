package so.kontext.video.internal.device

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import so.kontext.kit.privacy.ConsentDataProvider
import so.kontext.kit.privacy.ConsentDataProvider.ConsentData
import so.kontext.video.KontextVideo.Regulatory

/**
 * The consent signals a consent management platform writes to the default `SharedPreferences` under the
 * IAB's keys (TCF v2, GPP, US Privacy), read by KontextKit's `ConsentDataProvider`. A usable CMP value wins:
 * it is the live consent. What the publisher set on `KontextVideo.regulatory` fills in where the CMP has none
 * usable; `coppa` can only come from the publisher. As the Swift SDK does.
 *
 * Read off the main thread: the first read of the default `SharedPreferences` loads its file from disk, which
 * would block the main thread (and StrictMode flags it) as an ads request is built there.
 */
internal object Consent {
    /** Where the stored values are read. */
    @VisibleForTesting
    var readDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Reads the stored values; tests watch which thread it runs on. */
    @VisibleForTesting
    var collect: (Context) -> ConsentData = ConsentDataProvider::collect

    /**
     * The CMP's values, read on [readDispatcher], merged with [explicit], the publisher's, asked for once the read
     * is back so it is the latest.
     */
    suspend fun read(context: Context, explicit: () -> Regulatory?): Regulatory? {
        val stored = withContext(readDispatcher) { runCatching { collect(context) }.getOrNull() }
        return merge(stored, explicit())
    }

    /** A usable CMP value wins per field; the publisher fills in the rest; `null` when nothing is set. */
    private fun merge(stored: ConsentData?, explicit: Regulatory?): Regulatory? {
        val merged = Regulatory.Builder()
            .gdpr(stored?.gdpr?.let { it == 1 } ?: explicit?.gdpr)
            .gdprConsent(stored?.gdprConsent ?: explicit?.gdprConsent)
            .coppa(explicit?.coppa)
            .gpp(stored?.gpp ?: explicit?.gpp)
            .gppSid(stored?.gppSid ?: explicit?.gppSid)
            .usPrivacy(stored?.usPrivacy ?: explicit?.usPrivacy)
            .build()
        val isEmpty = merged.gdpr == null && merged.gdprConsent == null && merged.coppa == null && merged.gpp == null &&
            merged.gppSid == null && merged.usPrivacy == null
        return if (isEmpty) null else merged
    }
}
