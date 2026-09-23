package so.kontext.video.network

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import so.kontext.video.Constants
import so.kontext.video.SDKInfo
import so.kontext.video.internal.log

/**
 * `POST /init` — fired once at session creation, in the background. Lets the
 * server override client defaults or disable the session. Never throws and
 * never blocks: any failure returns `null` and the SDK keeps its defaults.
 */
internal class InitService(private val adServerUrl: String) {
    @Serializable
    class InitRequest(
        @SerialName("publisher_token") val publisherToken: String,
        @SerialName("user_id") val userId: String,
        @SerialName("install_id") val installId: String,
        val sdk: SDKInfo.Dto,
        val app: App,
    ) {
        @Serializable
        class App(@SerialName("bundle_id") val bundleId: String, val version: String) {
            companion object {
                fun collect(context: Context): App {
                    val version = runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: ""
                    return App(bundleId = context.packageName, version = version)
                }
            }
        }
    }

    @Serializable
    class InitResponse(
        /** `false` = the server permanently disabled this session; stop requesting ads. */
        val enabled: Boolean = true,
        /** Preload request timeout override, in milliseconds. */
        @SerialName("preloadTimeout") val preloadTimeoutMs: Long? = null,
    )

    suspend fun fetchInit(request: InitRequest): InitResponse? = try {
        val response = HttpClient.postJson("$adServerUrl/init", AdsService.json.encodeToString(request), Constants.INIT_TIMEOUT_MS)
        if (response.statusCode in 200..299 && response.body.isNotBlank()) {
            AdsService.json.decodeFromString(InitResponse.serializer(), response.body)
        } else {
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("/init failed: $e")
        null
    }
}
