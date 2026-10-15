package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.log.TunnelLog
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** The app's notification channels. */
object NotificationChannels {
    /** The foreground service's status notification. */
    const val TUNNEL = "tunnel"

    /** "Sign in again to AnkiMCP". */
    const val ACCOUNT = "account"

    val ALL = listOf(TUNNEL, ACCOUNT)
}

/** POST_NOTIFICATIONS is a runtime permission from Android 13; before that it is always granted. */
fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Why a notification on [channelImportance]'s channel would not be seen, or null if it would.
 * [channelImportance] is null while the channel has not been created yet (it is created with a
 * visible importance on first use).
 */
internal fun notificationBlockReason(permissionGranted: Boolean, appEnabled: Boolean, channelImportance: Int?): String? =
    when {
        !permissionGranted -> "Notification permission is off"
        !appEnabled -> "Notifications are off for this app"
        channelImportance == NotificationManagerCompat.IMPORTANCE_NONE -> "This notification category is off"
        else -> null
    }

/** Why a notification on [channelId] would not be seen, or null if it would. */
fun notificationBlockReason(context: Context, channelId: String): String? {
    val manager = NotificationManagerCompat.from(context)
    return notificationBlockReason(
        permissionGranted = hasNotificationPermission(context),
        appEnabled = manager.areNotificationsEnabled(),
        channelImportance = manager.getNotificationChannel(channelId)?.importance,
    )
}

/** False if any of the app's notifications would be hidden (permission, app switch or a channel turned off). */
internal fun allNotificationsShown(blockReason: (channelId: String) -> String?): Boolean =
    NotificationChannels.ALL.all { blockReason(it) == null }

fun allNotificationsShown(context: Context): Boolean = allNotificationsShown { notificationBlockReason(context, it) }

/**
 * Checks before a notification is posted whether it can be seen (permission, the app's
 * notification switch, its channel) and leaves a tunnel-log line when not, so a silently dropped
 * notification shows up in the app's log.
 */
class NotificationPermissionGate(private val log: TunnelLog, private val blockReason: (channelId: String) -> String?) {
    /** True if [what], posted on [channelId], can be seen; otherwise logs why not and returns false. */
    fun allows(what: String, channelId: String): Boolean {
        val reason = blockReason(channelId) ?: return true
        log.warn("$reason: $what not shown")
        return false
    }
}
