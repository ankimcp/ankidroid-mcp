package ai.ankimcp.ankidroid.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeNoticeTest {
    private val store = InMemoryKeyValueStore()
    private var now = 1_000_000L

    private fun notice() = FreezeNotice(store) { now }

    @Test
    fun `a detected freeze is persisted`() {
        notice().markDetected()

        assertTrue(notice().detected.value)
    }

    @Test
    fun `granting the battery exemption clears it`() {
        val notice = notice().apply { markDetected() }

        notice.onBatteryExemption(wasExempt = true, isExempt = true)
        assertTrue("already exempt: the exemption did not help, keep the note", notice.detected.value)

        notice.onBatteryExemption(wasExempt = false, isExempt = true)
        assertFalse(notice.detected.value)
        assertFalse(notice().detected.value)
    }

    @Test
    fun `an exempt app with no freeze for 7 days drops the notice`() {
        val notice = notice().apply { markDetected() }

        now += FreezeNotice.STALE_AFTER_MS - 1
        notice.onBatteryExemption(wasExempt = true, isExempt = true)
        assertTrue(notice.detected.value)

        now += 1
        notice.onBatteryExemption(wasExempt = false, isExempt = false)
        assertTrue("not exempt: keep pointing at the setting", notice.detected.value)
        notice.onBatteryExemption(wasExempt = true, isExempt = true)
        assertFalse(notice.detected.value)
    }

    @Test
    fun `a new freeze restarts the 7 days`() {
        val notice = notice().apply { markDetected() }
        now += FreezeNotice.STALE_AFTER_MS - 1
        notice.markDetected()

        now += 2
        notice.onBatteryExemption(wasExempt = true, isExempt = true)
        assertTrue(notice.detected.value)
    }

    @Test
    fun `dismiss clears it`() {
        val notice = notice().apply { markDetected() }

        notice.dismiss()

        assertFalse(notice.detected.value)
        assertFalse(notice().detected.value)
    }
}
