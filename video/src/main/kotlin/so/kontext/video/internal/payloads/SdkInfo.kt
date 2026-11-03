package so.kontext.video.internal.payloads

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.video.internal.SDK_VERSION

/**
 * The SDK's identity, sent as the `sdk` object with every server request. As the Swift SDK's `SDKInfo`, except that
 * the version is written once in `gradle/libs.versions.toml` and generated into [SDK_VERSION] at build time.
 */
internal object SdkInfo {
    /** As the server knows this SDK; the Swift SDK is `sdk-swift-video`. */
    const val NAME = "sdk-kotlin-video"

    fun json(): JsonObject = buildJsonObject {
        put("name", NAME)
        put("version", SDK_VERSION)
    }
}
