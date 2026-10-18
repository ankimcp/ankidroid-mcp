package ai.ankimcp.ankidroid.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The local Wi-Fi server's switch, port and "Require access token". Kept in the same "settings"
 * SharedPreferences file as [ConnectionSettings].
 *
 * Earlier builds stored `sign_in_skipped` for "Use on my Wi-Fi only"; the Status screen now
 * shows both tabs signed out too (D59), so the key is removed on first read.
 */
class LocalServerSettings(private val store: KeyValueStore) {
    init {
        if (store.contains(LEGACY_KEY_SIGN_IN_SKIPPED)) store.edit(mapOf(LEGACY_KEY_SIGN_IN_SKIPPED to null))
    }

    private val _enabled = MutableStateFlow(store.getBoolean(KEY_ENABLED) ?: false)
    private val _port = MutableStateFlow(store.getLong(KEY_PORT)?.toInt()?.takeIf(::isValidPort) ?: DEFAULT_PORT)
    private val _requireToken = MutableStateFlow(store.getBoolean(KEY_REQUIRE_TOKEN) ?: false)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val port: StateFlow<Int> = _port.asStateFlow()

    /** "Require access token"; off by default, the server is then open to the LAN (D55). */
    val requireToken: StateFlow<Boolean> = _requireToken.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        store.edit(mapOf(KEY_ENABLED to enabled))
        _enabled.value = enabled
    }

    /** Returns false (and changes nothing) for a port outside [MIN_PORT]..[MAX_PORT]. */
    fun setPort(port: Int): Boolean {
        if (!isValidPort(port)) return false
        store.edit(mapOf(KEY_PORT to port.toLong()))
        _port.value = port
        return true
    }

    fun setRequireToken(required: Boolean) {
        store.edit(mapOf(KEY_REQUIRE_TOKEN to required))
        _requireToken.value = required
    }

    companion object {
        /** Same as AnkiConnect Android, so existing instructions transfer. */
        const val DEFAULT_PORT = 8765

        /** Unprivileged ports only: an app cannot bind below 1024. */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        internal const val KEY_ENABLED = "local_server_enabled"
        internal const val KEY_PORT = "local_server_port"
        internal const val KEY_REQUIRE_TOKEN = "local_server_require_token"
        internal const val LEGACY_KEY_SIGN_IN_SKIPPED = "sign_in_skipped"

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
    }
}
