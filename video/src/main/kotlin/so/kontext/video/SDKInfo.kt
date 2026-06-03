package so.kontext.video

/**
 * Compile-time SDK identity (name + platform + version). Will be sent in
 * ad requests, error reports, and any iframe URLs once the SDK is
 * implemented. Mirrors the structure in `sdk-kotlin` so the server can
 * attribute analytics + bug reports per SDK.
 *
 * `VERSION` is sourced from `BuildConfig.SDK_VERSION`, which is set by
 * `build.gradle.kts` from the `sdkVersion` property (default value lives
 * in `gradle/libs.versions.toml`; CI patch releases override via
 * `-PsdkVersion=X.Y.Z`). Single source of truth — the published Maven
 * artifact version and the version reported to the ad server can't drift.
 */
internal object SDKInfo {
    const val NAME: String = "sdk-kotlin-video"
    val VERSION: String = BuildConfig.SDK_VERSION
    const val PLATFORM: String = "android"
}
