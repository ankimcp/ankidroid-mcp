package ai.ankimcp.ankidroid.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientConfigTest {
    private val url = ClientConfig.url("192.168.1.20", 8765)
    private val token = "abc_DEF-123"

    @Test
    fun `url is plain http to the Wi-Fi address and the MCP path`() {
        assertEquals("http://192.168.1.20:8765/mcp", url)
        assertEquals(url, LocalServerState.Running("192.168.1.20", 8765).url)
    }

    @Test
    fun `share text carries the URL, and the token only when required`() {
        assertEquals("AnkiMCP on my Wi-Fi\nURL: $url\nToken: $token", ClientConfig.shareText(url, token))
        assertEquals("AnkiMCP on my Wi-Fi\nURL: $url", ClientConfig.shareText(url, null))
    }
}
