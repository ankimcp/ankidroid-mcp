package ai.ankimcp.ankidroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezingOemTest {
    @Test
    fun `matches manufacturer or brand ignoring case`() {
        assertEquals(FreezingOem.ColorOs, FreezingOem.detect("realme", "realme"))
        assertEquals(FreezingOem.ColorOs, FreezingOem.detect("OnePlus", "OnePlus"))
        assertEquals(FreezingOem.ColorOs, FreezingOem.detect("OPPO", null))
        assertEquals(FreezingOem.Xiaomi, FreezingOem.detect("Xiaomi", "Redmi"))
        assertEquals(FreezingOem.Xiaomi, FreezingOem.detect(null, "POCO"))
        assertEquals(FreezingOem.Vivo, FreezingOem.detect("vivo", "iQOO"))
        assertEquals(FreezingOem.Huawei, FreezingOem.detect("HUAWEI", "HUAWEI"))
        assertEquals(FreezingOem.Huawei, FreezingOem.detect("HONOR", "HONOR"))
    }

    @Test
    fun `every maker's instructions cover both apps`() {
        FreezingOem.entries.forEach {
            assertTrue(it.name, it.instructions.contains("both AnkiDroid MCP and AnkiDroid"))
        }
    }

    @Test
    fun `stock and other makers are not matched`() {
        assertNull(FreezingOem.detect("Google", "google"))
        assertNull(FreezingOem.detect("samsung", "samsung"))
        assertNull(FreezingOem.detect(null, null))
        assertNull("no substring matches", FreezingOem.detect("Oppolike", "unrealme"))
    }
}
