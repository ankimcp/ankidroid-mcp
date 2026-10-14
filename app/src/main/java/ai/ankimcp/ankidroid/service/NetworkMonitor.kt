package ai.ankimcp.ankidroid.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the default network. It counts as available only once Android has validated it
 * (`NET_CAPABILITY_VALIDATED`: internet access actually works, no captive portal). [available]
 * gates reconnect attempts; [start]'s callback fires when a validated default network appears,
 * with `changed = true` if it differs from the last validated one (a live socket is then bound
 * to a network that is gone and should be replaced).
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _available = MutableStateFlow(isCurrentlyOnline())
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start(onAvailable: (changed: Boolean) -> Unit) {
        if (callback != null) return
        _available.value = isCurrentlyOnline()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            // Kept across onLost: on a wifi -> cell handover the old network is often lost
            // before the new one validates, and the socket on it still has to be replaced.
            private var lastValidated: Network? = null

            override fun onAvailable(network: Network) {
                // Not usable yet: wait for onCapabilitiesChanged (always delivered right after on API 26+).
                current = network
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network != current) return
                val validated = caps.isValidatedInternet()
                val wasAvailable = _available.value
                _available.value = validated
                if (validated && (!wasAvailable || lastValidated != network)) {
                    val previous = lastValidated
                    lastValidated = network
                    onAvailable(previous != null && previous != network)
                }
            }

            override fun onLost(network: Network) {
                if (network == current) current = null
                _available.value = isCurrentlyOnline()
            }
        }
        connectivity.registerDefaultNetworkCallback(cb)
        callback = cb
    }

    @Synchronized
    fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun isCurrentlyOnline(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.isValidatedInternet() == true
    }

    private fun NetworkCapabilities.isValidatedInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
