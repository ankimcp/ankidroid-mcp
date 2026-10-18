package ai.ankimcp.ankidroid.local

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.DeckRow
import ai.ankimcp.ankidroid.anki.FakeAvailability
import ai.ankimcp.ankidroid.anki.RecordingProvider
import ai.ankimcp.ankidroid.anki.toRows
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.mcp.McpServer
import ai.ankimcp.ankidroid.mcp.ToolRegistry
import ai.ankimcp.ankidroid.tools.ListDecksTool
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import ai.ankimcp.ankidroid.tunnel.JsonRpcHandler
import ai.ankimcp.ankidroid.tunnel.WakeLocks
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ThreadPoolExecutor

class McpHttpAdapterTest {
    private val log = TunnelLog(mirrorToLogcat = false)
    private val bound = BoundAddress("192.168.1.20", 8765)
    private var token: String? = TOKEN
    private val wakeLockTags = mutableListOf<String>()
    private var wakeLocksHeld = 0
    private val wakeLocks = WakeLocks { tag, _ ->
        wakeLockTags += tag
        wakeLocksHeld++
        AutoCloseable { wakeLocksHeld-- }
    }
    private val executors = mutableListOf<ThreadPoolExecutor>()

    @After
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
    }

    /** The real MCP server and list_decks tool over a fake AnkiDroid provider, as the app wires them. */
    private fun mcpServer(decks: List<DeckRow>): McpServer {
        val executor = AnkiDroidRepository.newProviderExecutor().also { executors += it }
        val provider = RecordingProvider { decks.toRows() }
        val repository = AnkiDroidRepository(provider, FakeAvailability(), executor, log, ElapsedClock { 0L })
        return McpServer(ToolRegistry(listOf(ListDecksTool(repository))), serverVersion = "test", log = log)
    }

    private fun adapter(
        handler: JsonRpcHandler = mcpServer(listOf(DeckRow(1, "Default", "[1,2,3]", "false"))),
        address: BoundAddress? = bound,
        timeoutMs: Long = 5_000,
    ) = McpHttpAdapter(handler, { address }, { token }, log, wakeLocks, ElapsedClock { 0L }, timeoutMs)

    private fun post(
        body: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "POST",
        path: String = "/mcp",
        bodyProblem: HttpRequest.BodyProblem? = null,
    ) = HttpRequest(
        method = method,
        path = path,
        headers = mapOf(
            "host" to "192.168.1.20:8765",
            "authorization" to "Bearer $TOKEN",
            "content-type" to "application/json",
            "accept" to "application/json, text/event-stream",
        ) + headers,
        body = body.toByteArray(),
        bodyProblem = bodyProblem,
    )

    private fun HttpRequest.withoutHeader(name: String) =
        HttpRequest(method, path, headers - name, body, bodyProblem)

    private fun handle(request: HttpRequest, adapter: McpHttpAdapter = adapter()) = runBlocking { adapter.handle(request) }

    private fun errorMessage(response: HttpResponse): String {
        val json = Json.parseToJsonElement(response.body).jsonObject
        assertEquals("2.0", json["jsonrpc"]!!.jsonPrimitive.content)
        return json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
    }

    @Test
    fun `initialize, notification and tools call go through the MCP server`() {
        val init = handle(post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""))
        assertEquals(200, init.status)
        assertEquals("application/json", init.contentType)
        assertTrue(init.headers.keys.none { it.equals("mcp-session-id", ignoreCase = true) })
        val result = Json.parseToJsonElement(init.body).jsonObject["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)

        val initialized = handle(post("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertEquals(202, initialized.status)
        assertNull(initialized.contentType)
        assertEquals("", initialized.body)

        val call = handle(
            post(
                """{"jsonrpc":"2.0","id":"c1","method":"tools/call","params":{"name":"list_decks","arguments":{"include_stats":true}}}""",
                mapOf("mcp-protocol-version" to "2025-06-18", "mcp-session-id" to "ignored"),
            ),
        )
        assertEquals(200, call.status)
        val json = Json.parseToJsonElement(call.body).jsonObject
        assertEquals("c1", json["id"]!!.jsonPrimitive.content)
        val decks = json["result"]!!.jsonObject["structuredContent"]!!.jsonObject["decks"]!!.jsonArray
        assertEquals("Default", decks[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(3, decks[0].jsonObject["stats"]!!.jsonObject["new_count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `every request holds a wake lock while it runs`() {
        handle(post("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))

        assertEquals(listOf("local-request"), wakeLockTags)
        assertEquals(0, wakeLocksHeld)
    }

    @Test
    fun `unparseable JSON is 400 with the -32700 body`() {
        for (body in listOf("not json", "")) {
            val response = handle(post(body))

            assertEquals(body, 400, response.status)
            assertEquals(-32700, Json.parseToJsonElement(response.body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `other JSON-RPC errors stay 200 with the error body, as on the tunnel`() {
        val response = handle(post("""{"jsonrpc":"2.0","id":1,"method":"nope"}"""))

        assertEquals(200, response.status)
        assertEquals(-32601, Json.parseToJsonElement(response.body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `GET and DELETE are 405 with Allow POST`() {
        for (method in listOf("GET", "DELETE", "PUT", "OPTIONS")) {
            val response = handle(post("", method = method))
            assertEquals(method, 405, response.status)
            assertEquals("POST", response.headers["Allow"])
            assertTrue(errorMessage(response).startsWith("Method Not Allowed"))
        }
    }

    @Test
    fun `other paths are 404`() {
        assertEquals(404, handle(post("{}", path = "/")).status)
        assertEquals(404, handle(post("{}", path = "/mcp/")).status)
    }

    @Test
    fun `Host must be the bound address and port`() {
        for (host in listOf("evil.example:8765", "192.168.1.20:9999", "192.168.1.20", "localhost:8765")) {
            val response = handle(post("{}", mapOf("host" to host)))
            assertEquals(host, 403, response.status)
        }
        assertEquals(403, handle(post("{}").withoutHeader("host")).status)
        assertEquals(200, handle(post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")).status)
    }

    @Test
    fun `Origin is optional, but when present must be this host`() {
        val ok = handle(post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", mapOf("origin" to "http://192.168.1.20:8765")))
        assertEquals(200, ok.status)

        for (origin in listOf("https://evil.example", "http://192.168.1.20:8766", "https://192.168.1.20:8765", "null")) {
            val response = handle(post("{}", mapOf("origin" to origin)))
            assertEquals(origin, 403, response.status)
            assertTrue(errorMessage(response).startsWith("Forbidden"))
        }
    }

    @Test
    fun `Host and Origin are checked before the token`() {
        token = "other"
        assertEquals(403, handle(post("{}", mapOf("host" to "evil.example:8765"))).status)
    }

    @Test
    fun `missing or wrong bearer token is 403 with a JSON-RPC error and no challenge`() {
        val cases = listOf(
            post("{}").withoutHeader("authorization"),
            post("{}", mapOf("authorization" to "Bearer wrong")),
            post("{}", mapOf("authorization" to TOKEN)),
            post("{}", mapOf("authorization" to "Basic $TOKEN")),
        )
        for (request in cases) {
            val response = handle(request)
            // Not 401: MCP clients start OAuth discovery on any 401 (D55).
            assertEquals(403, response.status)
            assertEquals("application/json", response.contentType)
            assertTrue(errorMessage(response).contains("access token"))
            assertTrue(response.headers.keys.none { it.equals("WWW-Authenticate", ignoreCase = true) })
        }
        // Also for GET: nothing is revealed before authentication.
        assertEquals(403, handle(post("", method = "GET").withoutHeader("authorization")).status)
    }

    @Test
    fun `with Require access token off, no token is needed but Host and Origin still are`() {
        val open = McpHttpAdapter(
            mcpServer(emptyList()), { bound }, { token }, log, wakeLocks, ElapsedClock { 0L }, requireToken = { false },
        )
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

        assertEquals(200, handle(post(ping).withoutHeader("authorization"), open).status)
        assertEquals(200, handle(post(ping, mapOf("authorization" to "Bearer anything")), open).status)
        assertNull(open.screen(post(ping).withoutHeader("authorization")))
        assertEquals(403, handle(post(ping, mapOf("host" to "evil.example:8765")).withoutHeader("authorization"), open).status)
        assertEquals(403, handle(post(ping, mapOf("origin" to "https://evil.example")).withoutHeader("authorization"), open).status)
        token = null
        assertEquals(200, handle(post(ping).withoutHeader("authorization"), open).status)
    }

    @Test
    fun `screen refuses on the headers alone and lets good requests through`() {
        val adapter = adapter()
        val headersOnly = post("").withoutHeader("authorization")

        assertEquals(403, adapter.screen(headersOnly)!!.status)
        assertEquals(403, adapter.screen(post("", mapOf("origin" to "https://evil.example")))!!.status)
        assertNull(adapter.screen(post("")))
    }

    @Test
    fun `refused requests are rate-limited per reason, with a count, at every log level`() {
        var now = 0L
        val adapter = McpHttpAdapter(mcpServer(emptyList()), { bound }, { token }, log, wakeLocks, ElapsedClock { now })
        val bad = post("{}", mapOf("authorization" to "Bearer wrong"))

        repeat(5) { handle(bad, adapter) }
        repeat(50) { adapter.screen(bad) }
        handle(post("{}", mapOf("host" to "evil.example:8765")), adapter)
        now += McpHttpAdapter.REJECTION_LOG_INTERVAL_MS
        handle(bad, adapter)

        // All levels: a refused request must not add a per-request debug line either.
        val lines = log.entries.value.map { it.message }
        assertEquals(
            listOf(
                "Local server rejected a request: missing or wrong access token",
                "Local server rejected a request: Host header is not this phone's address",
                "Local server rejected a request: missing or wrong access token (54 more not logged)",
            ),
            lines,
        )
    }

    @Test
    fun `logged method and path are stripped of control characters and truncated`() {
        handle(post("", method = "GET", path = "/mcp\r\nInjected: line" + "x".repeat(200)))

        val line = log.entries.value.last().message
        assertFalse(line.contains('\n') || line.contains('\r'))
        assertTrue(line.length < 120)
        assertEquals("ab", McpHttpAdapter.sanitize("a\u0000\nb"))
        assertEquals(65, McpHttpAdapter.sanitize("y".repeat(100)).length)
    }

    @Test
    fun `bearer scheme is case-insensitive`() {
        val response = handle(post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", mapOf("authorization" to "bearer $TOKEN")))
        assertEquals(200, response.status)
    }

    @Test
    fun `no token yet means every request is refused`() {
        token = null
        assertEquals(403, handle(post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")).status)
    }

    @Test
    fun `rejections are logged by reason and never contain the token`() {
        handle(post("{}", mapOf("authorization" to "Bearer wrong-$TOKEN")))
        handle(post("{}", mapOf("host" to "evil.example")))
        handle(post("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))

        val messages = log.entries.value.map { it.message }
        assertTrue(messages.any { it == "Local server rejected a request: missing or wrong access token" })
        assertTrue(messages.any { it == "Local server rejected a request: Host header is not this phone's address" })
        assertTrue(messages.none { it.contains(TOKEN) })
    }

    @Test
    fun `body framing problems are 411 and 413`() {
        assertEquals(413, handle(post("", bodyProblem = HttpRequest.BodyProblem.TooLarge)).status)
        assertEquals(411, handle(post("", bodyProblem = HttpRequest.BodyProblem.LengthRequired)).status)
    }

    @Test
    fun `Content-Type must be JSON when given`() {
        assertEquals(415, handle(post("{}", mapOf("content-type" to "text/plain"))).status)
        val withCharset = post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", mapOf("content-type" to "application/json; charset=utf-8"))
        assertEquals(200, handle(withCharset).status)
        assertEquals(200, handle(post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""").withoutHeader("content-type")).status)
    }

    @Test
    fun `Accept must admit JSON when given`() {
        assertEquals(406, handle(post("{}", mapOf("accept" to "text/event-stream"))).status)
        assertTrue(McpHttpAdapter.acceptsJson(null))
        assertTrue(McpHttpAdapter.acceptsJson("*/*"))
        assertTrue(McpHttpAdapter.acceptsJson("text/event-stream, application/json;q=0.9"))
        assertFalse(McpHttpAdapter.acceptsJson("text/html"))
    }

    @Test
    fun `unsupported MCP-Protocol-Version is 400, except on initialize`() {
        val ping = post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", mapOf("mcp-protocol-version" to "1999-01-01"))
        val response = handle(ping)
        assertEquals(400, response.status)
        assertTrue(errorMessage(response).contains("1999-01-01"))

        val init = post(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2026-07-28"}}""",
            mapOf("mcp-protocol-version" to "2026-07-28"),
        )
        assertEquals(200, handle(init).status)
    }

    @Test
    fun `a handler that runs past the budget is 504 with -32004 and the request id`() {
        val slow = JsonRpcHandler { _, _ -> awaitCancellation() }
        val response = handle(post("""{"jsonrpc":"2.0","id":7,"method":"tools/call"}"""), adapter(handler = slow, timeoutMs = 50))

        assertEquals(504, response.status)
        val json = Json.parseToJsonElement(response.body).jsonObject
        assertEquals(7, json["id"]!!.jsonPrimitive.int)
        assertEquals(-32004, json["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(0, wakeLocksHeld)
    }

    @Test
    fun `a handler exception is 500 with -32603`() {
        val failing = JsonRpcHandler { _, _ -> throw IllegalStateException("boom") }
        val response = handle(post("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""), adapter(handler = failing))

        assertEquals(500, response.status)
        assertEquals(-32603, Json.parseToJsonElement(response.body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `not bound means 503`() {
        assertEquals(503, handle(post("{}"), adapter(address = null)).status)
    }

    @Test
    fun `transport errors are JSON-RPC -32000 without id`() {
        val json = Json.parseToJsonElement(handle(post("", method = "GET")).body).jsonObject
        assertEquals(-32000, json["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertTrue(json["id"].toString() == "null")
        assertTrue(json is JsonObject)
    }

    private companion object {
        const val TOKEN = "dGVzdC10b2tlbi10aGF0LWlzLWxvbmctZW5vdWdoLTEyMw"
    }
}
