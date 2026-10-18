package ai.ankimcp.ankidroid.service

import ai.ankimcp.ankidroid.local.LinkCandidate
import ai.ankimcp.ankidroid.local.WifiAddress
import ai.ankimcp.ankidroid.local.WifiAddressSource
import ai.ankimcp.ankidroid.local.selectBindAddress
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Follows the Wi-Fi networks' IPv4 addresses for the local server. Listens with a
 * `TRANSPORT_WIFI` request (not the default network: with a VPN up, or while Android prefers
 * mobile data, the Wi-Fi network is still there) without the INTERNET capability, so a Wi-Fi
 * with no internet access still counts. The request keeps its default NOT_VPN capability.
 */
class WifiMonitor(context: Context) : WifiAddressSource {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _address = MutableStateFlow<WifiAddress>(WifiAddress.None)
    override val address: StateFlow<WifiAddress> = _address.asStateFlow()

    private val capabilities = LinkedHashMap<Network, NetworkCapabilities>()
    private val links = LinkedHashMap<Network, LinkProperties>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    override fun start() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                update { capabilities[network] = caps }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                update { links[network] = linkProperties }
            }

            override fun onLost(network: Network) {
                update {
                    capabilities.remove(network)
                    links.remove(network)
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, cb)
        callback = cb
    }

    @Synchronized
    override fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        capabilities.clear()
        links.clear()
        _address.value = WifiAddress.None
    }

    @Synchronized
    private fun update(change: () -> Unit) {
        if (callback == null) return
        change()
        _address.value = selectBindAddress(
            links.mapNotNull { (network, link) ->
                val caps = capabilities[network] ?: return@mapNotNull null
                LinkCandidate(
                    wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    addresses = link.linkAddresses.map { it.address },
                )
            },
        )
    }
}
