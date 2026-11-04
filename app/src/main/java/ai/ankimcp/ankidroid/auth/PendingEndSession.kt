package ai.ankimcp.ankidroid.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * The Keycloak sign-out URL waiting for MainActivity to open it (D64). Process-wide, so it
 * survives the activity finishing while sign-out runs; in memory only, since it holds the
 * id_token: never persist or log it.
 */
class PendingEndSession {
    private val _url = MutableStateFlow<String?>(null)

    /** Non-null while a URL waits; observe it, then [take] to claim it. */
    val url: StateFlow<String?> = _url.asStateFlow()

    fun set(url: String) {
        _url.value = url
    }

    /** Returns the waiting URL and clears it, so only one caller ever gets it. */
    fun take(): String? = _url.getAndUpdate { null }
}
