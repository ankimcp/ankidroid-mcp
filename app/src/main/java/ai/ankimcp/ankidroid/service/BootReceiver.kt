package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.appGraph
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Reconnects after boot or an app update, but only in "Always connected" mode. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext
        val graph = appContext.appGraph
        val pending = goAsync()
        startInBackground(
            scope = graph.appScope,
            io = Dispatchers.IO,
            // Reading credentials may decrypt with the Keystore: not on the main thread.
            shouldStart = { graph.settings.alwaysConnected && graph.credentialStore.load() != null },
            start = { TunnelForegroundService.start(appContext) },
            finish = pending::finish,
        )
    }

    companion object {
        private const val TAG = "BootReceiver"

        /** Checks [shouldStart] and runs [start] on [io]; always calls [finish] (the pending broadcast). */
        internal fun startInBackground(
            scope: CoroutineScope,
            io: CoroutineDispatcher,
            shouldStart: () -> Boolean,
            start: () -> Unit,
            finish: () -> Unit,
        ): Job = scope.launch(io) {
            try {
                if (!shouldStart()) return@launch
                try {
                    start()
                } catch (e: Exception) {
                    // ForegroundServiceStartNotAllowedException and friends: the user can still connect from the app.
                    Log.w(TAG, "Could not start tunnel service: ${e.javaClass.simpleName}")
                }
            } finally {
                finish()
            }
        }
    }
}
