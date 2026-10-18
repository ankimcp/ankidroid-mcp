package ai.ankimcp.ankidroid.local

import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.InetAddress

/** What the phone's Wi-Fi offers the local server. */
sealed interface WifiAddress {
    /** Not on Wi-Fi (or no IPv4 address yet). */
    data object None : WifiAddress

    /** On Wi-Fi, but only with public IPv4 addresses: binding would expose the server beyond the LAN. */
    data object PublicOnly : WifiAddress

    data class Usable(val host: String) : WifiAddress
}

/** The phone's IPv4 address on Wi-Fi, as [WifiAddress]. */
interface WifiAddressSource {
    val address: StateFlow<WifiAddress>

    fun start()

    fun stop()
}

/** What the Wi-Fi monitor knows about one network: its transports and link addresses. */
data class LinkCandidate(
    val wifi: Boolean,
    val cellular: Boolean,
    val vpn: Boolean,
    val addresses: List<InetAddress>,
)

/**
 * The address to bind to: an IPv4 address of a Wi-Fi network that is not also cellular or a
 * VPN, and only a private (10/8, 172.16/12, 192.168/16) or carrier-grade NAT (100.64/10) one,
 * so the server is never reachable over mobile data, a VPN or the internet. Private wins over
 * CGNAT; among equals the first network wins. A Wi-Fi with IPv4 addresses that are all public
 * is [WifiAddress.PublicOnly], and the server stays off.
 */
fun selectBindAddress(candidates: List<LinkCandidate>): WifiAddress {
    val ipv4 = candidates.asSequence()
        .filter { it.wifi && !it.cellular && !it.vpn }
        .flatMap { it.addresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }
        .toList()
    val best = ipv4.firstOrNull { it.isSiteLocalAddress } ?: ipv4.firstOrNull(::isCarrierGradeNat)
    return when {
        best != null -> WifiAddress.Usable(best.hostAddress!!)
        ipv4.isNotEmpty() -> WifiAddress.PublicOnly
        else -> WifiAddress.None
    }
}

/** 100.64.0.0/10 (RFC 6598). `isSiteLocalAddress` already covers the three RFC 1918 ranges. */
private fun isCarrierGradeNat(address: Inet4Address): Boolean {
    val bytes = address.address
    return (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64
}
