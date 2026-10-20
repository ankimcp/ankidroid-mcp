package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.local.LocalServerState
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.TunnelState

/** Shown when Android refused to start the tunnel service from the background. */
const val SERVICE_START_BLOCKED_REASON = "Could not start in the background. Open the app to connect."

/** The stop reason when the user turns Remote access off (D63). */
const val REMOTE_ACCESS_OFF_REASON = "Remote access off"

/** Boolean extra on the service's start intent: the caller starts the tunnel itself. */
internal const val EXTRA_CALLER_STARTS_TUNNEL = "ai.ankimcp.ankidroid.extra.CALLER_STARTS_TUNNEL"

/** Boolean extra on the service's start intent: started for the local server, not the tunnel. */
internal const val EXTRA_LOCAL_SERVER_ONLY = "ai.ankimcp.ankidroid.extra.LOCAL_SERVER_ONLY"

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
internal fun serviceStartExtras(callerStartsTunnel: Boolean, localServerOnly: Boolean = false): Map<String, Boolean> =
    mapOf(EXTRA_CALLER_STARTS_TUNNEL to callerStartsTunnel, EXTRA_LOCAL_SERVER_ONLY to localServerOnly)

/**
 * The service's side of [serviceStartExtras]: reads the start intent through [readExtra]
 * (`Intent.getBooleanExtra`) and starts the tunnel unless the caller does, the start was only
 * for the local server, or Remote access is off (D63: a Connect queued before the switch was
 * turned off must not start a run).
 */
internal fun startTunnelForServiceStart(
    readExtra: (key: String, default: Boolean) -> Boolean,
    remoteEnabled: Boolean = true,
    startTunnel: () -> Unit,
) {
    if (readExtra(EXTRA_LOCAL_SERVER_ONLY, false) || !remoteEnabled) return
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
internal fun serviceShouldStop(collected: TunnelState, current: TunnelState, localServerEnabled: Boolean = false): Boolean =
    !localServerEnabled && collected is TunnelState.Stopped && current is TunnelState.Stopped

/**
 * Whether the service stops after the local server was turned off: only if no tunnel run is
 * going on. Idle counts as ended here (a service started only for the local server never ran
 * the tunnel), which the tunnel collector cannot assume (see [serviceShouldStop]).
 */
internal fun serviceShouldStopAfterLocalServer(tunnel: TunnelState): Boolean =
    tunnel is TunnelState.Idle || tunnel is TunnelState.Stopped

internal enum class LocalSwitchAction { Start, Stop, None }

/**
 * What the service does when it sees the local server switch at [enabled], having seen
 * [previous] before (null: the collector's first value). Stop on every on-to-off change,
 * whether or not the server's run is still active (a failed start ended it); never on the
 * first value, which for a tunnel-only start is just "off" while the tunnel may not have
 * started yet.
 */
internal fun localSwitchAction(previous: Boolean?, enabled: Boolean): LocalSwitchAction = when {
    enabled -> LocalSwitchAction.Start
    previous == true -> LocalSwitchAction.Stop
    else -> LocalSwitchAction.None
}

/**
 * The notification text: the tunnel's state while a run is going on (or when nothing else is
 * running), and the local server's state while it is on, one per line.
 */
internal fun notificationLines(tunnel: TunnelState, local: LocalServerState): List<String> {
    val localLine = when (local) {
        LocalServerState.Off -> null
        LocalServerState.NoWifi -> "Local MCP server: connect to Wi-Fi"
        LocalServerState.PublicAddress -> "Local MCP server: off, this Wi-Fi gives a public address"
        is LocalServerState.Running -> "Local MCP server on ${local.host}:${local.port}"
        is LocalServerState.Failed -> "Local MCP server: ${local.reason}"
    }
    val tunnelEnded = tunnel is TunnelState.Idle || tunnel is TunnelState.Stopped
    val tunnelLine = if (localLine != null && tunnelEnded) null else TunnelForegroundService.describe(tunnel)
    return listOfNotNull(tunnelLine, localLine)
}
