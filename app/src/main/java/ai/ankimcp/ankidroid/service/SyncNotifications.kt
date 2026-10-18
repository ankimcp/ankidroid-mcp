package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.anki.AnkiDroidAvailability
import ai.ankimcp.ankidroid.anki.AnkiDroidSync
import ai.ankimcp.ankidroid.anki.SyncRequest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Posts "Tap to sync AnkiDroid"; the tap sends AnkiDroid's `com.ichi2.anki.DO_SYNC` intent, which
 * its `IntentHandler` activity (exported, category DEFAULT) turns into a normal sync, at most one
 * every 2 minutes (AnkiDroid's `INTENT_SYNC_MIN_INTERVAL`, v2.25.3) (D58).
 */
class NotificationSyncRequester(context: Context, private val permission: NotificationPermissionGate) : AnkiDroidSync {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    override fun requestSync(): SyncRequest {
        val intent = Intent(DO_SYNC_ACTION)
            .setPackage(AnkiDroidAvailability.PACKAGE_NAME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resolves(intent).not()) return SyncRequest.AnkiDroidMissing
        permission.blockReason("\"Tap to sync AnkiDroid\" notification", CHANNEL_ID)?.let {
            return SyncRequest.NotificationBlocked(it)
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.sync_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = appContext.getString(R.string.sync_channel_description) },
        )
        val sync = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentTitle(appContext.getString(R.string.sync_request_title))
            .setContentText(appContext.getString(R.string.sync_request_text))
            .setContentIntent(sync)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return SyncRequest.NotificationPosted
    }

    private fun resolves(intent: Intent): Boolean {
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0)
        }
        return info != null
    }

    companion object {
        const val DO_SYNC_ACTION = "com.ichi2.anki.DO_SYNC"
        private const val CHANNEL_ID = NotificationChannels.SYNC
        private const val NOTIFICATION_ID = 3
    }
}
