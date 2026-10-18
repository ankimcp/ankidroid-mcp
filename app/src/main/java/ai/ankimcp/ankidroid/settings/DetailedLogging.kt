package ai.ankimcp.ankidroid.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The Log tab's "Detailed logging" switch (D61): off by default, and off again
 * [DURATION_MS] after it was turned on. Stored as the wall-clock time it ends
 * (`detailed_logging_until` in the "settings" file), so the 24 h survive process death; the
 * end is checked when this is created (app start), on every [isEnabled] call and by
 * [launchAutoOff]. A clock set back so far that the end is more than [DURATION_MS] away also
 * ends it.
 */
class DetailedLogging(
    private val store: KeyValueStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Called once when the 24 h run out (not when the user turns it off). */
    private val onExpired: () -> Unit = {},
) {
    private val _until = MutableStateFlow(store.getLong(KEY_UNTIL))

    /** Wall-clock end of the current period, or null when off. */
    val until: StateFlow<Long?> = _until.asStateFlow()

    private val _enabled = MutableStateFlow(_until.value != null)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** The period had already ended when this was created; [onExpired] is not called for that. */
    val expiredAtStart: Boolean = expire(notify = false)

    /** Under the same lock as the expiry, so a toggle and an expiry at the same moment cannot interleave. */
    fun setEnabled(on: Boolean) {
        synchronized(this) {
            val until = if (on) nowMs() + DURATION_MS else null
            store.edit(mapOf(KEY_UNTIL to until))
            _until.value = until
            _enabled.value = on
        }
    }

    /** Whether it is on right now; turns it off first if the period has ended. Cheap. */
    fun isEnabled(): Boolean {
        checkExpiry()
        return _enabled.value
    }

    /** Turns it off when the period has ended; true if it did so just now. */
    fun checkExpiry(): Boolean = expire(notify = true)

    private fun expire(notify: Boolean): Boolean {
        val until = _until.value ?: return false
        val now = nowMs()
        if (now < until && until - now <= DURATION_MS) return false
        synchronized(this) {
            if (_until.value != until) return false
            store.edit(mapOf(KEY_UNTIL to null))
            _until.value = null
            _enabled.value = false
        }
        if (notify) onExpired()
        return true
    }

    /**
     * Turns it off when the period ends while the process lives. Re-checks the wall clock at
     * least every [CHECK_INTERVAL_MS], since a coroutine delay does not count deep sleep.
     */
    fun launchAutoOff(scope: CoroutineScope): Job = scope.launch {
        until.collectLatest { end ->
            if (end == null) return@collectLatest
            while (!checkExpiry()) {
                delay((end - nowMs()).coerceIn(1L, CHECK_INTERVAL_MS))
            }
        }
    }

    companion object {
        const val DURATION_MS = 24 * 60 * 60 * 1000L
        const val CHECK_INTERVAL_MS = 10 * 60 * 1000L
        internal const val KEY_UNTIL = "detailed_logging_until"
    }
}
