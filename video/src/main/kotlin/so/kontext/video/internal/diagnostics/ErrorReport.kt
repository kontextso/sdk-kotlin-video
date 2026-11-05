package so.kontext.video.internal.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.payloads.putIfNotNull
import java.util.UUID

/** One error report: its vocabulary (what failed, where, what the SDK did) and its JSON. Sent by [ErrorReporter]. */
internal object ErrorReport {
    /** What failed. Stable: the server groups by it. */
    enum class Code(val wire: String) {
        CONFIG_FAILED("config.failed"),
        ADS_REQUEST_FAILED("ads.request_failed"),
        ADS_DECODE_FAILED("ads.decode_failed"),
        CREATIVE_FAILED("ad.creative_failed"),
        LOAD_TIMEOUT("ad.load_timeout"),
        FRAME_LOAD_FAILED("frame.load_failed"),
        PROCESS_TERMINATED("frame.process_terminated"),
        UNRESPONSIVE("frame.unresponsive"),
        PROTOCOL_VIOLATION("frame.protocol_violation"),
        PRESENTATION_FAILED("ad.presentation_failed"),
        DISMISSAL_TIMED_OUT("ad.dismissal_timed_out"),
        EMPTY_TOKEN("integration.empty_token"),
        BAD_SERVER_URL("integration.bad_server_url"),
        NOT_INITIALIZED("integration.not_initialized"),
        BAD_CONTENT("integration.bad_content"),
    }

    /** Where in the SDK it failed. */
    enum class Stage { CONFIG, REQUEST, LOAD, PRESENTATION, PLAYBACK, INTEGRATION }

    /** What the SDK did about it. */
    enum class Recovery(val wire: String) {
        HELD_ADS_KEPT("held_ads_kept"),
        NO_AD("no_ad"),
        AD_ENDED_FAILED("ad_ended_failed"),
        AD_DROPPED_BEFORE_SHOW("ad_dropped_before_show"),

        /** The system never finished; `onAdEnded` was delivered anyway. */
        DID_END_DELIVERED("did_end_delivered"),
    }

    /** A system error as a domain and a code only, as the Swift SDK sends it; its text can carry anything. */
    data class Underlying(val domain: String, val code: Int)

    /**
     * A failed request as the report's fields: an HTTP status, or the exception's class with `network`. Not the
     * public `VideoAdException.RequestFailure`, which is what the app hears.
     */
    class FailedRequest(error: Throwable) {
        val httpStatus: Int? = (error as? ApiException.Http)?.status

        // A Java exception has no code of its own: 0.
        val underlying: Underlying? = when (error) {
            is ApiException.Http -> null
            is ApiException.Transport, is ApiException.Decoding -> error.cause?.javaClass?.name?.let { Underlying(it, 0) }
            else -> Underlying(error.javaClass.name, 0)
        }
        val detail: String? = if (error is ApiException.Transport) "network" else null
    }

    /** One report's JSON, its strings capped, with the recent events of [EventHistory]. */
    fun json(
        code: Code,
        stage: Stage,
        requestId: String? = null,
        sessionId: String? = null,
        adId: String? = null,
        vastCode: Int? = null,
        httpStatus: Int? = null,
        underlying: Underlying? = null,
        detail: String? = null,
        recovery: Recovery? = null,
    ): JsonObject = buildJsonObject {
        put("id", UUID.randomUUID().toString())
        put("createdAt", ReportFormat.timestamp())
        put("code", code.wire)
        put("stage", stage.name.lowercase())
        putIfNotNull("requestId", requestId?.let(ReportFormat::capped))
        putIfNotNull("sessionId", sessionId?.let(ReportFormat::capped))
        putIfNotNull("adId", adId?.let(ReportFormat::capped))
        putIfNotNull("vastCode", vastCode)
        putIfNotNull("httpStatus", httpStatus)
        underlying?.let {
            put(
                "underlying",
                buildJsonObject {
                    put("domain", ReportFormat.capped(it.domain))
                    put("code", it.code)
                },
            )
        }
        putIfNotNull("detail", detail?.let(ReportFormat::capped))
        putIfNotNull("recovery", recovery?.wire)
        put(
            "history",
            JsonArray(
                EventHistory.recent().map { moment ->
                    buildJsonObject {
                        put("createdAt", moment.createdAt)
                        put("name", moment.name)
                        putIfNotNull("adId", moment.adId)
                        moment.state?.let { state -> put("state", JsonObject(state.mapValues { ReportFormat.json(it.value) })) }
                    }
                },
            ),
        )
    }
}
