package ai.ankimcp.ankidroid.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingEndSessionTest {
    @Test
    fun `take on empty returns null`() {
        assertNull(PendingEndSession().take())
    }

    @Test
    fun `set then take returns the url once`() {
        val pending = PendingEndSession()
        pending.set("https://auth.test/logout?a=1")

        assertEquals("https://auth.test/logout?a=1", pending.url.value)
        assertEquals("https://auth.test/logout?a=1", pending.take())
        assertNull(pending.url.value)
        assertNull(pending.take())
    }

    @Test
    fun `a later set replaces an unclaimed url`() {
        val pending = PendingEndSession()
        pending.set("first")
        pending.set("second")

        assertEquals("second", pending.take())
        assertNull(pending.take())
    }
}
