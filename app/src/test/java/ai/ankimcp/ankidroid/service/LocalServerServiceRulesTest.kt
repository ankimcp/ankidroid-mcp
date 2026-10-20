package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.local.LocalServerState
import ai.ankimcp.ankidroid.tunnel.TunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the foreground service treats the local server next to the tunnel. */
class LocalServerServiceRulesTest {
    private val stopped = TunnelState.Stopped(1000, "Disconnected by user")
    private val running = LocalServerState.Running("192.168.1.20", 8765)

    @Test
    fun `a stopped tunnel does not stop the service while the local server is on`() {
        assertTrue(serviceShouldStop(stopped, stopped, localServerEnabled = false))
        assertFalse(serviceShouldStop(stopped, stopped, localServerEnabled = true))
    }

    @Test
    fun `turning the local server off stops the service only when no tunnel run is going on`() {
        assertTrue(serviceShouldStopAfterLocalServer(TunnelState.Idle))
        assertTrue(serviceShouldStopAfterLocalServer(stopped))
        assertFalse(serviceShouldStopAfterLocalServer(TunnelState.Connected("https://x/mcp")))
        assertFalse(serviceShouldStopAfterLocalServer(TunnelState.Connecting(1)))
    }

    @Test
    fun `switching off always stops the server, also after a failed start, but never on the first value`() {
        assertEquals(LocalSwitchAction.Start, localSwitchAction(null, true))
        assertEquals(LocalSwitchAction.Start, localSwitchAction(false, true))
        // The collector does not consult isStarted: a token failure ends the run, the switch-off must still stop.
        assertEquals(LocalSwitchAction.Stop, localSwitchAction(true, false))
        assertEquals(LocalSwitchAction.None, localSwitchAction(null, false))
        assertEquals(LocalSwitchAction.None, localSwitchAction(false, false))
    }

    @Test
    fun `a start for the local server leaves the tunnel alone`() {
        val extras = serviceStartExtras(callerStartsTunnel = false, localServerOnly = true)
        var tunnelStarts = 0

        startTunnelForServiceStart(readExtra = { key, default -> extras[key] ?: default }) { tunnelStarts++ }
        assertEquals(0, tunnelStarts)

        val connect = serviceStartExtras(callerStartsTunnel = false)
        startTunnelForServiceStart(readExtra = { key, default -> connect[key] ?: default }) { tunnelStarts++ }
        assertEquals(1, tunnelStarts)
    }

    @Test
    fun `a start for the tunnel while Remote access is off leaves it alone`() {
        // Connect was queued, then the switch turned off before the service ran (D63).
        val connect = serviceStartExtras(callerStartsTunnel = false)
        var tunnelStarts = 0

        startTunnelForServiceStart(readExtra = { key, default -> connect[key] ?: default }, remoteEnabled = false) { tunnelStarts++ }
        assertEquals(0, tunnelStarts)

        startTunnelForServiceStart(readExtra = { key, default -> connect[key] ?: default }, remoteEnabled = true) { tunnelStarts++ }
        assertEquals(1, tunnelStarts)
    }

    @Test
    fun `notification shows the local server address, and the tunnel only while it runs`() {
        assertEquals(listOf("Local MCP server on 192.168.1.20:8765"), notificationLines(TunnelState.Idle, running))
        assertEquals(listOf("Local MCP server on 192.168.1.20:8765"), notificationLines(stopped, running))
        assertEquals(
            listOf("Connected: Claude can reach your decks", "Local MCP server on 192.168.1.20:8765"),
            notificationLines(TunnelState.Connected("https://x/mcp"), running),
        )
        assertEquals(listOf("Local MCP server: connect to Wi-Fi"), notificationLines(TunnelState.Idle, LocalServerState.NoWifi))
        assertEquals(listOf("Connecting…"), notificationLines(TunnelState.Connecting(1), LocalServerState.Off))
        assertEquals(listOf("Not connected"), notificationLines(TunnelState.Idle, LocalServerState.Off))
    }
}
