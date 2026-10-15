package ai.ankimcp.ankidroid.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers that the phone froze the app while a tunnel ran, so the Status screen can point at
 * the battery settings. Persisted in the "settings" file. Cleared when the user dismisses it,
 * when battery optimisation is turned off while the app is open, or once the app is exempt and
 * no freeze has been seen for [STALE_AFTER_MS] (covers an exemption granted while the app was
 * not running).
 */
class FreezeNotice(
    private val store: KeyValueStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _detected = MutableStateFlow(store.getBoolean(KEY_FREEZE_DETECTED) == true)
    val detected: StateFlow<Boolean> = _detected.asStateFlow()

    fun markDetected() {
        store.edit(mapOf(KEY_FREEZE_DETECTED to true, KEY_FREEZE_DETECTED_AT to nowMs()))
        _detected.value = true
    }

    fun dismiss() {
        if (!_detected.value) return
        store.edit(mapOf(KEY_FREEZE_DETECTED to null, KEY_FREEZE_DETECTED_AT to null))
        _detected.value = false
    }

    /**
     * Called with the battery exemption whenever the app reads it. Clears the notice when the
     * exemption was just granted (the user acted on it), or when it is granted and the last
     * freeze is older than [STALE_AFTER_MS].
     */
    fun onBatteryExemption(wasExempt: Boolean, isExempt: Boolean) {
        if (!_detected.value || !isExempt) return
        val lastAt = store.getLong(KEY_FREEZE_DETECTED_AT) ?: 0L
        if (!wasExempt || nowMs() - lastAt >= STALE_AFTER_MS) dismiss()
    }

    companion object {
        internal const val KEY_FREEZE_DETECTED = "freeze_detected"
        internal const val KEY_FREEZE_DETECTED_AT = "freeze_detected_at"
        const val STALE_AFTER_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
