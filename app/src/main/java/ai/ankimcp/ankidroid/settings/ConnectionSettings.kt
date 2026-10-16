package ai.ankimcp.ankidroid.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sessions end after this long without a forwarded request. */
enum class IdleWindow(val minutes: Int) {
    Minutes15(15),
    Minutes30(30),
    Minutes60(60),
    ;

    val millis: Long get() = minutes * 60_000L

    companion object {
        val DEFAULT = Minutes30

        fun fromMinutes(minutes: Long?): IdleWindow? = entries.firstOrNull { it.minutes.toLong() == minutes }
    }
}

/**
 * The idle window, persisted in the "settings" SharedPreferences file.
 *
 * There is one connection mode, on demand (D50). Earlier builds stored a mode
 * (`connection_mode`, "always" or "on_demand") and before that a `keep_connected` boolean;
 * both keys are removed on first read, so an "Always connected" or "Keep connected" install
 * becomes on demand.
 */
class ConnectionSettings(private val store: KeyValueStore) {
    private val _idleWindow: MutableStateFlow<IdleWindow>

    init {
        migrate()
        _idleWindow = MutableStateFlow(IdleWindow.fromMinutes(store.getLong(KEY_IDLE_MINUTES)) ?: IdleWindow.DEFAULT)
    }

    val idleWindow: StateFlow<IdleWindow> = _idleWindow.asStateFlow()

    fun setIdleWindow(window: IdleWindow) {
        store.edit(mapOf(KEY_IDLE_MINUTES to window.minutes.toLong()))
        _idleWindow.value = window
    }

    private fun migrate() {
        if (!store.contains(LEGACY_KEY_MODE) && !store.contains(LEGACY_KEY_KEEP_CONNECTED)) return
        store.edit(mapOf(LEGACY_KEY_MODE to null, LEGACY_KEY_KEEP_CONNECTED to null))
    }

    companion object {
        const val PREFS_NAME = "settings"
        internal const val KEY_IDLE_MINUTES = "idle_window_minutes"
        internal const val LEGACY_KEY_MODE = "connection_mode"
        internal const val LEGACY_KEY_KEEP_CONNECTED = "keep_connected"
    }
}
