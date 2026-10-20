package ai.ankimcp.ankidroid.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAccessSettingsTest {
    @Test
    fun `on by default, and an absent key from an older build counts as on`() {
        assertTrue(RemoteAccessSettings(InMemoryKeyValueStore()).enabled.value)
        val older = InMemoryKeyValueStore(mapOf(LocalServerSettings.KEY_ENABLED to true, ConnectionSettings.KEY_IDLE_MINUTES to 30L))
        assertTrue(RemoteAccessSettings(older).isEnabled)
    }

    @Test
    fun `off is persisted`() {
        val store = InMemoryKeyValueStore()
        RemoteAccessSettings(store).setEnabled(false)

        val reread = RemoteAccessSettings(store)
        assertFalse(reread.enabled.value)
        assertFalse(reread.isEnabled)
    }

    @Test
    fun `turning it on again is persisted too`() {
        val store = InMemoryKeyValueStore(mapOf(RemoteAccessSettings.KEY_ENABLED to false))
        val settings = RemoteAccessSettings(store)
        assertFalse(settings.isEnabled)

        settings.setEnabled(true)

        assertTrue(RemoteAccessSettings(store).isEnabled)
    }
}
