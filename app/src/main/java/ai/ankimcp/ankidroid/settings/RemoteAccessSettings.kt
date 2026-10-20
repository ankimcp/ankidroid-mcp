package ai.ankimcp.ankidroid.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "Remote access" switch (D63): off stops the tunnel, unregisters push wake-ups and ignores
 * any that still arrive, so Disconnect is a real off. On by default (an absent key counts as
 * on); kept in the same "settings" SharedPreferences file as [ConnectionSettings], so it
 * survives sign-out and sign-in.
 */
class RemoteAccessSettings(private val store: KeyValueStore) {
    private val _enabled = MutableStateFlow(store.getBoolean(KEY_ENABLED) ?: true)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val isEnabled: Boolean get() = _enabled.value

    fun setEnabled(enabled: Boolean) {
        store.edit(mapOf(KEY_ENABLED to enabled))
        _enabled.value = enabled
    }

    companion object {
        internal const val KEY_ENABLED = "remote_enabled"
    }
}
