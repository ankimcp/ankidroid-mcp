package ai.ankimcp.ankidroid.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the tunnel is kept up. */
enum class ConnectionMode(val key: String) {
    /**
     * Connect when a push wake-up arrives (or the user taps Connect) and disconnect after
     * [IdleWindow] without a request.
     */
    OnDemand("on_demand"),

    /** Former "keep connected": auto-connect on launch and boot, retry forever, no idle timer. */
    Always("always"),
    ;

    companion object {
        fun fromKey(key: String?): ConnectionMode? = entries.firstOrNull { it.key == key }
    }
}

/** On-demand sessions end after this long without a forwarded request. */
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
 * Connection mode and idle window, persisted in the "settings" SharedPreferences file.
 *
 * Replaces the phase-1 `keep_connected` boolean: on first read, `true` becomes
 * [ConnectionMode.Always] and `false` (or absent) [ConnectionMode.OnDemand], and the old key is
 * removed.
 */
class ConnectionSettings(private val store: KeyValueStore) {
    private val _mode: MutableStateFlow<ConnectionMode>
    private val _idleWindow: MutableStateFlow<IdleWindow>

    init {
        migrate()
        _mode = MutableStateFlow(ConnectionMode.fromKey(store.getString(KEY_MODE)) ?: ConnectionMode.OnDemand)
        _idleWindow = MutableStateFlow(IdleWindow.fromMinutes(store.getLong(KEY_IDLE_MINUTES)) ?: IdleWindow.DEFAULT)
    }

    val mode: StateFlow<ConnectionMode> = _mode.asStateFlow()
    val idleWindow: StateFlow<IdleWindow> = _idleWindow.asStateFlow()

    val alwaysConnected: Boolean get() = _mode.value == ConnectionMode.Always

    fun setMode(mode: ConnectionMode) {
        store.edit(mapOf(KEY_MODE to mode.key))
        _mode.value = mode
    }

    fun setIdleWindow(window: IdleWindow) {
        store.edit(mapOf(KEY_IDLE_MINUTES to window.minutes.toLong()))
        _idleWindow.value = window
    }

    /**
     * An explicit Disconnect must not be undone by the next launch or boot, so it leaves
     * [ConnectionMode.Always] (exactly what turning off "keep connected" did before).
     */
    fun onUserDisconnect() {
        if (alwaysConnected) setMode(ConnectionMode.OnDemand)
    }

    private fun migrate() {
        if (store.contains(KEY_MODE)) return
        val legacy = store.getBoolean(LEGACY_KEY_KEEP_CONNECTED)
        val mode = if (legacy == true) ConnectionMode.Always else ConnectionMode.OnDemand
        store.edit(mapOf(KEY_MODE to mode.key, LEGACY_KEY_KEEP_CONNECTED to null))
    }

    companion object {
        const val PREFS_NAME = "settings"
        internal const val KEY_MODE = "connection_mode"
        internal const val KEY_IDLE_MINUTES = "idle_window_minutes"
        internal const val LEGACY_KEY_KEEP_CONNECTED = "keep_connected"
    }
}
