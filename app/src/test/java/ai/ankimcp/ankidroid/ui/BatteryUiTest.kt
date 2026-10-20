package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.service.FreezingOem
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryUiTest {
    @Test
    fun `freezing makers always get the two App info buttons, exempt or not`() {
        listOf(true, false).forEach { unrestricted ->
            listOf(true, false).forEach { frozen ->
                assertEquals(BatteryAction.MakerAppInfo, batteryAction(unrestricted, FreezingOem.ColorOs, frozen))
            }
        }
    }

    @Test
    fun `other phones get the battery settings list until exempt`() {
        assertEquals(BatteryAction.BatterySettings, batteryAction(unrestricted = false, oem = null, freezeDetected = false))
        assertEquals(BatteryAction.BatterySettings, batteryAction(unrestricted = false, oem = null, freezeDetected = true))
    }

    @Test
    fun `exempt with a freeze seen points at App info, exempt without one needs no button`() {
        assertEquals(BatteryAction.AppInfo, batteryAction(unrestricted = true, oem = null, freezeDetected = true))
        assertEquals(BatteryAction.None, batteryAction(unrestricted = true, oem = null, freezeDetected = false))
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
