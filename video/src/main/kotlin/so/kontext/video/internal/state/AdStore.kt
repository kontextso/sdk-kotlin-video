package so.kontext.video.internal.state

import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException.NoFillReason
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.payloads.AdsResponse

/**
 * The ads one `VideoAds` holds: at most one unshown ad per format, and the ids that left (shown, rejected,
 * expired, failed, destroyed), which an answer may not bring back. `VideoAds` asks it and carries out its
 * [plan]; the ads themselves end through their own `invalidate`.
 *
 * How an answer is followed, format by format ([plan], a pure function; tested as a table in `AdStoreTest`):
 *
 * ```
 *   held   the answer for the format    steps
 *   none   ad X                         Replace(X)
 *   X      ad X                         Keep
 *   X      ad Y                         Drop, Replace(Y)
 *   X      no fill R                    Drop, NoFill(R)
 *   X      ad Y, an id already used     Drop, IgnoreUsed(Y)
 *   none   left out                     NoFill(Unknown), unanswered
 *   X      left out                     Keep (its own expiry still applies)
 * ```
 *
 * A held ad that expired while the request ran leaves first, silently ([dropExpired]), whether the answer names
 * its format or not; the plan is made from what is held after that.
 */
internal class AdStore {
    /** The unshown ad of each format, at most one. */
    val ads = mutableMapOf<AdFormat, VideoAd>()

    /** Ids that left: shown, rejected, expired, failed, destroyed. A response that brings one back is ignored. */
    private val spentIds = mutableSetOf<String>()

    /** A valid ad is held: a failed request then keeps it (the error report's recovery says so). */
    val holdsAds: Boolean get() = ads.values.any { !it.isExpired }

    /** One step of following an answer for one format. */
    sealed class Step {
        abstract val format: AdFormat

        /** The held ad stays as it is. */
        data class Keep(override val format: AdFormat) : Step()

        /** The held ad leaves (as expired: the server replaced it). */
        data class Drop(override val format: AdFormat) : Step()

        /** [payload] becomes the held ad of [format]. */
        data class Replace(override val format: AdFormat, val payload: AdPayload) : Step()

        /** No ad for [format], for [reason]; [isAnswered]: the answer said so (logged), else it left the format out. */
        data class NoFill(override val format: AdFormat, val reason: NoFillReason, val isAnswered: Boolean = true) : Step()

        /** The answer brings [id], already used (shown or rejected while the request ran): ignored, a no-fill. */
        data class IgnoreUsed(override val format: AdFormat, val id: String) : Step()
    }

    /** The held ad of [format] while it is valid: one that expired since the answer (app code ran first) leaves silently. */
    fun ad(format: AdFormat): VideoAd? {
        val held = ads[format] ?: return null
        if (!held.isExpired) return held
        held.invalidate(VideoAd.InvalidationReason.Expired)
        return null
    }

    /** The ids of the valid ads held, for a request's `held`; expired ones leave first, silently (the server knows `expires`). */
    fun heldIds(): List<String> {
        dropExpired()
        return AdFormat.entries.mapNotNull { ads[it]?.id }
    }

    /** Every held ad that expired leaves, silently. */
    fun dropExpired() {
        ads.values.filter { it.isExpired }.forEach { it.invalidate(VideoAd.InvalidationReason.Expired) }
    }

    /**
     * One id is one bid, across answers as within one: an answer that brings, under one format, the id of an ad
     * held under another (one the answer may leave out, so kept) is malformed as a whole, before anything held
     * or spent changes, as an id listed twice in one answer is.
     */
    fun checkIdsAgainstHeld(response: AdsResponse) {
        val results = (response as? AdsResponse.Formats)?.results ?: return
        for (result in results) {
            for (payload in result.ads) {
                val other = ads.entries.firstOrNull { (format, held) -> format != result.format && held.id == payload.id } ?: continue
                throw ApiException.Decoding(IllegalArgumentException("ad ${payload.id} is ${result.format}, and held as ${other.key}"))
            }
        }
    }

    /** What following [response] does to what is held now, format by format. */
    fun plan(response: AdsResponse): List<Step> = plan(response, ads.mapValues { it.value.id }, spentIds)

    /** An ad ended before `show`, or was shown: it leaves its format, and its id is never accepted again. */
    fun left(ad: VideoAd) {
        if (ads[ad.format] === ad) ads.remove(ad.format)
        spentIds += ad.id
    }

    /** [ad] began showing: it leaves its format. */
    fun beganShowing(ad: VideoAd) = left(ad)

    /** `show` reserved [ad] and could not put it on screen: it is held again as before, its id unspent. */
    fun didNotShow(ad: VideoAd, wasHeld: Boolean) {
        spentIds -= ad.id
        if (wasHeld && ads[ad.format] == null) ads[ad.format] = ad
    }

    /** Every valid held ad goes through [endValid] (its `notUsed`); one that expired leaves silently. Then none is held. */
    fun endAll(endValid: (VideoAd) -> Unit) {
        for (ad in ads.values.toList()) {
            if (ad.isExpired) {
                ad.invalidate(VideoAd.InvalidationReason.Expired)
                continue
            }
            endValid(ad)
        }
        ads.clear()
    }

    companion object {
        /** [plan] on plain values: the held ids by format and the ids already used. */
        fun plan(response: AdsResponse, held: Map<AdFormat, String>, spentIds: Set<String>): List<Step> {
            val steps = mutableListOf<Step>()
            for (format in AdFormat.entries) {
                val current = held[format]
                val result = response.result(format)
                if (result == null) {
                    steps += if (current == null) Step.NoFill(format, NoFillReason.Unknown, isAnswered = false) else Step.Keep(format)
                    continue
                }
                val payload = result.ads.firstOrNull()
                if (payload != null && current == payload.id) {
                    steps += Step.Keep(format)
                    continue
                }
                if (current != null) steps += Step.Drop(format)
                steps += when {
                    payload == null -> Step.NoFill(format, result.noFillReason ?: NoFillReason.Unknown)
                    payload.id in spentIds -> Step.IgnoreUsed(format, payload.id)
                    else -> Step.Replace(format, payload)
                }
            }
            return steps
        }
    }
}
