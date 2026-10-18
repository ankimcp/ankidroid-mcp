package ai.ankimcp.ankidroid.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServerSettingsTest {
    @Test
    fun `off and port 8765 by default`() {
        val settings = LocalServerSettings(InMemoryKeyValueStore())

        assertFalse(settings.enabled.value)
        assertEquals(8765, settings.port.value)
        // Open by default (owner decision, D55).
        assertFalse(settings.requireToken.value)
    }

    @Test
    fun `Require access token is persisted`() {
        val store = InMemoryKeyValueStore()
        LocalServerSettings(store).setRequireToken(true)

        assertTrue(LocalServerSettings(store).requireToken.value)
    }

    @Test
    fun `values are persisted`() {
        val store = InMemoryKeyValueStore()
        LocalServerSettings(store).apply {
            setEnabled(true)
            assertTrue(setPort(9000))
        }

        val reread = LocalServerSettings(store)
        assertTrue(reread.enabled.value)
        assertEquals(9000, reread.port.value)
    }

    @Test
    fun `the old sign_in_skipped flag is removed on first read and the switch is kept`() {
        val store = InMemoryKeyValueStore(
            mapOf(LocalServerSettings.LEGACY_KEY_SIGN_IN_SKIPPED to true, LocalServerSettings.KEY_ENABLED to true),
        )

        val settings = LocalServerSettings(store)

        assertFalse(store.contains(LocalServerSettings.LEGACY_KEY_SIGN_IN_SKIPPED))
        assertTrue(settings.enabled.value)
    }

    @Test
    fun `ports outside 1024-65535 are refused`() {
        val settings = LocalServerSettings(InMemoryKeyValueStore())

        assertFalse(settings.setPort(80))
        assertFalse(settings.setPort(70000))
        assertEquals(8765, settings.port.value)
        assertTrue(settings.setPort(1024))
        assertTrue(settings.setPort(65535))
    }

    @Test
    fun `a stored invalid port falls back to the default`() {
        val settings = LocalServerSettings(InMemoryKeyValueStore(mapOf(LocalServerSettings.KEY_PORT to 22L)))

        assertEquals(8765, settings.port.value)
    }
}
