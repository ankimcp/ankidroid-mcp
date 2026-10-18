package ai.ankimcp.ankidroid.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class BindAddressTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    private fun wifi(vararg addresses: String) =
        LinkCandidate(wifi = true, cellular = false, vpn = false, addresses = addresses.map(::ip))

    private fun usable(host: String) = WifiAddress.Usable(host)

    @Test
    fun `picks the Wi-Fi IPv4 address, skipping IPv6 and link-local`() {
        val link = wifi("fe80::1", "2001:db8::5", "169.254.10.1", "192.168.1.20")

        assertEquals(usable("192.168.1.20"), selectBindAddress(listOf(link)))
    }

    @Test
    fun `never binds on mobile data or a VPN`() {
        val cellular = LinkCandidate(wifi = false, cellular = true, vpn = false, addresses = listOf(ip("10.64.0.2")))
        val vpn = LinkCandidate(wifi = true, cellular = false, vpn = true, addresses = listOf(ip("100.101.102.103")))

        assertEquals(WifiAddress.None, selectBindAddress(listOf(cellular, vpn)))
        assertEquals(usable("192.168.1.20"), selectBindAddress(listOf(cellular, vpn, wifi("192.168.1.20"))))
    }

    @Test
    fun `no Wi-Fi, no address and never a wildcard or loopback`() {
        assertEquals(WifiAddress.None, selectBindAddress(emptyList()))
        assertEquals(WifiAddress.None, selectBindAddress(listOf(wifi())))
        assertEquals(WifiAddress.None, selectBindAddress(listOf(wifi("0.0.0.0", "127.0.0.1", "fe80::1"))))
    }

    @Test
    fun `accepts the three private ranges and carrier-grade NAT, private first`() {
        for (host in listOf("10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.0.1", "100.64.0.1", "100.127.255.254")) {
            assertEquals(host, usable(host), selectBindAddress(listOf(wifi(host))))
        }
        assertEquals(usable("10.0.0.7"), selectBindAddress(listOf(wifi("100.70.1.2"), wifi("10.0.0.7"))))
    }

    @Test
    fun `a Wi-Fi with only public addresses is PublicOnly and never bound`() {
        for (host in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "100.63.255.255", "192.169.1.1")) {
            assertEquals(host, WifiAddress.PublicOnly, selectBindAddress(listOf(wifi(host))))
        }
        assertEquals(usable("192.168.1.20"), selectBindAddress(listOf(wifi("203.0.113.5", "192.168.1.20"))))
    }
}
