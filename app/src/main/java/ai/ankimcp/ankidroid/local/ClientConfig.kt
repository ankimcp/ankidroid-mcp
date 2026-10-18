package ai.ankimcp.ankidroid.local

/**
 * Text the Local server card shows and shares. Client setup (Claude Code, Claude Desktop through
 * mcp-remote) is in the documentation, not on the card (D59).
 */
object ClientConfig {
    fun url(host: String, port: Int): String = "http://$host:$port${McpHttpAdapter.MCP_PATH}"

    /** What Share sends: the URL, and the token if one is required. */
    fun shareText(url: String, token: String?): String = buildString {
        appendLine("AnkiMCP on my Wi-Fi")
        append("URL: $url")
        if (token != null) append("\nToken: $token")
    }
}
