package ai.ankimcp.ankidroid.tunnel

/**
 * Milliseconds since an arbitrary origin. On Android this is `SystemClock.elapsedRealtime()`,
 * which keeps counting while the CPU is suspended (unlike `System.nanoTime()`), so liveness
 * checks see real elapsed time after the device slept.
 */
fun interface ElapsedClock {
    fun elapsedMs(): Long

    companion object {
        /** JVM monotonic time. Stops while an Android device is suspended: JVM tests only. */
        val Monotonic = ElapsedClock { System.nanoTime() / 1_000_000 }
    }
}

/**
 * Partial wake locks. Every acquisition carries its own timeout so a forgotten release cannot
 * keep the CPU awake; `close()` releases early and is idempotent.
 */
fun interface WakeLocks {
    fun acquire(tag: String, timeoutMs: Long): AutoCloseable

    companion object {
        val None = WakeLocks { _, _ -> AutoCloseable {} }
    }
}

/**
 * Wakes the device after a delay, even in Doze, and then calls [TunnelConnection.onWakeup].
 * Only one wake-up is pending at a time; [schedule] replaces it.
 */
interface WakeupScheduler {
    fun schedule(delayMs: Long)

    fun cancel()

    companion object {
        val None = object : WakeupScheduler {
            override fun schedule(delayMs: Long) = Unit

            override fun cancel() = Unit
        }
    }
}
