package ai.ankimcp.ankidroid.push

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Posts (or removes) the "Sign in again to AnkiMCP" notification. */
interface SignInNotifier {
    fun showSignInRequired()

    fun dismiss()
}

/**
 * Handles an FCM data message `{type: "wake", reason: "mcp_request"}`. Runs on the FCM thread,
 * so it only does quick work: start the foreground service and the tunnel, and leave the
 * connection attempt (token refresh included) to the tunnel. If that attempt ends because the
 * session expired, the sign-in notification is posted. A user who signed out on purpose gets no
 * notification.
 */
class WakeHandler(
    private val scope: CoroutineScope,
    private val tunnelState: StateFlow<TunnelState>,
    private val isTunnelRunning: () -> Boolean,
    private val isSignedIn: () -> Boolean,
    /** True while a logout is in progress. */
    private val isSigningOut: () -> Boolean,
    /**
     * `startForegroundService` without starting the tunnel (this handler does that); false if
     * Android refused it (already logged and shown).
     */
    private val startService: () -> Boolean,
    private val startTunnel: () -> Unit,
    /** Stops the run this wake started when a logout began meanwhile. */
    private val stopTunnel: () -> Unit,
    /** Cuts a running tunnel's reconnect backoff short. */
    private val nudgeTunnel: () -> Unit,
    private val notifier: SignInNotifier,
    private val log: TunnelLog,
    private val watchTimeoutMs: Long = WATCH_TIMEOUT_MS,
) {
    enum class Outcome { Ignored, AlreadyConnected, Nudged, Started, NotSignedIn, SigningOut, StartBlocked }

    fun onMessage(data: Map<String, String>): Outcome {
        if (data[KEY_TYPE] != TYPE_WAKE) {
            log.debug("Ignoring push message of type '${data[KEY_TYPE]}'")
            return Outcome.Ignored
        }
        val reason = data[KEY_REASON] ?: "unspecified"
        if (isSigningOut()) {
            log.info("Wake-up ($reason): signing out, not connecting")
            return Outcome.SigningOut
        }
        if (isTunnelRunning()) {
            if (tunnelState.value is TunnelState.Connected) {
                log.debug("Wake-up ($reason): already connected")
                return Outcome.AlreadyConnected
            }
            log.info("Wake-up ($reason): tunnel is reconnecting, retrying now")
            nudgeTunnel()
            return Outcome.Nudged
        }
        if (!isSignedIn()) {
            // Signed out on purpose (or never signed in): nothing expired, so no notification.
            log.warn("Wake-up ($reason): not signed in, not connecting")
            return Outcome.NotSignedIn
        }
        log.info("Wake-up ($reason): connecting")
        if (!startService()) return Outcome.StartBlocked
        // Started here rather than in the service so the run below is this wake's run.
        startTunnel()
        // A logout that began after the check above has already stopped the tunnel, before this
        // run existed; the credentials it is about to clear would otherwise keep this run going.
        if (isSigningOut()) {
            log.info("Wake-up ($reason): signing out, disconnecting")
            stopTunnel()
            return Outcome.SigningOut
        }
        scope.launch {
            val end = withTimeoutOrNull(watchTimeoutMs) {
                tunnelState.first { it is TunnelState.Connected || it is TunnelState.Stopped }
            }
            if (end is TunnelState.Stopped && end.reloginRequired && !end.signedOut) {
                log.warn("Wake-up ($reason): sign-in required, not connecting")
                notifier.showSignInRequired()
            }
        }
        return Outcome.Started
    }

    companion object {
        const val KEY_TYPE = "type"
        const val KEY_REASON = "reason"
        const val TYPE_WAKE = "wake"

        /** How long to watch the woken tunnel for a sign-in failure (token refresh + first attempts). */
        const val WATCH_TIMEOUT_MS = 120_000L
    }
}
