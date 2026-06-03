package so.kontext.video

/**
 * Public entry point for the Kontext Video SDK.
 *
 * Declared as an `object` so the type cannot be instantiated — it's a pure
 * namespace, the Kotlin equivalent of sdk-swift's case-less enum.
 *
 * This is intentionally a near-empty placeholder: the video SDK does
 * nothing yet. Session creation, ad loading, and player integration will
 * be added here incrementally. See the sibling `sdk-kotlin` repo for the
 * shape the public API is expected to converge on
 * (`KontextAds.createSession(...)` → `Session` → `createAd(...)`).
 */
public object KontextVideo {

    /** Version of the SDK, as reported to the ad server. */
    public val version: String
        get() = SDKInfo.VERSION
}
