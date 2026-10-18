package ai.ankimcp.ankidroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryUiTest {
    @Test
    fun `the battery card is always shown, the D42 card with the server off`() {
        assertEquals(StatusBattery.Tunnel, statusBattery(localEnabled = false))
    }

    @Test
    fun `with the local server on, its card is the only one`() {
        assertEquals(StatusBattery.Local, statusBattery(localEnabled = true))
    }

    @Test
    fun `the notifications card talks about the tunnel when signed in, server on or off`() {
        assertEquals(NotificationsUse.Tunnel, notificationsUse(signedIn = true, localEnabled = false))
        assertEquals(NotificationsUse.Tunnel, notificationsUse(signedIn = true, localEnabled = true))
    }

    @Test
    fun `signed out, the notifications card talks about the local server while it is on, generically otherwise`() {
        assertEquals(NotificationsUse.Local, notificationsUse(signedIn = false, localEnabled = true))
        assertEquals(NotificationsUse.Idle, notificationsUse(signedIn = false, localEnabled = false))
    }
}
