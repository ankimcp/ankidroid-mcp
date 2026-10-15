package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.log.LogEntry
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.CloseCodes
import ai.ankimcp.ankidroid.tunnel.TunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundServiceStarterTest {
    private val log = TunnelLog(mirrorToLogcat = false)
    private val blocked = mutableListOf<String>()

    @Test
    fun `refused start is logged and reported instead of crashing`() {
        // Stand-in for ForegroundServiceStartNotAllowedException, which extends IllegalStateException.
        class StartNotAllowed : IllegalStateException("startForegroundService() not allowed")

        val started = startForegroundServiceSafely(log, { blocked += it }) { throw StartNotAllowed() }

        assertFalse(started)
        assertEquals(listOf(SERVICE_START_BLOCKED_REASON), blocked)
        assertTrue(SERVICE_START_BLOCKED_REASON.contains("Open the app to connect"))
        val entry = log.entries.value.single()
        assertEquals(LogEntry.Level.WARN, entry.level)
        assertTrue(entry.message, entry.message.contains("StartNotAllowed"))
    }

    @Test
    fun `successful start reports nothing`() {
        var calls = 0

        val started = startForegroundServiceSafely(log, { blocked += it }) { calls++ }

        assertTrue(started)
        assertEquals(1, calls)
        assertTrue(blocked.isEmpty())
        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun `the service starts the tunnel only when the caller does not`() {
        var starts = 0

        startTunnelUnlessCallerDid(callerStartsTunnel = true) { starts++ }
        assertEquals("a wake-up starts the tunnel itself", 0, starts)

        startTunnelUnlessCallerDid(callerStartsTunnel = false) { starts++ }
        assertEquals("Connect, boot and auto-connect rely on the service", 1, starts)
    }

    @Test
    fun `the service stops only if the tunnel is still stopped when the collector gets there`() {
        val stopped = TunnelState.Stopped(CloseCodes.NORMAL, "Screen turned off", phoneAsleep = true)

        assertTrue(serviceShouldStop(stopped, stopped))
        assertFalse(
            "a wake-up restarted the tunnel before the main thread saw the stop",
            serviceShouldStop(stopped, TunnelState.Connecting(0)),
        )
        assertFalse(serviceShouldStop(TunnelState.Connecting(0), TunnelState.Connecting(0)))
    }
}
