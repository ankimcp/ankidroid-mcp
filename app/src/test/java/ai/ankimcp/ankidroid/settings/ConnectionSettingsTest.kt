package ai.ankimcp.ankidroid.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionSettingsTest {
    @Test
    fun `a stored always-connected mode becomes on demand and the key is dropped`() {
        val store = InMemoryKeyValueStore(
            mapOf(ConnectionSettings.LEGACY_KEY_MODE to "always", ConnectionSettings.KEY_IDLE_MINUTES to 60L),
        )

        val settings = ConnectionSettings(store)

        assertFalse(store.contains(ConnectionSettings.LEGACY_KEY_MODE))
        assertEquals("the chosen idle window survives", IdleWindow.Minutes60, settings.idleWindow.value)
    }

    @Test
    fun `the first builds' keep connected flag is dropped too`() {
        listOf(true, false).forEach { keepConnected ->
            val store = InMemoryKeyValueStore(mapOf(ConnectionSettings.LEGACY_KEY_KEEP_CONNECTED to keepConnected))

            val settings = ConnectionSettings(store)

            assertFalse(store.contains(ConnectionSettings.LEGACY_KEY_KEEP_CONNECTED))
            assertFalse(store.contains(ConnectionSettings.LEGACY_KEY_MODE))
            assertEquals(IdleWindow.Minutes30, settings.idleWindow.value)
        }
    }

    @Test
    fun `fresh install defaults to a 30 minute window`() {
        val store = InMemoryKeyValueStore()

        val settings = ConnectionSettings(store)

        assertEquals(IdleWindow.Minutes30, settings.idleWindow.value)
        assertEquals(30 * 60_000L, settings.idleWindow.value.millis)
        assertEquals("nothing to migrate, nothing written", emptyMap<String, Any>(), store.values)
    }

    @Test
    fun `idle window persists across instances`() {
        val store = InMemoryKeyValueStore()
        ConnectionSettings(store).setIdleWindow(IdleWindow.Minutes15)

        assertEquals(IdleWindow.Minutes15, ConnectionSettings(store).idleWindow.value)
    }

    @Test
    fun `an unknown stored window falls back to the default`() {
        val store = InMemoryKeyValueStore(mapOf(ConnectionSettings.KEY_IDLE_MINUTES to 45L))

        assertEquals(IdleWindow.Minutes30, ConnectionSettings(store).idleWindow.value)
    }
}
