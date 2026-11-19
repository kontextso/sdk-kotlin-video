package so.kontext.video.internal.state

import androidx.annotation.VisibleForTesting
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.State
import so.kontext.video.KontextVideo.User

/**
 * One source of truth: the one place every fact of the SDK's own life lives (where it is, the configuration it was
 * initialized with, what `/config` said of the reports, the app's inputs), and the only way those facts change.
 * Everyone else asks the one shared instance, [current]: `KontextVideo`, the ads requests, the error and debug
 * reporters. The data only: which moves are legal is [SdkLifecycle]'s rule, which [moveTo] asks. No Android, no
 * side effects; `SdkInitializer` acts on it (the shell: `/config`, its retry, the reporters).
 */
internal class SdkState {
    /** Where the SDK is now, as `KontextVideo.state` shows it. It moves only through [moveTo]. */
    var phase: State = State.NotInitialized
        private set

    /** What `initialize` was given, recorded once by [recordConfiguration]. */
    var configuration: Configuration? = null
        private set

    /**
     * What `/config` said of error reports (`reportErrors`): `null` until it answers (the reports wait in memory),
     * then on or off. Off is final for the launch. Set by [recordErrorReporting].
     */
    var isErrorReportingOn: Boolean? = null
        private set

    /** What `/config` said of debug events (`reportDebug`); off until it answers. Set by [recordDebugReporting]. */
    var isDebugReportingOn: Boolean = false
        private set

    /** What the publisher knows about the viewer (`KontextVideo.user`); the next ads request carries it. */
    var user: User? = null
        private set

    /** The app's privacy and consent signals (`KontextVideo.regulatory`). */
    var regulatory: Regulatory? = null
        private set

    /** `initialize` was called: the phase left [State.NotInitialized], never to return. */
    val isInitialized: Boolean get() = phase != State.NotInitialized

    /** The server's reason while the SDK is switched off, else `null`. */
    val disabledReason: String? get() = (phase as? State.Disabled)?.reason

    /** Moves to [next] when [SdkLifecycle.canMove] allows it, and says whether it did; a refused move changes nothing. */
    fun moveTo(next: State): Boolean {
        if (!SdkLifecycle.canMove(phase, next)) return false
        phase = next
        return true
    }

    /** The configuration `initialize` was given. Once; never replaced. */
    fun recordConfiguration(configuration: Configuration) {
        if (this.configuration == null) this.configuration = configuration
    }

    /** `/config`'s `reportErrors`. An off stays off: nothing turns the reports back on in this launch. */
    fun recordErrorReporting(isOn: Boolean) {
        if (isErrorReportingOn == false) return
        isErrorReportingOn = isOn
    }

    /** `/config`'s `reportDebug`. */
    fun recordDebugReporting(isOn: Boolean) {
        isDebugReportingOn = isOn
    }

    /** The app set `KontextVideo.user`. */
    fun recordUser(user: User?) {
        this.user = user
    }

    /** The app set `KontextVideo.regulatory`. */
    fun recordRegulatory(regulatory: Regulatory?) {
        this.regulatory = regulatory
    }

    /** As if `/config` had enabled the SDK with [configuration], without it. Tests only. */
    @VisibleForTesting
    fun enableForTesting(configuration: Configuration) {
        this.configuration = configuration
        phase = State.Enabled
    }

    companion object {
        /** The SDK's state: one per process, as the SDK is. */
        var current: SdkState = SdkState()
            private set

        /** Back to a fresh state, as before `initialize`. Tests only. */
        fun resetForTesting() {
            current = SdkState()
        }
    }
}
