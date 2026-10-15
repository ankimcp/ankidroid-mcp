package ai.ankimcp.ankidroid.tunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire protocol between this app and the AnkiMCP tunnel relay. Mirrors
 * `anki_mcp_server/tunnel/protocol.py` (add-on) and `apps/tunnel/src/types/protocol.ts`
 * (relay). See docs/PROTOCOL.md.
 */
object CloseCodes {
    const val NORMAL = 1000
    const val GOING_AWAY = 1001
    const val AUTH_FAILED = 4001
    const val TOKEN_REVOKED = 4002
    const val TUNNEL_AUTH_FAILED = 4003
    const val ACCOUNT_DELETED = 4004
    const val SESSION_REPLACED = 4005
    const val URL_REGENERATED = 4006
    const val SERVICE_UNAVAILABLE = 4008
    const val SHUTDOWN = 4009
    const val IDLE_TIMEOUT = 4010

    /**
     * The host does not serve this client type (wrong endpoint, e.g. the phone on the desktop
     * tunnel). Sent as a close frame right after the upgrade.
     */
    const val CLIENT_KIND_MISMATCH = 4011

    private val NO_RECONNECT =
        setOf(NORMAL, TOKEN_REVOKED, ACCOUNT_DELETED, SESSION_REPLACED, IDLE_TIMEOUT, CLIENT_KIND_MISMATCH)
    private val REFRESH_TOKEN = setOf(AUTH_FAILED, TUNNEL_AUTH_FAILED)

    /** Everything except the permanent codes reconnects, including unknown codes. */
    fun shouldReconnect(code: Int): Boolean = code !in NO_RECONNECT

    fun shouldRefreshToken(code: Int): Boolean = code in REFRESH_TOKEN
}

object ProtocolConstants {
    /** Time allowed for TCP + TLS + WebSocket upgrade (the add-on's `open_timeout`). */
    const val OPEN_TIMEOUT_MS = 10_000L

    /** Time allowed between socket open and `tunnel_established`. */
    const val CONNECTION_TIMEOUT_MS = 10_000L

    /** Server pings every 30 s; we check health on the same cadence. */
    const val HEARTBEAT_INTERVAL_MS = 30_000L

    /** Close the socket if no server ping arrived for this long. */
    const val HEALTH_CHECK_TIMEOUT_MS = 60_000L

    /** Per-request budget; below the relay's own 30 s timeout so our 504 wins. */
    const val REQUEST_TIMEOUT_MS = 25_000L

    const val CLIENT_TYPE_HEADER = "x-ankimcp-client-type"
    const val CLIENT_VERSION_HEADER = "x-ankimcp-client-version"
    const val CLIENT_TYPE = "ankidroid"

    private val SEMVER_TRIPLE = Regex("""(\d+)\.(\d+)\.(\d+)""")

    /**
     * Reduce a version string to bare `MAJOR.MINOR.PATCH`, or null if there is none
     * (the header is then omitted). Same rule as the add-on's `normalize_client_version`.
     */
    fun normalizeClientVersion(raw: String): String? =
        SEMVER_TRIPLE.find(raw)?.destructured?.let { (major, minor, patch) -> "$major.$minor.$patch" }
}

/** Messages the relay sends to us. */
@Serializable
sealed class ServerMessage {
    @Serializable
    @SerialName("tunnel_established")
    data class TunnelEstablished(
        val url: String,
        val expiresAt: String? = null,
    ) : ServerMessage()

    @Serializable
    @SerialName("request")
    data class Request(
        val requestId: String,
        val method: String,
        val path: String,
        val headers: Map<String, String> = emptyMap(),
        /** JSON-RPC message as a string; absent for bodyless requests. */
        val body: String? = null,
    ) : ServerMessage()

    @Serializable
    @SerialName("ping")
    data class Ping(val timestamp: Long) : ServerMessage()

    @Serializable
    @SerialName("error")
    data class Error(
        val code: String,
        val message: String,
        val details: JsonElement? = null,
    ) : ServerMessage()
}

/** Messages we send to the relay. */
@Serializable
sealed class ClientMessage {
    @Serializable
    @SerialName("response")
    data class Response(
        val requestId: String,
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String? = null,
    ) : ClientMessage()

    @Serializable
    @SerialName("pong")
    data class Pong(val timestamp: Long) : ClientMessage()
}

class ProtocolException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object Protocol {
    private val SERVER_TYPES = setOf("tunnel_established", "request", "ping", "error")

    internal val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * Parse one text frame from the relay.
     *
     * @throws ProtocolException on invalid JSON, a non-object, a missing `type`, an unknown
     *   `type`, or a known type with missing/invalid fields.
     */
    fun parseServerMessage(raw: String): ServerMessage {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (e: SerializationException) {
            throw ProtocolException("Invalid JSON: ${e.message}", e)
        }
        val obj = element as? JsonObject
            ?: throw ProtocolException("Expected JSON object, got ${element::class.simpleName}")
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ProtocolException("Message missing 'type' field")
        if (type !in SERVER_TYPES) throw ProtocolException("Unknown server message type: '$type'")
        return try {
            json.decodeFromJsonElement(ServerMessage.serializer(), obj)
        } catch (e: SerializationException) {
            throw ProtocolException("Malformed '$type' message: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("Malformed '$type' message: ${e.message}", e)
        }
    }

    fun encode(message: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), message)
}
