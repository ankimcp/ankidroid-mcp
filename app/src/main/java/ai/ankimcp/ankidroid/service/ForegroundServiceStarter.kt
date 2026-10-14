package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.log.TunnelLog

/** Shown when Android refused to start the tunnel service from the background. */
const val SERVICE_START_BLOCKED_REASON = "Could not start in the background. Open the app to connect."

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
