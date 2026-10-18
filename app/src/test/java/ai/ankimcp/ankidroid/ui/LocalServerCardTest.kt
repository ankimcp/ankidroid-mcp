package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.local.LocalServerState
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalServerCardTest {
    @Test
    fun `status line says where the server runs, or what is missing`() {
        assertEquals("Running on 192.168.1.20:8765" to LocalStatusKind.Running, describeLocalServer(LocalServerState.Running("192.168.1.20", 8765)))
        assertEquals("Connect to Wi-Fi" to LocalStatusKind.Waiting, describeLocalServer(LocalServerState.NoWifi))
        assertEquals("Port 8765 is in use." to LocalStatusKind.Error, describeLocalServer(LocalServerState.Failed("Port 8765 is in use.")))
        assertEquals(LocalStatusKind.Waiting, describeLocalServer(LocalServerState.Off).second)
    }
}
