package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.appGraph
import ai.ankimcp.ankidroid.tunnel.TunnelState
import ai.ankimcp.ankidroid.ui.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the tunnel runs and shows its status. The tunnel itself lives
 * in [ai.ankimcp.ankidroid.AppGraph]; this service only starts/stops it and mirrors its state.
 * It stops itself whenever the tunnel stops, including after an on-demand idle timeout.
 */
class TunnelForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = appGraph
        lastStartId = startId
        if (intent?.action == ACTION_STOP) {
            graph.tunnel.stop()
            shutdown()
            return START_NOT_STICKY
        }

        ensureChannel()
        // Without the permission (Android 13+) the service still runs, with a hidden notification.
        graph.notificationPermission.allows("tunnel status notification", CHANNEL_ID)
        startInForeground(buildNotification(graph.tunnel.state.value))

        graph.networkMonitor.start { changed -> graph.tunnel.onNetworkAvailable(changed) }
        graph.screenMonitor.start(onScreenOff = graph.tunnel::onScreenOff, onScreenOn = graph.tunnel::onScreenOn)
        startTunnelForServiceStart(
            readExtra = { key, default -> intent?.getBooleanExtra(key, default) ?: default },
            startTunnel = { graph.tunnel.start() },
        )

        if (stateJob == null) {
            stateJob = scope.launch {
                graph.tunnel.state.collect { state ->
                    if (state is TunnelState.Stopped) {
                        if (serviceShouldStop(state, graph.tunnel.state.value)) shutdown()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(state))
                    }
                }
            }
        }
        // An earlier shutdown may have been refused for this start, and if this start began no
        // run (e.g. a wake-up whose run already ended), Stopped is not emitted again.
        if (graph.tunnel.state.value is TunnelState.Stopped) shutdown()
        // Not sticky: after a process kill we only come back via the app or a push wake-up.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        appGraph.networkMonitor.stop()
        appGraph.screenMonitor.stop()
        scope.cancel()
        super.onDestroy()
    }

    /** Refused (and the notification kept) when a newer start, e.g. a wake-up's, is already queued. */
    private fun shutdown() {
        if (stopSelfResult(lastStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_description) }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: TunnelState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, TunnelForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(describe(state))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_action_disconnect), disconnect)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = NotificationChannels.TUNNEL
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "ai.ankimcp.ankidroid.action.START"
        private const val ACTION_STOP = "ai.ankimcp.ankidroid.action.STOP"

        /**
         * Starts the tunnel service. Returns false if Android refused the start (app in the
         * background); the tunnel state then says so (see [startForegroundServiceSafely]).
         * With [callerStartsTunnel] the service leaves starting the tunnel to the caller (see
         * [startTunnelUnlessCallerDid]).
         */
        fun start(context: Context, callerStartsTunnel: Boolean = false): Boolean {
            val graph = context.appGraph
            return startForegroundServiceSafely(graph.log, graph.tunnel::reportStartBlocked) {
                val intent = Intent(context, TunnelForegroundService::class.java).setAction(ACTION_START)
                serviceStartExtras(callerStartsTunnel).forEach { (key, value) -> intent.putExtra(key, value) }
                ContextCompat.startForegroundService(context, intent)
            }
        }

        /** User-initiated disconnect from the app: ends this session; the next wake-up connects again. */
        fun stop(context: Context) {
            val graph = context.appGraph
            graph.tunnel.stop()
            context.stopService(Intent(context, TunnelForegroundService::class.java))
        }

        fun describe(state: TunnelState): String = when (state) {
            TunnelState.Idle -> "Not connected"
            TunnelState.WaitingForNetwork -> "Waiting for network"
            is TunnelState.Connecting -> "Connecting…"
            is TunnelState.Connected -> "Connected: Claude can reach your decks"
            is TunnelState.Reconnecting -> "Reconnecting in ${(state.delayMs + 999) / 1000}s (attempt ${state.attempt})"
            is TunnelState.Stopped -> "Disconnected: ${state.reason}"
        }
    }
}
