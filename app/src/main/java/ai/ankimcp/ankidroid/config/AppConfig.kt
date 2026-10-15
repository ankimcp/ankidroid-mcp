package ai.ankimcp.ankidroid.config

import ai.ankimcp.ankidroid.BuildConfig

/** Static build-time configuration. */
data class AppConfig(
    /** Tunnel relay WebSocket URL, e.g. `wss://ankidroid.ankimcp.ai`. */
    val tunnelUrl: String,
    /** App versionName, sent as `x-ankimcp-client-version` and MCP `serverInfo.version`. */
    val appVersion: String,
) {
    companion object {
        /** OAuth client id shared with the CLI and the desktop add-on. */
        const val OAUTH_CLIENT_ID = "ankimcp-cli"

        fun fromBuildConfig(): AppConfig =
            AppConfig(tunnelUrl = BuildConfig.TUNNEL_URL, appVersion = BuildConfig.VERSION_NAME)
    }
}
