package so.kontext.video.internal.network

import kotlinx.coroutines.CancellationException
import so.kontext.video.KontextVideo.Content
import so.kontext.video.VideoAdException
import so.kontext.video.internal.core.Sdk
import so.kontext.video.internal.diagnostics.DebugReporter
import so.kontext.video.internal.diagnostics.ErrorReport
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.payloads.AdsRequest
import so.kontext.video.internal.payloads.AdsResponse
import so.kontext.video.internal.platform.Environment

/**
 * One `VideoAds`'s ads request, from the SDK being on to a parsed answer it may use: one request at a time, made
 * by `VideoAds.format`, which holds the shared task. What the answer does to the held ads is `AdStore`'s plan,
 * carried out by `VideoAds`.
 *
 * 1. Wait for the SDK (`/config`); it must be on, and the `VideoAds` still usable.
 * 2. Build the request: `held` is read after the waits (`/config`, the user agent, the advertising id), just
 *    before the body, so an ad shown, rejected or expired meanwhile is not listed.
 * 3. POST `video/ads` and parse the answer; an id held under another format makes it malformed. A failure is
 *    reported and thrown as `VideoAdException.Request`.
 * 4. The kill switch in an answer switches the whole SDK off (the held ads dropped by [onDisabled]) and throws
 *    `Disabled`; otherwise each malformed ad left out of its format is reported, and the answer is returned.
 */
internal class AdsFetcher(
    private val content: Content,
    private val labels: Map<String, String>,
    private val sessionId: String,
    /** Content time now, for the request. */
    private val playheadMillis: () -> Long,
    /** The ids of the valid ads held, read just before the body. */
    private val heldIds: () -> List<String>,
    /** Throws for an answer whose ids clash with the held ads (`AdStore.checkIdsAgainstHeld`). */
    private val checkIdsAgainstHeld: (AdsResponse) -> Unit,
    /** Whether a valid ad is held, for a failure's recovery. */
    private val holdsAds: () -> Boolean,
    /** Throws `Destroyed` or `Disabled` once the `VideoAds` may no longer use an answer. */
    private val checkStillUsable: () -> Unit,
    /** The answer switched the SDK off: every held ad goes. */
    private val onDisabled: () -> Unit,
) {
    /** The id of the latest ads request, `null` before the first. */
    var lastRequestId: String? = null
        private set

    /** Asks the server; returns the answer, or throws [VideoAdException]. */
    suspend fun fetch(): AdsResponse {
        // 1. The SDK on, the VideoAds still usable.
        val isEnabled = Sdk.waitUntilEnabled()
        checkStillUsable()
        if (!isEnabled) {
            Logger.warning { "${content.id}: not requested, the SDK is ${Sdk.state.phase}" }
            Sdk.disabledReason?.let { throw VideoAdException.Disabled(it) }
            throw VideoAdException.InitializationFailed()
        }
        // 2. The request, `held` read now.
        val configuration = Sdk.configuration ?: throw VideoAdException.NotInitialized()
        val context = Environment.context ?: throw VideoAdException.NotInitialized()
        val adsRequest = AdsRequest.make(context, configuration.publisherToken, content, labels, playheadMillis(), sessionId, heldIds)
        // Another video's response may have switched the SDK off meanwhile: nothing leaves.
        checkStillUsable()
        lastRequestId = adsRequest.requestId
        Logger.debug { "${content.id}: requesting at playhead ${adsRequest.playheadMillis} ms, holding ${adsRequest.held.size}" }
        DebugReporter.event("ads.request", state = mapOf("held" to adsRequest.held.size, "playhead" to adsRequest.playheadMillis))
        // 3. The answer, parsed and checked against the held ads.
        val response = try {
            AdsResponse.parse(ApiClient(configuration.adServerUrl).post("video/ads", adsRequest.body)).also(checkIdsAgainstHeld)
        } catch (error: CancellationException) {
            throw error
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
            // Not swallowed: the report carries it, and the app hears the failure it maps to (toRequestFailure).
            checkStillUsable()
            Logger.error { "${content.id}: request ${adsRequest.requestId} failed, ${error.message}" }
            @Suppress("InstanceOfCheckForException") // the code, not the handling, depends on it
            ErrorReporter.report(
                if (error is ApiException.Decoding) ErrorReport.Code.ADS_DECODE_FAILED else ErrorReport.Code.ADS_REQUEST_FAILED,
                ErrorReport.Stage.REQUEST,
                requestId = adsRequest.requestId,
                sessionId = sessionId,
                failure = ErrorReport.FailedRequest(error),
                recovery = if (holdsAds()) ErrorReport.Recovery.HELD_ADS_KEPT else ErrorReport.Recovery.NO_AD,
            )
            throw VideoAdException.Request(error.toRequestFailure())
        }
        // 4. The kill switch switches the whole SDK off and drops every ad, with no notices.
        if (response is AdsResponse.Disabled) {
            Sdk.disable(response.reason)
            onDisabled()
            throw VideoAdException.Disabled(response.reason)
        }
        checkStillUsable()
        reportDroppedAds(response, adsRequest.requestId)
        return response
    }

    /**
     * A malformed ad was left out of its format, the rest of the answer counts: reported once per dropped ad,
     * with its format and the field that failed (`overlay:price.currency`), as the Swift SDK does.
     */
    private fun reportDroppedAds(response: AdsResponse, requestId: String) {
        val results = (response as? AdsResponse.Formats)?.results ?: return
        for (result in results) {
            for (dropped in result.dropped) {
                Logger.error { "${result.format} for ${content.id}: an ad of request $requestId is malformed at ${dropped.path} ($dropped); left out" }
                ErrorReporter.report(
                    ErrorReport.Code.ADS_DECODE_FAILED,
                    ErrorReport.Stage.REQUEST,
                    requestId = requestId,
                    sessionId = sessionId,
                    detail = "${result.format.wireName}:${dropped.path}",
                    recovery = if (result.ads.isEmpty()) ErrorReport.Recovery.NO_AD else null,
                )
            }
        }
    }
}
