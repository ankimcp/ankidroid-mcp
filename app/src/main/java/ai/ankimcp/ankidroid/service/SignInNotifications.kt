package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.push.SignInNotifier
import ai.ankimcp.ankidroid.ui.MainActivity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** "Sign in again to AnkiMCP", on its own channel so it is not muted with the tunnel status. */
class AndroidSignInNotifier(context: Context, private val permission: NotificationPermissionGate) : SignInNotifier {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    override fun showSignInRequired() {
        if (!permission.allows("\"Sign in again\" notification", CHANNEL_ID)) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.account_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = appContext.getString(R.string.account_channel_description) },
        )
        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentTitle(appContext.getString(R.string.sign_in_required_title))
            .setContentText(appContext.getString(R.string.sign_in_required_text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun dismiss() {
        manager.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = NotificationChannels.ACCOUNT
        const val NOTIFICATION_ID = 2
    }
}
