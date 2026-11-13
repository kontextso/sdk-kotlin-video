package so.kontext.video.internal.network

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import so.kontext.video.KontextVideo.Content
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAdException.RequestFailure
import so.kontext.video.internal.SdkInitializer
import so.kontext.video.internal.diagnostics.DebugReporter
import so.kontext.video.internal.diagnostics.ErrorReport
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.diagnostics.IntegrationGuard
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.payloads.AdsRequest
import so.kontext.video.internal.payloads.AdsResponse
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.state.SdkState

/**
 * One `VideoAds`'s ads request, from the SDK being on to a parsed answer it may use: one request at a time, made
 * by `VideoAds.format`, which holds the shared task. What the answer does to the held ads is `AdStore`'s plan,
 * carried out by `VideoAds`.
 *
 * 1. A user must be set: without one nothing is sent (`Request(MissingUser)`). Then wait for the SDK
 *    (`/config`); it must be on, and the `VideoAds` still usable.
 * 2. Wait for this `VideoAds`'s outcome beacons still on the wire (`PendingBeacons`, at most 2 s), so the server
 *    has heard a `reject()` made just before; then build the request: the user, the consent and the playhead are
 *    read after every wait (`/config`, the beacons, the user agent, the advertising id), just before the body.
 * 3. POST `video/ads` and parse the answer; an id held under another format makes it malformed. A failure is
 *    reported and thrown as `VideoAdException.Request`.
 * 4. The kill switch in an answer switches the whole SDK off (`SdkInitializer.disable` drops the unshown ads of
 *    every live `VideoAds`) and throws `Disabled`; otherwise each malformed ad left out of its format is reported, and the answer is returned.
 */
internal class AdsFetcher(
    private val content: Content,
    private val section: String?,
    private val variantId: String?,
    private val sessionId: String,
    /** Content time now, for the request. */
    private val playheadMillis: () -> Long,
    /** The `notUsed` and `error` beacons the request waits for. */
    private val pendingBeacons: PendingBeacons,
    /** Throws for an answer whose ids clash with the held ads (`AdStore.checkIdsAgainstHeld`). */
    private val checkIdsAgainstHeld: (AdsResponse) -> Unit,
    /** Throws `Destroyed` or `Disabled` once the `VideoAds` may no longer use an answer. */
    private val checkStillUsable: () -> Unit,
) {
    /** The id of the latest ads request, `null` before the first. */
    var lastRequestId: String? = null
        private set

    /** Asks the server; returns the answer, or throws [VideoAdException]. */
    suspend fun fetch(): AdsResponse {
        // 1. A user, the SDK on, the VideoAds still usable.
        if (!IntegrationGuard.requireUser("ads.format()")) throw failed(RequestFailure.MissingUser)
        val isEnabled = SdkInitializer.waitUntilEnabled()
        checkStillUsable()
        if (!isEnabled) {
            Logger.warning { "${content.id}: not requested, the SDK is ${SdkState.current.phase}" }
            SdkState.current.disabledReason?.let { throw VideoAdException.Disabled(it) }
            throw VideoAdException.InitializationFailed()
        }
        // 2. The outcome beacons first, then the request, the user, the consent and the playhead read now.
        pendingBeacons.await()
        checkStillUsable()
        val configuration = SdkState.current.configuration ?: throw VideoAdException.NotInitialized()
        val context = Environment.context ?: throw VideoAdException.NotInitialized()
        val adsRequest = build(context, configuration.publisherToken)
        // Another video's response may have switched the SDK off meanwhile: nothing leaves.
        checkStillUsable()
        lastRequestId = adsRequest.requestId
        Logger.debug { "${content.id}: requesting at playhead ${adsRequest.playheadMillis} ms${section?.let { " for section $it" } ?: ""}" }
        val requestProperties = mapOf("playhead" to adsRequest.playheadMillis) + (section?.let { mapOf("section" to it) } ?: emptyMap())
        DebugReporter.event("ads.request", sessionId = sessionId, properties = requestProperties)
        // 3. The answer, parsed and checked against the held ads.
        val response = try {
            val answer = ApiClient(configuration.adServerUrl).post("video/ads", adsRequest.body)
            // Decoded off the main thread (every ad validated, up to a 5 MB answer); checked against the held ads
            // back on it, where they live.
            withContext(decodeDispatcher) { decode(answer) }.also(checkIdsAgainstHeld)
        } catch (error: CancellationException) {
            throw error
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
            // Not swallowed: the report carries it, and the app hears the failure it maps to (toRequestFailure).
            checkStillUsable()
            Logger.error { "${content.id}: request ${adsRequest.requestId} failed, ${error.message}" }
            @Suppress("InstanceOfCheckForException") // the code, not the handling, depends on it
            ErrorReporter.report(
                if (error is ApiException.Decoding) ErrorReport.Code.ADS_DECODE_FAILED else ErrorReport.Code.ADS_REQUEST_FAILED,
                requestId = adsRequest.requestId,
                sessionId = sessionId,
                failure = ErrorReport.FailedRequest(error),
            )
            throw failed(error.toRequestFailure())
        }
        // 4. The kill switch switches the whole SDK off and drops the unshown ads of every `VideoAds`, with no notices.
        if (response is AdsResponse.Disabled) {
            SdkInitializer.disable(response.reason)
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
                    requestId = requestId,
                    sessionId = sessionId,
                    detail = "${result.format.wireName}:${dropped.path}",
                )
            }
        }
    }

    /**
     * The request's body; a failure to build it is a failed request: `MissingUser` when the user was cleared while
     * the request waited, `BadResponse` when the device facts or KontextKit threw.
     */
    @Suppress("SwallowedException") // the user's absence, said as the failure format() promises
    private suspend fun build(context: Context, publisherToken: String): AdsRequest = try {
        AdsRequest.make(context, publisherToken, content, section, variantId, playheadMillis(), sessionId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: VideoAdException.Request) {
        // The user was cleared while the request waited.
        checkStillUsable()
        if (error.failure == RequestFailure.MissingUser) IntegrationGuard.requireUser("ads.format()")
        throw failed(error.failure)
    } catch (error: VideoAdException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        // The device facts, the stored ids or KontextKit threw: the app hears a failed request, never a raw
        // exception (format() throws only VideoAdException).
        checkStillUsable()
        Logger.error { "${content.id}: the request could not be built, ${error.message}" }
        ErrorReporter.report(
            ErrorReport.Code.ADS_REQUEST_FAILED,
            requestId = null,
            sessionId = sessionId,
            failure = ErrorReport.FailedRequest(error),
        )
        throw failed(RequestFailure.BadResponse)
    }

    /**
     * The failure as `format()` throws it, said as the debug event `ads.requestFailed`: `failure` is the case's
     * name (`offline`, `timedOut`, `server`, `badResponse`, `missingUser`), `httpStatus` a server error's status.
     */
    private fun failed(failure: RequestFailure): VideoAdException.Request {
        val properties = mutableMapOf<String, Any>("failure" to failure.kind)
        if (failure is RequestFailure.Server) properties["httpStatus"] = failure.status
        DebugReporter.event("ads.requestFailed", sessionId = sessionId, level = DebugReporter.Level.WARNING, properties = properties)
        return VideoAdException.Request(failure)
    }

    companion object {
        /** Where an answer is decoded; tests see which thread. */
        @VisibleForTesting
        var decodeDispatcher: CoroutineDispatcher = Dispatchers.Default

        /** The answer's decoder; tests watch it run. */
        @VisibleForTesting
        var decode: (JsonElement?) -> AdsResponse = { AdsResponse.parse(it) }
    }
}
