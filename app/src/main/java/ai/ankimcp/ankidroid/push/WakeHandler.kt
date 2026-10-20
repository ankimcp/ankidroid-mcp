package ai.ankimcp.ankidroid.push

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.service.REMOTE_ACCESS_OFF_REASON
import ai.ankimcp.ankidroid.service.SERVICE_START_BLOCKED_REASON
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
 * notification. With Remote access off (D63) a wake-up (one already in flight when the token
 * was unregistered) is ignored.
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
     * Android refused it (already logged).
     */
    private val startService: () -> Boolean,
    private val startTunnel: () -> Unit,
    /**
     * Stops the run this wake started, with the reason shown: the service start was refused, or
     * a logout began meanwhile.
     */
    private val stopTunnel: (reason: String) -> Unit,
    /**
     * Revives a running tunnel: reconnects at once if it looks connected (the relay found no
     * connection, so the socket is dead), otherwise cuts its reconnect backoff short. False if
     * the run had already ended, in which case a new one is started.
     */
    private val wakeRunningTunnel: () -> Boolean,
    private val notifier: SignInNotifier,
    private val log: TunnelLog,
    /** The Remote access switch (D63). */
    private val isRemoteEnabled: () -> Boolean = { true },
    private val watchTimeoutMs: Long = WATCH_TIMEOUT_MS,
) {
    enum class Outcome { Ignored, Reconnected, Nudged, Started, NotSignedIn, SigningOut, StartBlocked, RemoteOff }

    fun onMessage(data: Map<String, String>): Outcome {
        if (data[KEY_TYPE] != TYPE_WAKE) {
            log.debug("Ignoring push message of type '${data[KEY_TYPE]}'")
            return Outcome.Ignored
        }
        val reason = data[KEY_REASON] ?: "unspecified"
        if (!isRemoteEnabled()) {
            log.info("Wake-up ($reason): Remote access is off, not connecting")
            return Outcome.RemoteOff
        }
        if (isSigningOut()) {
            log.info("Wake-up ($reason): signing out, not connecting")
            return Outcome.SigningOut
        }
        if (isTunnelRunning()) {
            // The relay only wakes a phone it has no connection for, so "connected" here is stale
            // (typically a socket that died while the app was frozen).
            val looksConnected = tunnelState.value is TunnelState.Connected
            if (wakeRunningTunnel()) {
                if (looksConnected) {
                    log.info("Wake-up ($reason): tunnel looked connected but the relay has no connection, reconnecting")
                    return Outcome.Reconnected
                }
                log.info("Wake-up ($reason): tunnel is reconnecting, retrying now")
                return Outcome.Nudged
            }
            // The run ended in between (e.g. a freeze stop): start a new one below.
            log.info("Wake-up ($reason): tunnel stopped meanwhile")
        }
        if (!isSignedIn()) {
            // Signed out on purpose (or never signed in): nothing expired, so no notification.
            log.warn("Wake-up ($reason): not signed in, not connecting")
            return Outcome.NotSignedIn
        }
        log.info("Wake-up ($reason): connecting")
        // Started here rather than in the service so the run below is this wake's run, and
        // before the service so its onStartCommand sees this run rather than the previous run's
        // Stopped state (which would make it shut down again).
        startTunnel()
        if (!startService()) {
            stopTunnel(SERVICE_START_BLOCKED_REASON)
            return Outcome.StartBlocked
        }
        // Remote access turned off after the check above: its stop found nothing running yet, so
        // this run would outlive the switch.
        if (!isRemoteEnabled()) {
            log.info("Wake-up ($reason): Remote access turned off, disconnecting")
            stopTunnel(REMOTE_ACCESS_OFF_REASON)
            return Outcome.RemoteOff
        }
        // A logout that began after the check above has already stopped the tunnel, before this
        // run existed; the credentials it is about to clear would otherwise keep this run going.
        if (isSigningOut()) {
            log.info("Wake-up ($reason): signing out, disconnecting")
            stopTunnel("Signing out")
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
