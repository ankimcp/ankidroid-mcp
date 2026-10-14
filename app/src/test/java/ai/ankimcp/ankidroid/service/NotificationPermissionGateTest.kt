package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.log.LogEntry
import ai.ankimcp.ankidroid.log.TunnelLog
import androidx.core.app.NotificationManagerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionGateTest {
    private val log = TunnelLog(mirrorToLogcat = false)

    private fun gate(permission: Boolean = true, appEnabled: Boolean = true, importances: Map<String, Int> = emptyMap()) =
        NotificationPermissionGate(log) { channel -> notificationBlockReason(permission, appEnabled, importances[channel]) }

    @Test
    fun `a notification dropped for a missing permission is logged`() {
        assertFalse(gate(permission = false).allows("\"Sign in again\" notification", NotificationChannels.ACCOUNT))

        val entry = log.entries.value.single()
        assertEquals(LogEntry.Level.WARN, entry.level)
        assertEquals("Notification permission is off: \"Sign in again\" notification not shown", entry.message)
    }

    @Test
    fun `with everything on nothing is logged`() {
        val importances = mapOf(NotificationChannels.TUNNEL to NotificationManagerCompat.IMPORTANCE_LOW)

        assertTrue(gate(importances = importances).allows("tunnel status notification", NotificationChannels.TUNNEL))

        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun `notifications switched off for the app are logged and shown as off on the Status screen`() {
        // Permission granted, but areNotificationsEnabled() is false.
        val blockReason = { _: String -> notificationBlockReason(permissionGranted = true, appEnabled = false, channelImportance = null) }

        assertFalse(NotificationPermissionGate(log, blockReason).allows("tunnel status notification", NotificationChannels.TUNNEL))
        assertFalse("Status card shows \"Notifications are off\"", allNotificationsShown(blockReason))

        assertEquals("Notifications are off for this app: tunnel status notification not shown", log.entries.value.single().message)
    }

    @Test
    fun `a channel turned off hides only that channel, and the Status screen says notifications are off`() {
        val importances = mapOf(NotificationChannels.ACCOUNT to NotificationManagerCompat.IMPORTANCE_NONE)
        val gate = gate(importances = importances)

        assertTrue(gate.allows("tunnel status notification", NotificationChannels.TUNNEL))
        assertFalse(gate.allows("\"Sign in again\" notification", NotificationChannels.ACCOUNT))
        assertFalse(allNotificationsShown { notificationBlockReason(true, true, importances[it]) })

        assertEquals(
            listOf("This notification category is off: \"Sign in again\" notification not shown"),
            log.entries.value.map { it.message },
        )
    }

    @Test
    fun `a channel not created yet counts as shown`() {
        assertNull(notificationBlockReason(permissionGranted = true, appEnabled = true, channelImportance = null))
        assertTrue(allNotificationsShown { null })
    }
}
