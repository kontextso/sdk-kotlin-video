package so.kontext.video

import kotlinx.serialization.Serializable

/**
 * Compile-time SDK identity (name + platform + version) sent with every
 * server request.
 *
 * `VERSION` is sourced from `BuildConfig.SDK_VERSION`, set by
 * `build.gradle.kts` from the `sdkVersion` property (default in
 * `gradle/libs.versions.toml`; CI overrides via `-PsdkVersion=X.Y.Z`), so the
 * published Maven version and the version reported to the ad server can't drift.
 */
internal object SDKInfo {
    const val NAME: String = "sdk-kotlin-video"
    val VERSION: String = BuildConfig.SDK_VERSION
    const val PLATFORM: String = "android"

    @Serializable
    data class Dto(val name: String, val platform: String, val version: String)

    fun dto(): Dto = Dto(name = NAME, platform = PLATFORM, version = VERSION)
}
