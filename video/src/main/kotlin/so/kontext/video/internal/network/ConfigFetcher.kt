package so.kontext.video.internal.network

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.VideoAdException.RequestFailure
import so.kontext.video.internal.diagnostics.DebugReporter
import so.kontext.video.internal.diagnostics.ErrorReport
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.payloads.ConfigRequest
import so.kontext.video.internal.payloads.ConfigResponse

/**
 * The `/config` request, the twin of `AdsFetcher`: builds it, POSTs `config`, parses the answer. One attempt,
 * whatever the failure; what the answer does to the SDK is `SdkInitializer`'s.
 */
internal object ConfigFetcher {
    /**
     * The answer, or `null` when the request failed: then [onFailure] runs first (the SDK moves to `Failed`), and
     * the failure is logged, reported (`CONFIG_FAILED`) and said as the debug event `config.failed`.
     */
    suspend fun fetch(context: Context, configuration: Configuration, onFailure: () -> Unit): ConfigResponse? {
        var requestId: String? = null
        // Building the body is inside the boundary too: it reads the install id, the package and KontextKit, any of
        // which may throw (a wrong-typed stored id). Whatever fails, `/config` reaches an outcome and the SDK
        // moves to `Failed`, never staying `Initializing` for the launch.
        return try {
            // Off the main thread: the install id's first read loads its SharedPreferences file from disk, often
            // from Application.onCreate.
            val request = withContext(Dispatchers.IO) { ConfigRequest.make(context, configuration) }
            requestId = request.requestId
            ConfigResponse.parse(ApiClient(configuration.adServerUrl).post("config", request.body))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure()
            Logger.error { "/config failed, no ads until it succeeds: ${error.message}" }
            ErrorReporter.report(ErrorReport.Code.CONFIG_FAILED, requestId = requestId, failure = ErrorReport.FailedRequest(error))
            // As `ads.requestFailed`: the failure's kind, and a server error's status.
            val failure = error.toRequestFailure()
            val properties = mutableMapOf<String, Any>("failure" to failure.kind)
            if (failure is RequestFailure.Server) properties["httpStatus"] = failure.status
            DebugReporter.event("config.failed", level = DebugReporter.Level.WARNING, properties = properties)
            null
        }
    }
}
