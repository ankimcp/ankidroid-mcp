package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.TunnelState

/** Shown when Android refused to start the tunnel service from the background. */
const val SERVICE_START_BLOCKED_REASON = "Could not start in the background. Open the app to connect."

/** Boolean extra on the service's start intent: the caller starts the tunnel itself. */
internal const val EXTRA_CALLER_STARTS_TUNNEL = "ai.ankimcp.ankidroid.extra.CALLER_STARTS_TUNNEL"

/**
 * Runs [start], a `startForegroundService` call, and returns false instead of crashing when
 * Android refuses it: `ForegroundServiceStartNotAllowedException` on API 31+ (the app is in the
 * background), plain `IllegalStateException` on 26-30. The former extends the latter, so one
 * catch covers both. The refusal is logged to the tunnel log and passed to [onBlocked] so the
 * UI can show it.
 */
fun startForegroundServiceSafely(log: TunnelLog, onBlocked: (reason: String) -> Unit, start: () -> Unit): Boolean =
    try {
        start()
        true
    } catch (e: IllegalStateException) {
        log.warn("Could not start the tunnel service: ${e.javaClass.simpleName}")
        onBlocked(SERVICE_START_BLOCKED_REASON)
        false
    }

/** The boolean extras the service's start intent carries. */
internal fun serviceStartExtras(callerStartsTunnel: Boolean): Map<String, Boolean> =
    mapOf(EXTRA_CALLER_STARTS_TUNNEL to callerStartsTunnel)

/**
 * The service's side of [serviceStartExtras]: reads the start intent through [readExtra]
 * (`Intent.getBooleanExtra`) and starts the tunnel unless the caller does.
 */
internal fun startTunnelForServiceStart(readExtra: (key: String, default: Boolean) -> Boolean, startTunnel: () -> Unit) {
    startTunnelUnlessCallerDid(readExtra(EXTRA_CALLER_STARTS_TUNNEL, false), startTunnel)
}

/**
 * What the service does with the tunnel when it starts. A wake-up starts the tunnel itself
 * right after starting the service ([callerStartsTunnel]); a second start from the service
 * would restart a run that had already ended (e.g. not signed in) and run it twice.
 */
internal fun startTunnelUnlessCallerDid(callerStartsTunnel: Boolean, startTunnel: () -> Unit) {
    if (!callerStartsTunnel) startTunnel()
}

/**
 * Whether the service stops after its state collector saw [collected]. The collector runs on
 * the main thread behind the tunnel: a wake-up may already have started a new run, which sets
 * the state to Connecting synchronously, so the [current] state must still be Stopped too.
 * (`isRunning` can't be used: a run that ends by itself publishes Stopped while its job is still
 * active.) A wake-up's start that is queued but not yet delivered is covered by the service
 * stopping with `stopSelfResult(lastStartId)`.
 */
internal fun serviceShouldStop(collected: TunnelState, current: TunnelState): Boolean =
    collected is TunnelState.Stopped && current is TunnelState.Stopped
