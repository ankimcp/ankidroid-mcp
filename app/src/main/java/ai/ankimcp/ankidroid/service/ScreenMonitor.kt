package ai.ankimcp.ankidroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Reports `ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON` while the tunnel service runs. These are
 * only delivered to context-registered receivers, so the service registers this in
 * `onStartCommand` and unregisters it in `onDestroy`, like [NetworkMonitor]. They are protected
 * broadcasts that only the system can send, so exporting the receiver lets no other app reach it.
 */
class ScreenMonitor(context: Context) {
    private val appContext = context.applicationContext
    private var receiver: BroadcastReceiver? = null

    @Synchronized
    fun start(onScreenOff: () -> Unit, onScreenOn: () -> Unit) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(appContext, r, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = r
    }

    @Synchronized
    fun stop() {
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
    }
}
