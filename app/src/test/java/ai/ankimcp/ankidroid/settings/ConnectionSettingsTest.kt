package ai.ankimcp.ankidroid.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSettingsTest {
    @Test
    fun `keep connected on migrates to always connected and drops the old key`() {
        val store = InMemoryKeyValueStore(mapOf(ConnectionSettings.LEGACY_KEY_KEEP_CONNECTED to true))

        val settings = ConnectionSettings(store)

        assertEquals(ConnectionMode.Always, settings.mode.value)
        assertTrue(settings.alwaysConnected)
        assertEquals(IdleWindow.Minutes30, settings.idleWindow.value)
        assertEquals("always", store.values[ConnectionSettings.KEY_MODE])
        assertFalse(store.contains(ConnectionSettings.LEGACY_KEY_KEEP_CONNECTED))
    }

    @Test
    fun `keep connected off migrates to on demand`() {
        val store = InMemoryKeyValueStore(mapOf(ConnectionSettings.LEGACY_KEY_KEEP_CONNECTED to false))

        val settings = ConnectionSettings(store)

        assertEquals(ConnectionMode.OnDemand, settings.mode.value)
        assertEquals("on_demand", store.values[ConnectionSettings.KEY_MODE])
        assertFalse(store.contains(ConnectionSettings.LEGACY_KEY_KEEP_CONNECTED))
    }

    @Test
    fun `fresh install defaults to on demand with a 30 minute window`() {
        val store = InMemoryKeyValueStore()

        val settings = ConnectionSettings(store)

        assertEquals(ConnectionMode.OnDemand, settings.mode.value)
        assertEquals(IdleWindow.Minutes30, settings.idleWindow.value)
        assertEquals(30 * 60_000L, settings.idleWindow.value.millis)
    }

    @Test
    fun `migration runs once and never overrides a chosen mode`() {
        val store = InMemoryKeyValueStore(mapOf(ConnectionSettings.LEGACY_KEY_KEEP_CONNECTED to true))
        ConnectionSettings(store).setMode(ConnectionMode.OnDemand)
        // A stale legacy key (e.g. written by an old build) must not win over the new setting.
        store.values[ConnectionSettings.LEGACY_KEY_KEEP_CONNECTED] = true

        assertEquals(ConnectionMode.OnDemand, ConnectionSettings(store).mode.value)
    }

    @Test
    fun `mode and idle window persist across instances`() {
        val store = InMemoryKeyValueStore()
        ConnectionSettings(store).apply {
            setMode(ConnectionMode.Always)
            setIdleWindow(IdleWindow.Minutes60)
        }

        val reloaded = ConnectionSettings(store)

        assertEquals(ConnectionMode.Always, reloaded.mode.value)
        assertEquals(IdleWindow.Minutes60, reloaded.idleWindow.value)
    }

    @Test
    fun `unknown stored values fall back to the defaults`() {
        val store = InMemoryKeyValueStore(
            mapOf(ConnectionSettings.KEY_MODE to "sometimes", ConnectionSettings.KEY_IDLE_MINUTES to 45L),
        )

        val settings = ConnectionSettings(store)

        assertEquals(ConnectionMode.OnDemand, settings.mode.value)
        assertEquals(IdleWindow.Minutes30, settings.idleWindow.value)
    }

    @Test
    fun `user disconnect leaves always connected but keeps on demand`() {
        val always = ConnectionSettings(InMemoryKeyValueStore()).apply { setMode(ConnectionMode.Always) }
        always.onUserDisconnect()
        assertEquals(ConnectionMode.OnDemand, always.mode.value)

        val onDemand = ConnectionSettings(InMemoryKeyValueStore()).apply { setIdleWindow(IdleWindow.Minutes15) }
        onDemand.onUserDisconnect()
        assertEquals(ConnectionMode.OnDemand, onDemand.mode.value)
        assertEquals(IdleWindow.Minutes15, onDemand.idleWindow.value)
    }
}
