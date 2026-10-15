package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.service.FreezingOem
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryUiTest {
    private fun placement(unrestricted: Boolean, oem: FreezingOem?, freezeDetected: Boolean) =
        BatteryUi(unrestricted, oem, freezeDetected).placement

    @Test
    fun `stock Android keeps the card low and hides it once exempt`() {
        assertEquals(BatteryUi.Placement.Bottom, placement(unrestricted = false, oem = null, freezeDetected = false))
        assertEquals(BatteryUi.Placement.Hidden, placement(unrestricted = true, oem = null, freezeDetected = false))
    }

    @Test
    fun `freezing makers get the card high until exempt, then low`() {
        assertEquals(BatteryUi.Placement.Top, placement(unrestricted = false, oem = FreezingOem.ColorOs, freezeDetected = false))
        assertEquals(BatteryUi.Placement.Bottom, placement(unrestricted = true, oem = FreezingOem.ColorOs, freezeDetected = false))
    }

    @Test
    fun `a detected freeze always puts the card high`() {
        assertEquals(BatteryUi.Placement.Top, placement(unrestricted = true, oem = null, freezeDetected = true))
        assertEquals(BatteryUi.Placement.Top, placement(unrestricted = true, oem = FreezingOem.Xiaomi, freezeDetected = true))
    }
}
