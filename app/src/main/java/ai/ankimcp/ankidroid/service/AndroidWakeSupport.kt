package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.appGraph
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import ai.ankimcp.ankidroid.tunnel.WakeLocks
import ai.ankimcp.ankidroid.tunnel.WakeupScheduler
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** `SystemClock.elapsedRealtime()`: keeps counting while the CPU is suspended. */
object AndroidElapsedClock : ElapsedClock {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
}

/** Non-reference-counted `PARTIAL_WAKE_LOCK`s, one per acquisition, always with a timeout. */
class AndroidWakeLocks(context: Context) : WakeLocks {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)

    override fun acquire(tag: String, timeoutMs: Long): AutoCloseable {
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ankimcp:$tag").apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true) && wakeLock.isHeld) wakeLock.release()
        }
    }
}

/**
 * Inexact `setAndAllowWhileIdle` alarm on the elapsed-realtime clock. Fires in Doze, but the
 * system limits these to about once a minute normally and about every 15 minutes in deep
 * Doze. Deliberately not an exact alarm: `SCHEDULE_EXACT_ALARM` is denied by default on
 * Android 14+ and `USE_EXACT_ALARM` is restricted on Google Play to alarm/calendar apps.
 */
class AlarmWakeupScheduler(context: Context) : WakeupScheduler {
    private val appContext = context.applicationContext
    private val alarms = appContext.getSystemService(AlarmManager::class.java)

    private val operation: PendingIntent = PendingIntent.getBroadcast(
        appContext,
        0,
        Intent(appContext, TunnelAlarmReceiver::class.java).setAction(TunnelAlarmReceiver.ACTION_WAKEUP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun schedule(delayMs: Long) {
        alarms.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            operation,
        )
    }

    override fun cancel() {
        alarms.cancel(operation)
    }
}

/** Delivers [AlarmWakeupScheduler] alarms to the tunnel. */
class TunnelAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKEUP) return
        // Runs under the alarm's own wake lock; onWakeup takes a short one of its own before returning.
        context.appGraph.tunnel.onWakeup()
    }

    companion object {
        const val ACTION_WAKEUP = "ai.ankimcp.ankidroid.action.TUNNEL_WAKEUP"
    }
}
