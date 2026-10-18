package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.appGraph
import ai.ankimcp.ankidroid.local.LocalServerState
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the tunnel runs or the local Wi-Fi server is on, and shows
 * their status. Both live in [ai.ankimcp.ankidroid.AppGraph]; this service starts/stops them
 * and mirrors their state. It stops itself whenever the tunnel stops (including after an
 * on-demand idle timeout) unless the local server is on, and when the local server is turned
 * off while no tunnel runs. The local server runs exactly while this service does.
 */
class TunnelForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var localJob: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = appGraph
        lastStartId = startId
        if (intent?.action == ACTION_STOP) {
            graph.tunnel.stop()
            if (!graph.localSettings.enabled.value || stateJob == null) shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_LOCAL) {
            graph.localSettings.setEnabled(false)
            graph.localServer.stop()
            if (serviceShouldStopAfterLocalServer(graph.tunnel.state.value) || stateJob == null) shutdown()
            return START_NOT_STICKY
        }

        ensureChannel()
        // Without the permission (Android 13+) the service still runs, with a hidden notification.
        graph.notificationPermission.allows("tunnel status notification", CHANNEL_ID)
        startInForeground(buildNotification(graph.tunnel.state.value, graph.localServer.state.value))

        graph.networkMonitor.start { changed -> graph.tunnel.onNetworkAvailable(changed) }
        graph.screenMonitor.start(onScreenOff = graph.tunnel::onScreenOff, onScreenOn = graph.tunnel::onScreenOn)
        val readExtra = { key: String, default: Boolean -> intent?.getBooleanExtra(key, default) ?: default }
        startTunnelForServiceStart(readExtra = readExtra, startTunnel = { graph.tunnel.start() })
        graph.localServer.start()

        if (stateJob == null) {
            stateJob = scope.launch {
                combine(graph.tunnel.state, graph.localServer.state) { tunnel, local -> tunnel to local }
                    .collect { (state, local) ->
                        val localEnabled = graph.localSettings.enabled.value
                        if (state is TunnelState.Stopped && !localEnabled) {
                            if (serviceShouldStop(state, graph.tunnel.state.value, localEnabled)) shutdown()
                        } else {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, buildNotification(state, local))
                        }
                    }
            }
        }
        if (localJob == null) {
            localJob = scope.launch {
                var previous: Boolean? = null
                graph.localSettings.enabled.collect { enabled ->
                    when (localSwitchAction(previous, enabled)) {
                        LocalSwitchAction.Start -> graph.localServer.start()
                        LocalSwitchAction.Stop -> {
                            // Also after a failed start (token), whose run already ended: stop() is idempotent.
                            graph.localServer.stop()
                            if (serviceShouldStopAfterLocalServer(graph.tunnel.state.value)) shutdown()
                        }
                        LocalSwitchAction.None -> Unit
                    }
                    previous = enabled
                }
            }
        }
        // An earlier shutdown may have been refused for this start, and if this start began no
        // run (e.g. a wake-up whose run already ended), Stopped is not emitted again. A start for
        // the local server whose switch was turned off meanwhile ends here too.
        val localEnabled = graph.localSettings.enabled.value
        if (!localEnabled && graph.tunnel.state.value is TunnelState.Stopped) shutdown()
        if (!localEnabled && readExtra(EXTRA_LOCAL_SERVER_ONLY, false) && graph.tunnel.state.value is TunnelState.Idle) shutdown()
        // Not sticky: after a process kill we only come back via the app or a push wake-up.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        appGraph.localServer.stop()
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

    private fun buildNotification(state: TunnelState, local: LocalServerState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val lines = notificationLines(state, local)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(lines.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val tunnelRunning = state !is TunnelState.Idle && state !is TunnelState.Stopped
        if (tunnelRunning || local == LocalServerState.Off) {
            val disconnect = PendingIntent.getService(
                this,
                1,
                Intent(this, TunnelForegroundService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, getString(R.string.notification_action_disconnect), disconnect)
        }
        if (local != LocalServerState.Off) {
            val stopLocal = PendingIntent.getService(
                this,
                2,
                Intent(this, TunnelForegroundService::class.java).setAction(ACTION_STOP_LOCAL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, getString(R.string.notification_action_stop_local), stopLocal)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = NotificationChannels.TUNNEL
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "ai.ankimcp.ankidroid.action.START"
        private const val ACTION_STOP = "ai.ankimcp.ankidroid.action.STOP"
        private const val ACTION_STOP_LOCAL = "ai.ankimcp.ankidroid.action.STOP_LOCAL"

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

        /**
         * Starts the service for the local Wi-Fi server only (the tunnel is left alone). Returns
         * false if Android refused the start; the local server's state then says so.
         */
        fun startLocalServer(context: Context): Boolean {
            val graph = context.appGraph
            return startForegroundServiceSafely(graph.log, graph.localServer::reportStartBlocked) {
                val intent = Intent(context, TunnelForegroundService::class.java).setAction(ACTION_START)
                serviceStartExtras(callerStartsTunnel = false, localServerOnly = true)
                    .forEach { (key, value) -> intent.putExtra(key, value) }
                ContextCompat.startForegroundService(context, intent)
            }
        }

        /**
         * User-initiated disconnect from the app: ends this session; the next wake-up connects
         * again. The service keeps running while the local server is on.
         */
        fun stop(context: Context) {
            val graph = context.appGraph
            graph.tunnel.stop()
            if (!graph.localSettings.enabled.value) {
                context.stopService(Intent(context, TunnelForegroundService::class.java))
            }
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
