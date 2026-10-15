package ai.ankimcp.ankidroid.config

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Non-secret user preferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _keepConnected = MutableStateFlow(prefs.getBoolean(KEY_KEEP_CONNECTED, false))

    /**
     * When true the tunnel is reconnected automatically on app launch and after boot.
     * Defaults to false so the first launch never connects on its own.
     */
    val keepConnected: StateFlow<Boolean> = _keepConnected.asStateFlow()

    fun setKeepConnected(value: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_CONNECTED, value).apply()
        _keepConnected.value = value
    }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_KEEP_CONNECTED = "keep_connected"
    }
}
