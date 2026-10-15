package ai.ankimcp.ankidroid.push

import ai.ankimcp.ankidroid.appGraph
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FCM entry point. Both callbacks run on Firebase's background thread; neither blocks:
 * registration runs in the app scope, and a wake-up only starts the foreground service and
 * the tunnel (allowed from a high-priority message on Android 12+).
 */
class AnkiMcpMessagingService : FirebaseMessagingService() {
    // Deprecated with the token API since firebase-messaging 25.1.0; see docs/DECISIONS.md D38.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        val graph = appGraph
        graph.appScope.launch(Dispatchers.IO) { graph.push.onNewToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val graph = appGraph
        val data = message.data
        if (data[WakeHandler.KEY_TYPE] == WakeHandler.TYPE_WAKE && message.priority != RemoteMessage.PRIORITY_HIGH) {
            // FCM downgrades high-priority messages for apps that rarely show anything; Android
            // may then refuse the foreground-service start (logged by startForegroundServiceSafely).
            graph.log.warn("Wake-up arrived at normal priority (sent as ${message.originalPriority})")
        }
        graph.wakeHandler.onMessage(data)
    }
}
