package ai.ankimcp.ankidroid.local

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.mcp.JsonRpc
import ai.ankimcp.ankidroid.mcp.JsonRpcErrors
import ai.ankimcp.ankidroid.mcp.McpServer
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import ai.ankimcp.ankidroid.tunnel.JsonRpcHandler
import ai.ankimcp.ankidroid.tunnel.ProtocolConstants
import ai.ankimcp.ankidroid.tunnel.TunnelClient
import ai.ankimcp.ankidroid.tunnel.WakeLocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** The address the server is bound to; requests must name exactly this host. */
data class BoundAddress(val host: String, val port: Int)

/**
 * MCP Streamable HTTP, stateless (MCP 2025-06-18 transports; PROTOCOL.md "Local HTTP
 * transport"): `POST /mcp` with one JSON-RPC message, answered with `application/json`, or
 * 202 for a notification. No SSE: GET and DELETE are 405, no `Mcp-Session-Id` is issued and
 * one sent by the client is ignored. The JSON-RPC itself is [handler], the same
 * [McpServer] the tunnel uses.
 *
 * Checks, in order: Host and Origin against [address] (403, DNS rebinding), the bearer
 * [token] when [requireToken] is on (403 without a challenge, like the add-on: MCP clients
 * start OAuth discovery on any 401), path (404), method (405), body framing (411/413), Content-Type (415), Accept (406),
 * `MCP-Protocol-Version` (400), JSON syntax (400 with -32700). The first three also run on the
 * headers alone ([screen]) before the engine reads the body. Rejections are logged by reason
 * only, at most once per reason per [REJECTION_LOG_INTERVAL_MS]; the token and the
 * Authorization header never reach the log, and the method and path are sanitised.
 */
