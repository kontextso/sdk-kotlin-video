package so.kontext.video.internal.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.network.ResponseTooLarge
import so.kontext.video.internal.network.UnexpectedFailure
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.payloads.putIfNotNull
import java.util.UUID

/** One error report: what failed, the ids it concerns, and its JSON. Sent by [ErrorReporter]. */
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

    /** The system error behind a fault, as a domain and a code only, as the Swift SDK sends it; its text can carry anything. */
    data class Cause(val domain: String, val code: Int)

    /**
     * A failed request as the report's fields: an HTTP status, or the exception's class with `network`. Not the
     * public `VideoAdException.RequestFailure`, which is what the app hears.
     */
    class FailedRequest(error: Throwable) {
        val httpStatus: Int? = (error as? ApiException.Http)?.status

        // A Java exception has no code of its own: 0.
        val cause: Cause? = when (error) {
            is ApiException.Http -> null
            is ApiException.Transport, is ApiException.Decoding -> error.cause?.let { Cause(domainOf(it), 0) }
            else -> Cause(domainOf(error), 0)
        }
        val detail: String? = if (error is ApiException.Transport) "network" else null
    }

    /**
     * A failure's domain that reads the same in every build: a platform exception's class name (`java.`, `javax.`,
     * `android.`, never renamed), and a stable word for the SDK's own and its libraries' exceptions, whose class
     * names R8 renames in a publisher's release build (`a.b.c` would group nothing).
     */
    internal fun domainOf(error: Throwable): String {
        val name = error.javaClass.name
        return when {
            error is ResponseTooLarge -> "too_large"
            error is UnexpectedFailure -> "unexpected"
            error is AdPayload.MalformedField -> "malformed_field"
            error is kotlinx.serialization.SerializationException -> "json"
            name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.") -> name
            else -> "other"
        }
    }

    /** One report's JSON, its strings capped, with the recent events of [EventHistory] as they were recorded. */
    fun json(
        code: Code,
        requestId: String? = null,
        sessionId: String? = null,
        bidId: String? = null,
        vastCode: Int? = null,
        httpStatus: Int? = null,
        cause: Cause? = null,
        detail: String? = null,
    ): JsonObject = buildJsonObject {
        put("id", UUID.randomUUID().toString())
        put("occurredAt", ReportFormat.timestamp())
        put("code", code.wire)
        putIfNotNull("requestId", requestId?.let(ReportFormat::capped))
        putIfNotNull("sessionId", sessionId?.let(ReportFormat::capped))
        putIfNotNull("bidId", bidId?.let(ReportFormat::capped))
        putIfNotNull("vastCode", vastCode)
        putIfNotNull("httpStatus", httpStatus)
        cause?.let {
            put(
                "cause",
                buildJsonObject {
                    put("domain", ReportFormat.capped(it.domain))
                    put("code", it.code)
                },
            )
        }
        putIfNotNull("detail", detail?.let(ReportFormat::capped))
        put("history", JsonArray(EventHistory.recent()))
    }
}
