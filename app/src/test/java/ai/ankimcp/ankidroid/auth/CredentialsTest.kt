package ai.ankimcp.ankidroid.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsTest {
    private val user = UserInfo("u-1", "a@b.com", "plus")

    @Test
    fun `round-trips with an id_token`() {
        val credentials = Credentials("at", "rt", "2026-01-01T00:00:00Z", user, idToken = "idt-1")

        val json = credentials.toJson()

        assertTrue(json, json.contains("\"id_token\":\"idt-1\""))
        assertEquals(credentials, Credentials.fromJson(json))
    }

    @Test
    fun `round-trips without an id_token and omits the key`() {
        val credentials = Credentials("at", "rt", "2026-01-01T00:00:00Z", user)

        val json = credentials.toJson()

        assertFalse(json, json.contains("id_token"))
        assertEquals(credentials, Credentials.fromJson(json))
    }

    @Test
    fun `JSON stored before id_token existed still parses`() {
        val old = """{"access_token":"at","refresh_token":"rt","expires_at":"2026-01-01T00:00:00Z",""" +
            """"user":{"id":"u-1","email":"a@b.com","tier":"plus"}}"""

        val credentials = Credentials.fromJson(old)

        assertEquals("at", credentials.accessToken)
        assertNull(credentials.idToken)
    }

    @Test
    fun `toString never contains the tokens`() {
        val text = Credentials("at-secret", "rt-secret", "2026-01-01T00:00:00Z", user, idToken = "idt-secret").toString()

        assertFalse(text, text.contains("idt-secret"))
        assertFalse(text, text.contains("at-secret"))
        assertFalse(text, text.contains("rt-secret"))
    }
}