class McpHttpAdapter(
    private val handler: JsonRpcHandler,
    private val address: () -> BoundAddress?,
    private val token: () -> String?,
    private val log: TunnelLog,
    private val wakeLocks: WakeLocks,
    private val clock: ElapsedClock,
    private val requestTimeoutMs: Long = ProtocolConstants.REQUEST_TIMEOUT_MS,
    /** "Require access token" (off by default, D55); off skips the Authorization check only. */
    private val requireToken: () -> Boolean = { true },
) {
    /** Reason -> (time of the last warning, rejections not logged since). */
    private val rejections = HashMap<String, Pair<Long, Int>>()

    /**
     * Host, Origin and token on the headers only, so an unauthenticated peer never makes the
     * engine read (and allocate) a body. Null when the request may go on.
     */
    fun screen(request: HttpRequest): HttpResponse? = preBodyRejection(request)

    /**
     * Refused requests leave only their rate-limited warning ([logRejection]), no per-request
     * line, so an unauthenticated peer cannot flush the log; authenticated ones get a debug line.
     */
    suspend fun handle(request: HttpRequest): HttpResponse {
        val start = clock.elapsedMs()
        preBodyRejection(request)?.let { return it }
        val response = respond(request)
        logRequest(request, response, start)
        return response
    }

    private fun logRequest(request: HttpRequest, response: HttpResponse, start: Long) {
        log.debug("Local ${sanitize(request.method)} ${sanitize(request.path)} -> ${response.status} (${clock.elapsedMs() - start} ms)")
    }

    private fun preBodyRejection(request: HttpRequest): HttpResponse? {
        val bound = address() ?: return reject(503, "Server is not running", "not running")
        // Host and Origin are the DNS-rebinding defence and run whether or not a token is required.
        hostProblem(request, bound)?.let { return reject(403, "Forbidden: $it", it) }
        if (requireToken() && !authorized(request.header("authorization"))) {
            return reject(
                403,
                "Forbidden: send the access token from the AnkiMCP app as \"Authorization: Bearer <token>\"",
                "missing or wrong access token",
            )
        }
        return null
    }

    private suspend fun respond(request: HttpRequest): HttpResponse {
        if (request.path != MCP_PATH) return error(404, "Not Found: the MCP endpoint is $MCP_PATH")
        if (request.method != "POST") {
            return error(405, "Method Not Allowed: this server only accepts POST", mapOf("Allow" to "POST"))
        }
        when (request.bodyProblem) {
            HttpRequest.BodyProblem.TooLarge -> return error(413, "Payload Too Large")
            HttpRequest.BodyProblem.LengthRequired -> return error(411, "Length Required: send Content-Length")
            null -> Unit
        }
        val contentType = request.header("content-type")
        if (contentType != null && !contentType.substringBefore(';').trim().equals(JSON, ignoreCase = true)) {
            return error(415, "Unsupported Media Type: send application/json")
        }
        if (!acceptsJson(request.header("accept"))) return error(406, "Not Acceptable: this server answers application/json")

        val body = request.body.toString(Charsets.UTF_8)
        // Not JSON at all: 400 with the JSON-RPC parse error, as the spec and the TypeScript SDK do.
        val parsed = try {
            JsonRpc.json.parseToJsonElement(body)
        } catch (_: Exception) {
            return HttpResponse(400, JSON, JsonRpc.error(JsonNull, JsonRpcErrors.PARSE_ERROR, "Parse error").toString())
        }
        val method = ((parsed as? JsonObject)?.get("method") as? JsonPrimitive)?.content
        val protocolVersion = request.header(PROTOCOL_VERSION_HEADER)?.trim()
        if (protocolVersion != null && protocolVersion !in McpServer.SUPPORTED_PROTOCOL_VERSIONS && method != "initialize") {
            return error(400, "Bad Request: unsupported MCP-Protocol-Version ${sanitize(protocolVersion)}")
        }
        return call(body, request.headers)
    }

    private suspend fun call(body: String, headers: Map<String, String>): HttpResponse =
        // Same per-request budget and wake lock as a relayed request (D27, D49).
        wakeLocks.acquire("local-request", requestTimeoutMs + WAKE_LOCK_SLACK_MS).use {
            try {
                val result = withTimeout(requestTimeoutMs) { handler.handle(body, headers) }
                if (result == null) HttpResponse(202) else HttpResponse(200, JSON, result)
            } catch (e: TimeoutCancellationException) {
                val envelope = TunnelClient.errorEnvelope(TunnelClient.extractRequestId(body), JsonRpcErrors.REQUEST_TIMEOUT, "Request timed out")
                HttpResponse(504, JSON, envelope.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Local server failed to process a request: ${e.message ?: e.javaClass.simpleName}")
                val envelope = TunnelClient.errorEnvelope(JsonNull, JsonRpcErrors.INTERNAL_ERROR, "Internal error: ${e.message}")
                HttpResponse(500, JSON, envelope.toString())
            }
        }

    private fun hostProblem(request: HttpRequest, bound: BoundAddress): String? {
        // Ports are 1024+ (LocalServerSettings), so the Host always carries the port.
        val host = request.header("host")?.trim() ?: return "no Host header"
        if (!host.equals(authority(bound), ignoreCase = true)) return "Host header is not this phone's address"
        val origin = request.header("origin")?.trim() ?: return null
        return if (origin.trimEnd('/').equals("http://${authority(bound)}", ignoreCase = true)) null else "Origin is not this phone's address"
    }

    private fun authorized(header: String?): Boolean {
        val expected = token() ?: return false
        val presented = header?.trim()?.takeIf { it.regionMatches(0, BEARER, 0, BEARER.length, ignoreCase = true) }
            ?.substring(BEARER.length)?.trim() ?: return false
        return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    private fun reject(status: Int, message: String, reason: String): HttpResponse {
        logRejection(reason)
        return error(status, message)
    }

    /** A scanner or a misconfigured client must not flood the 100-entry log. */
    private fun logRejection(reason: String) {
        val now = clock.elapsedMs()
        val suppressed = synchronized(rejections) {
            val last = rejections[reason]
            if (last != null && now - last.first < REJECTION_LOG_INTERVAL_MS) {
                rejections[reason] = last.first to last.second + 1
                return
            }
            rejections[reason] = now to 0
            last?.second ?: 0
        }
        val more = if (suppressed > 0) " ($suppressed more not logged)" else ""
        log.warn("Local server rejected a request: $reason$more")
    }

    companion object {
        const val MCP_PATH = "/mcp"

        /** At most one warning per rejection reason in this window. */
        const val REJECTION_LOG_INTERVAL_MS = 10_000L
        private const val MAX_LOGGED_CHARS = 64
        private const val JSON = "application/json"
        private const val BEARER = "Bearer "
        private const val PROTOCOL_VERSION_HEADER = "mcp-protocol-version"
        private const val WAKE_LOCK_SLACK_MS = 5_000L

        /** Transport-level errors, as the MCP TypeScript SDK sends them: JSON-RPC -32000, no id. */
        const val TRANSPORT_ERROR = -32000

        internal fun authority(bound: BoundAddress): String = "${bound.host}:${bound.port}"

        internal fun error(status: Int, message: String, headers: Map<String, String> = emptyMap()): HttpResponse =
            HttpResponse(status, JSON, JsonRpc.error(JsonNull, TRANSPORT_ERROR, message).toString(), headers)

        /** Missing Accept means anything; otherwise it must admit JSON (clients send JSON and SSE). */
        internal fun acceptsJson(accept: String?): Boolean {
            if (accept.isNullOrBlank()) return true
            return accept.split(',').map { it.substringBefore(';').trim().lowercase() }
                .any { it == JSON || it == "application/*" || it == "*/*" }
        }

        /** Client-controlled text for the log or a reply: no control characters, at most 64 characters. */
        internal fun sanitize(text: String): String {
            val clean = text.filterNot { it.isISOControl() }
            return if (clean.length > MAX_LOGGED_CHARS) clean.take(MAX_LOGGED_CHARS) + "…" else clean
        }
    }
}
