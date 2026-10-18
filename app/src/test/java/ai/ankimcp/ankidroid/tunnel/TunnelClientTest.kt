package ai.ankimcp.ankidroid.tunnel

import ai.ankimcp.ankidroid.log.TunnelLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TunnelClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val received = LinkedBlockingQueue<String>()
    private val serverSocket = CompletableFuture<WebSocket>()
    private val serverSawClose = CompletableFuture<Int>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val url get() = server.url("/").toString().replaceFirst("http://", "ws://")

    private fun enqueueRelay(onOpen: (WebSocket) -> Unit = {}) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.complete(webSocket)
                        onOpen(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received.add(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        serverSawClose.complete(code)
                        webSocket.close(code, null)
                    }
                },
            ),
        )
    }

    private fun establish(ws: WebSocket) {
        ws.send("""{"type":"tunnel_established","url":"https://tunnel.ankimcp.ai/mcp"}""")
    }

    private fun client(
        handler: JsonRpcHandler = JsonRpcHandler { _, _ -> """{"jsonrpc":"2.0","id":1,"result":{}}""" },
        onEstablished: (String) -> Unit = {},
        openTimeoutMs: Long = 5_000,
        handshakeTimeoutMs: Long = 5_000,
        heartbeatIntervalMs: Long = 30_000,
        healthTimeoutMs: Long = 60_000,
        requestTimeoutMs: Long = 5_000,
        clock: ElapsedClock = ElapsedClock.Monotonic,
        uptimeClock: ElapsedClock = ElapsedClock.Monotonic,
        wakeLocks: WakeLocks = WakeLocks.None,
        onRequestDone: () -> Unit = {},
        queueSize: (WebSocket) -> Long = { it.queueSize() },
        dedupe: RequestDedupe = RequestDedupe(clock),
        onRequest: () -> Unit = {},
        log: TunnelLog = TunnelLog(mirrorToLogcat = false),
    ) = TunnelClient(
        http = http,
        url = url,
        accessToken = "token-123",
        clientVersion = "0.1.0",
        handler = handler,
        log = log,
        onRequest = onRequest,
        clock = clock,
        wakeLocks = wakeLocks,
        onEstablished = onEstablished,
        openTimeoutMs = openTimeoutMs,
        handshakeTimeoutMs = handshakeTimeoutMs,
        heartbeatIntervalMs = heartbeatIntervalMs,
        healthTimeoutMs = healthTimeoutMs,
        requestTimeoutMs = requestTimeoutMs,
        uptimeClock = uptimeClock,
        onRequestDone = onRequestDone,
        queueSize = queueSize,
        dedupe = dedupe,
    )

    private fun requestFrame(requestId: String, rpcId: Int) =
        """{"type":"request","requestId":"$requestId","method":"POST","path":"/mcp","headers":{},""" +
            """"body":"{\"jsonrpc\":\"2.0\",\"id\":$rpcId,\"method\":\"tools/call\"}"}"""

    private fun nextMessage(): JsonObject {
        val raw = received.poll(5, TimeUnit.SECONDS)
        assertNotNull("expected a message from the client", raw)
        return Json.parseToJsonElement(raw!!).jsonObject
    }

    private class RecordingWakeLocks : WakeLocks {
        val acquired = CopyOnWriteArrayList<String>()
        val released = CopyOnWriteArrayList<String>()

        override fun acquire(tag: String, timeoutMs: Long): AutoCloseable {
            acquired += tag
            return AutoCloseable { released += tag }
        }
    }

    @Test
    fun `sends auth and client identification headers`() = runBlocking {
        enqueueRelay { ws ->
            establish(ws)
            ws.close(1000, "done")
        }

        withTimeout(10_000) { client().run() }

        val request = server.takeRequest()
        assertEquals("Bearer token-123", request.getHeader("Authorization"))
        assertEquals("ankidroid", request.getHeader("x-ankimcp-client-type"))
        assertEquals("0.1.0", request.getHeader("x-ankimcp-client-version"))
    }

    @Test
    fun `handshake times out without tunnel_established`() {
        enqueueRelay()

        val error = assertThrows(TunnelConnectionException::class.java) {
            runBlocking { withTimeout(10_000) { client(handshakeTimeoutMs = 300).run() } }
        }

        assertTrue(error.message, error.message!!.contains("Timed out waiting for tunnel_established"))
    }

    @Test
    fun `open times out separately when the upgrade never completes`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val error = assertThrows(TunnelConnectionException::class.java) {
            runBlocking { withTimeout(10_000) { client(openTimeoutMs = 300, handshakeTimeoutMs = 60_000).run() } }
        }

        assertTrue(error.message, error.message!!.contains("Timed out opening the connection"))
    }

    @Test
    fun `close during handshake carries the close code`() {
        enqueueRelay { ws -> ws.close(4003, "Invalid token") }

        val error = assertThrows(TunnelConnectionException::class.java) {
            runBlocking { withTimeout(10_000) { client().run() } }
        }

        assertEquals(4003, error.closeCode)
    }

    @Test
    fun `wrong first message fails the handshake`() {
        enqueueRelay { ws -> ws.send("""{"type":"ping","timestamp":1}""") }

        val error = assertThrows(TunnelConnectionException::class.java) {
            runBlocking { withTimeout(10_000) { client().run() } }
        }

        assertTrue(error.message, error.message!!.contains("Expected tunnel_established"))
    }

    @Test
    fun `request is answered with a 200 response carrying the JSON-RPC body`() = runBlocking {
        val established = CompletableFuture<String>()
        val bodies = LinkedBlockingQueue<String>()
        val headersSeen = CompletableFuture<Map<String, String>>()
        val wakeLocks = RecordingWakeLocks()
        enqueueRelay { ws ->
            establish(ws)
            ws.send(
                """{"type":"request","requestId":"req-1","method":"POST","path":"/mcp",
                   "headers":{"content-type":"application/json","mcp-protocol-version":"2025-06-18"},
                   "body":"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"}""",
            )
        }
        val tunnel = client(
            handler = JsonRpcHandler { body, headers ->
                bodies.add(body)
                headersSeen.complete(headers)
                """{"jsonrpc":"2.0","id":1,"result":{}}"""
            },
            onEstablished = { established.complete(it) },
            wakeLocks = wakeLocks,
        )
        val run = async(Dispatchers.IO) { tunnel.run() }

        val response = nextMessage()
        assertEquals("response", response["type"]!!.jsonPrimitive.content)
        assertEquals("req-1", response["requestId"]!!.jsonPrimitive.content)
        assertEquals(200, response["statusCode"]!!.jsonPrimitive.int)
        assertEquals("application/json", response["headers"]!!.jsonObject["content-type"]!!.jsonPrimitive.content)
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""", response["body"]!!.jsonPrimitive.content)
        assertEquals("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", bodies.poll(1, TimeUnit.SECONDS))
        assertEquals("2025-06-18", headersSeen.get(1, TimeUnit.SECONDS)["mcp-protocol-version"])
        assertEquals("https://tunnel.ankimcp.ai/mcp", established.get(1, TimeUnit.SECONDS))

        serverSocket.get(1, TimeUnit.SECONDS).close(4005, "Connected from another device")
        val closed = withTimeout(10_000) { run.await() }
        assertEquals(CloseResult(4005, "Connected from another device"), closed)
        assertEquals(listOf("request"), wakeLocks.acquired.toList())
        assertEquals(listOf("request"), wakeLocks.released.toList())
    }

    @Test
    fun `request wake lock is released when an in-flight request is cancelled`() = runBlocking {
        val handlerStarted = CompletableFuture<Unit>()
        val wakeLocks = RecordingWakeLocks()
        enqueueRelay { ws ->
            establish(ws)
            ws.send(
                """{"type":"request","requestId":"req-c","method":"POST","path":"/mcp","headers":{},
                   "body":"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}"}""",
            )
        }
        val run = async(Dispatchers.IO) {
            client(
                handler = JsonRpcHandler { _, _ ->
                    handlerStarted.complete(Unit)
                    awaitCancellation()
                },
                requestTimeoutMs = 60_000,
                wakeLocks = wakeLocks,
            ).run()
        }
        handlerStarted.get(5, TimeUnit.SECONDS)
        assertTrue(wakeLocks.released.isEmpty())

        serverSocket.get(1, TimeUnit.SECONDS).close(1000, "bye")
        withTimeout(10_000) { run.await() }

        assertEquals(listOf("request"), wakeLocks.released.toList())
    }

    @Test
    fun `request wake lock is released when the request is cancelled before it starts`() = runBlocking {
        val handled = AtomicBoolean(false)
        val released = CopyOnWriteArrayList<String>()
        lateinit var tunnel: TunnelClient
        // The socket drops while the request is being taken: its coroutine is cancelled before it
        // ever runs (run() executes on this single runBlocking thread).
        val wakeLocks = WakeLocks { tag, _ ->
            if (tag == "request") tunnel.abort("dropped")
            AutoCloseable { released += tag }
        }
        enqueueRelay { ws ->
            establish(ws)
            ws.send(
                """{"type":"request","requestId":"req-x","method":"POST","path":"/mcp","headers":{},
                   "body":"{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}"}""",
            )
        }
        tunnel = client(
            handler = JsonRpcHandler { _, _ ->
                handled.set(true)
                """{"jsonrpc":"2.0","id":4,"result":{}}"""
            },
            wakeLocks = wakeLocks,
        )

        val closed = withTimeout(10_000) { tunnel.run() }

        assertEquals(CloseResult(CloseCodes.GOING_AWAY, "dropped"), closed)
        assertFalse("request coroutine should never have run", handled.get())
        assertEquals(listOf("request"), released.toList())
    }

    @Test
    fun `notification is answered with 200 and no body`() = runBlocking {
        enqueueRelay { ws ->
            establish(ws)
            ws.send(
                """{"type":"request","requestId":"req-2","method":"POST","path":"/mcp","headers":{},
                   "body":"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"}""",
            )
        }
        val run = async(Dispatchers.IO) { client(handler = JsonRpcHandler { _, _ -> null }).run() }

        val response = nextMessage()
        assertEquals(200, response["statusCode"]!!.jsonPrimitive.int)
        assertFalse(response.containsKey("body"))

        serverSocket.get(1, TimeUnit.SECONDS).close(1000, "bye")
        assertEquals(1000, withTimeout(10_000) { run.await() }.code)
    }

    @Test
    fun `slow handler yields 504 with JSON-RPC -32004 and the request id`() = runBlocking {
        enqueueRelay { ws ->
            establish(ws)
            ws.send(
                """{"type":"request","requestId":"req-3","method":"POST","path":"/mcp","headers":{},
                   "body":"{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\"}"}""",
            )
        }
        val run = async(Dispatchers.IO) {
            client(
                handler = JsonRpcHandler { _, _ ->
                    delay(10_000)
                    "never"
                },
                requestTimeoutMs = 200,
            ).run()
        }

        val response = nextMessage()
        assertEquals(504, response["statusCode"]!!.jsonPrimitive.int)
        val body = Json.parseToJsonElement(response["body"]!!.jsonPrimitive.content).jsonObject
        assertEquals(-32004, body["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(42, body["id"]!!.jsonPrimitive.int)

        serverSocket.get(1, TimeUnit.SECONDS).close(1000, "bye")
        withTimeout(10_000) { run.await() }
        Unit
    }

    @Test
    fun `a handler that returns as the timeout fires sends its stored 200, not a 504`() = runBlocking {
        enqueueRelay { ws ->
            establish(ws)
            ws.send(requestFrame("req-t", 7))
        }
        val run = async(Dispatchers.IO) {
            client(
                // Suspends once (withTimeout keeps the value of a block that never suspended), then
                // blocks past the timeout, so withTimeout throws after the response was recorded.
                handler = JsonRpcHandler { _, _ ->
                    delay(10)
                    Thread.sleep(500)
                    """{"jsonrpc":"2.0","id":7,"result":{}}"""
                },
                requestTimeoutMs = 100,
            ).run()
        }

        val response = nextMessage()
        assertEquals(200, response["statusCode"]!!.jsonPrimitive.int)
        assertEquals("""{"jsonrpc":"2.0","id":7,"result":{}}""", response["body"]!!.jsonPrimitive.content)

        serverSocket.get(1, TimeUnit.SECONDS).close(1000, "bye")
        withTimeout(10_000) { run.await() }
        Unit
    }

    @Test
    fun `ping is answered while a slow request is still in flight`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        enqueueRelay { ws ->
            establish(ws)
            ws.send(
                """{"type":"request","requestId":"slow","method":"POST","path":"/mcp","headers":{},
                   "body":"{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\"}"}""",
            )
            ws.send("""{"type":"ping","timestamp":77}""")
        }
        val run = async(Dispatchers.IO) {
            client(
                handler = JsonRpcHandler { _, _ ->
                    gate.await()
                    """{"jsonrpc":"2.0","id":9,"result":{}}"""
                },
            ).run()
        }

        val first = nextMessage()
        assertEquals("pong", first["type"]!!.jsonPrimitive.content)
        assertEquals(77, first["timestamp"]!!.jsonPrimitive.int)

        gate.complete(Unit)
        val second = nextMessage()
        assertEquals("response", second["type"]!!.jsonPrimitive.content)
        assertEquals("slow", second["requestId"]!!.jsonPrimitive.content)
        assertEquals(200, second["statusCode"]!!.jsonPrimitive.int)

        serverSocket.get(1, TimeUnit.SECONDS).close(1000, "bye")
        withTimeout(10_000) { run.await() }
        Unit
    }

    @Test
    fun `handler exception yields 500 with JSON-RPC -32603`() = runBlocking {
        enqueueRelay { ws ->
            establish(ws)
            ws.send(
                """{"type":"request","requestId":"req-4","method":"POST","path":"/mcp","headers":{},
                   "body":"{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}"}""",
            )
        }
        val run = async(Dispatchers.IO) {
            client(handler = JsonRpcHandler { _, _ -> throw IllegalStateException("broken") }).run()
        }

        val response = nextMessage()
        assertEquals(500, response["statusCode"]!!.jsonPrimitive.int)
        val body = Json.parseToJsonElement(response["body"]!!.jsonPrimitive.content).jsonObject
        assertEquals(-32603, body["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, body["id"])

        serverSocket.get(1, TimeUnit.SECONDS).close(1000, "bye")
        withTimeout(10_000) { run.await() }
        Unit
    }

    @Test
    fun `ping is answered with pong echoing the timestamp`() = runBlocking {
        enqueueRelay { ws ->
            establish(ws)
            ws.send("""{"type":"ping","timestamp":1704063600000}""")
        }
        val run = async(Dispatchers.IO) { client().run() }

        val pong = nextMessage()
        assertEquals("pong", pong["type"]!!.jsonPrimitive.content)
        assertEquals(1704063600000, pong["timestamp"]!!.jsonPrimitive.content.toLong())

        serverSocket.get(1, TimeUnit.SECONDS).close(1000, "bye")
        withTimeout(10_000) { run.await() }
        Unit
    }

    @Test
    fun `missing server pings close the connection with 1001`() = runBlocking {
        enqueueRelay { ws -> establish(ws) }

        val closed = withTimeout(10_000) {
            client(heartbeatIntervalMs = 100, healthTimeoutMs = 300).run()
        }

        assertEquals(CloseCodes.GOING_AWAY, closed.code)
        assertTrue(closed.reason, closed.reason.contains("No server ping"))
    }

    @Test
    fun `health timeout is measured on the injected elapsed clock`() = runBlocking {
        val now = AtomicLong(0)
        val established = CompletableFuture<String>()
        enqueueRelay { ws -> establish(ws) }
        val run = async(Dispatchers.IO) {
            client(
                clock = { now.get() },
                heartbeatIntervalMs = 20,
                healthTimeoutMs = 60_000,
                onEstablished = { established.complete(it) },
            ).run()
        }
        established.get(5, TimeUnit.SECONDS)

        // Plenty of real heartbeat ticks, but no time passed on the injected clock.
        delay(300)
        assertTrue(run.isActive)

        // Simulates the CPU sleeping: elapsedRealtime jumped while timers were frozen.
        now.set(61_000)
        val closed = withTimeout(5_000) { run.await() }
        assertEquals(CloseCodes.GOING_AWAY, closed.code)
        assertTrue(closed.reason, closed.reason.contains("No server ping in 61s"))
    }

    @Test
    fun `checkHealth drops a stale connection without waiting for the heartbeat timer`() = runBlocking {
        val now = AtomicLong(0)
        val established = CompletableFuture<String>()
        enqueueRelay { ws -> establish(ws) }
        val tunnel = client(clock = { now.get() }, onEstablished = { established.complete(it) })
        val run = async(Dispatchers.IO) { tunnel.run() }
        established.get(5, TimeUnit.SECONDS)

        assertTrue(tunnel.checkHealth())
        now.set(60_001)
        assertFalse(tunnel.checkHealth())

        assertEquals(CloseCodes.GOING_AWAY, withTimeout(5_000) { run.await() }.code)
    }

    @Test
    fun `a check after a freeze waits for the queued ping instead of dropping the socket`() = runBlocking {
        val now = AtomicLong(0)
        val uptime = AtomicLong(0)
        val established = CompletableFuture<String>()
        enqueueRelay { ws -> establish(ws) }
        val tunnel = client(
            clock = { now.get() },
            uptimeClock = { uptime.get() },
            onEstablished = { established.complete(it) },
        )
        val run = async(Dispatchers.IO) { tunnel.run() }
        established.get(5, TimeUnit.SECONDS)

        // Frozen for 76 s while the CPU ran: both clocks moved, the server's ping is still queued.
        now.set(76_000)
        uptime.set(76_000)
        assertTrue(tunnel.checkHealth())

        serverSocket.get(1, TimeUnit.SECONDS).send("""{"type":"ping","timestamp":1}""")
        assertEquals("pong", nextMessage()["type"]!!.jsonPrimitive.content)
        now.set(76_000 + 59_000)
        uptime.set(76_000 + 30_000)
        assertTrue(tunnel.checkHealth())
        assertTrue(run.isActive)

        tunnel.disconnect()
        assertEquals(CloseCodes.NORMAL, withTimeout(5_000) { run.await() }.code)
    }

    @Test
    fun `a 45 s pause after an on-time check still counts as a freeze`() = runBlocking {
        val now = AtomicLong(0)
        val uptime = AtomicLong(0)
        val established = CompletableFuture<String>()
        enqueueRelay { ws -> establish(ws) }
        val tunnel = client(
            clock = { now.get() },
            uptimeClock = { uptime.get() },
            onEstablished = { established.complete(it) },
        )
        val run = async(Dispatchers.IO) { tunnel.run() }
        established.get(5, TimeUnit.SECONDS)

        now.set(31_000)
        uptime.set(31_000)
        assertTrue(tunnel.checkHealth())

        // Frozen right after that check: the next one runs 45 s later with pings 76 s overdue.
        now.set(76_000)
        uptime.set(76_000)
        assertTrue(tunnel.checkHealth())
        assertTrue(run.isActive)

        tunnel.disconnect()
        assertEquals(CloseCodes.NORMAL, withTimeout(5_000) { run.await() }.code)
    }

    @Test
    fun `an on-time check with overdue pings is not a freeze`() = runBlocking {
        val now = AtomicLong(0)
        val uptime = AtomicLong(0)
        val established = CompletableFuture<String>()
        enqueueRelay { ws -> establish(ws) }
        val tunnel = client(
            clock = { now.get() },
            uptimeClock = { uptime.get() },
            onEstablished = { established.complete(it) },
        )
        val run = async(Dispatchers.IO) { tunnel.run() }
        established.get(5, TimeUnit.SECONDS)

        now.set(30_000)
        uptime.set(30_000)
        assertTrue(tunnel.checkHealth())
        now.set(60_001)
        uptime.set(60_001)
        assertFalse(tunnel.checkHealth())

        assertEquals(CloseCodes.GOING_AWAY, withTimeout(5_000) { run.await() }.code)
    }

    @Test
    fun `requests in flight are awaited`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        enqueueRelay { ws ->
            establish(ws)
            ws.send(
                """{"type":"request","requestId":"r1","method":"POST","path":"/mcp","headers":{},""" +
                    """"body":"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"}""",
            )
        }
        val tunnel = client(
            handler = JsonRpcHandler { _, _ ->
                started.complete(Unit)
                release.await()
                """{"jsonrpc":"2.0","id":1,"result":{}}"""
            },
        )
        val run = async(Dispatchers.IO) { tunnel.run() }
        withTimeout(5_000) { started.await() }

        val idle = async { tunnel.awaitRequestsIdle() }
        delay(100)
        assertTrue("a request is still being handled", idle.isActive)
        assertTrue(tunnel.hasRequestsInFlight())

        release.complete(Unit)
        withTimeout(5_000) { idle.await() }
        assertFalse(tunnel.hasRequestsInFlight())
        assertEquals("response", nextMessage()["type"]!!.jsonPrimitive.content)

        tunnel.disconnect()
        withTimeout(5_000) { run.await() }
        Unit
    }

    @Test
    fun `the request lock is released and the request reported done only once the send queue drains`() = runBlocking {
        val queued = AtomicLong(1)
        val events = CopyOnWriteArrayList<String>()
        val done = CompletableDeferred<Unit>()
        val tunnel = flushTestClient(events, done) { queued.get() }
        val run = async(Dispatchers.IO) { tunnel.run() }

        assertEquals("response", nextMessage()["type"]!!.jsonPrimitive.content)
        delay(300)
        assertEquals("the response is still queued: the lock stays and nothing is reported", emptyList<String>(), events.toList())
        assertTrue(tunnel.hasRequestsInFlight())

        queued.set(0)
        withTimeout(1_000) { done.await() }
        assertEquals(listOf("released request", "done"), events.toList())
        assertFalse(tunnel.hasRequestsInFlight())

        tunnel.disconnect()
        withTimeout(5_000) { run.await() }
        Unit
    }

    @Test
    fun `a send queue that never drains releases the request lock after the flush cap`() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val done = CompletableDeferred<Unit>()
        val tunnel = flushTestClient(events, done) { 1L }
        val run = async(Dispatchers.IO) { tunnel.run() }

        assertEquals("response", nextMessage()["type"]!!.jsonPrimitive.content)
        val sentAt = System.nanoTime()
        withTimeout(TunnelClient.FLUSH_TIMEOUT_MS + 2_000) { done.await() }
        val waitedMs = (System.nanoTime() - sentAt) / 1_000_000

        assertTrue("waited $waitedMs ms", waitedMs >= TunnelClient.FLUSH_TIMEOUT_MS - 300)
        assertEquals(listOf("released request", "done"), events.toList())

        tunnel.disconnect()
        withTimeout(5_000) { run.await() }
        Unit
    }

    /** A relay that sends one request; the client records lock releases and "done" into [events]. */
    private fun flushTestClient(events: MutableList<String>, done: CompletableDeferred<Unit>, queueSize: () -> Long): TunnelClient {
        enqueueRelay { ws ->
            establish(ws)
            ws.send(
                """{"type":"request","requestId":"r1","method":"POST","path":"/mcp","headers":{},""" +
                    """"body":"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"}""",
            )
        }
        val wakeLocks = WakeLocks { tag, _ -> AutoCloseable { events += "released $tag" } }
        return client(
            wakeLocks = wakeLocks,
            onRequestDone = {
                events += "done"
                done.complete(Unit)
            },
            queueSize = { queueSize() },
        )
    }

    @Test
    fun `a request that throws no longer counts as in flight`() = runBlocking {
        enqueueRelay { ws ->
            establish(ws)
            ws.send(
                """{"type":"request","requestId":"r1","method":"POST","path":"/mcp","headers":{},""" +
                    """"body":"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"}""",
            )
        }
        val tunnel = client(handler = JsonRpcHandler { _, _ -> throw IllegalStateException("boom") })
        val run = async(Dispatchers.IO) { tunnel.run() }

        assertEquals(500, nextMessage()["statusCode"]!!.jsonPrimitive.int)
        withTimeout(5_000) { tunnel.awaitRequestsIdle() }

        tunnel.disconnect()
        withTimeout(5_000) { run.await() }
        Unit
    }

    @Test
    fun `a request cancelled with the socket no longer counts as in flight`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        enqueueRelay { ws ->
            establish(ws)
            ws.send(
                """{"type":"request","requestId":"r1","method":"POST","path":"/mcp","headers":{},""" +
                    """"body":"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"}""",
            )
        }
        val tunnel = client(
            handler = JsonRpcHandler { _, _ ->
                started.complete(Unit)
                awaitCancellation()
            },
        )
        val run = async(Dispatchers.IO) { tunnel.run() }
        withTimeout(5_000) { started.await() }

        tunnel.abort("Network changed")
        withTimeout(5_000) { run.await() }
        withTimeout(5_000) { tunnel.awaitRequestsIdle() }
    }

    @Test
    fun `a freeze buys one heartbeat of grace, then a silent server is dropped`() = runBlocking {
        val now = AtomicLong(0)
        val uptime = AtomicLong(0)
        val established = CompletableFuture<String>()
        enqueueRelay { ws -> establish(ws) }
        val tunnel = client(
            clock = { now.get() },
            uptimeClock = { uptime.get() },
            onEstablished = { established.complete(it) },
        )
        val run = async(Dispatchers.IO) { tunnel.run() }
        established.get(5, TimeUnit.SECONDS)

        now.set(76_000)
        uptime.set(76_000)
        assertTrue("first check after the freeze grants grace", tunnel.checkHealth())
        now.set(100_000)
        uptime.set(100_000)
        assertTrue("still within the heartbeat of grace", tunnel.checkHealth())

        // Another freeze, but no ping arrived since the first one: no second grace.
        now.set(106_001)
        uptime.set(200_000)
        assertFalse(tunnel.checkHealth())

        val closed = withTimeout(5_000) { run.await() }
        assertEquals(CloseCodes.GOING_AWAY, closed.code)
        assertTrue(closed.reason, closed.reason.contains("No server ping in 106s"))
    }

    @Test
    fun `abort drops the socket and returns 1001 with the reason`() = runBlocking {
        val established = CompletableFuture<String>()
        enqueueRelay { ws -> establish(ws) }
        val tunnel = client(onEstablished = { established.complete(it) })
        val run = async(Dispatchers.IO) { tunnel.run() }
        established.get(5, TimeUnit.SECONDS)

        tunnel.abort("Network changed")

        assertEquals(CloseResult(CloseCodes.GOING_AWAY, "Network changed"), withTimeout(5_000) { run.await() })
    }

    @Test
    fun `disconnect sends a 1000 close and run returns 1000`() = runBlocking {
        val established = CompletableFuture<String>()
        enqueueRelay { ws -> establish(ws) }
        val tunnel = client(onEstablished = { established.complete(it) })
        val run = async(Dispatchers.IO) { tunnel.run() }
        established.get(5, TimeUnit.SECONDS)

        tunnel.disconnect()

        assertEquals(CloseCodes.NORMAL, serverSawClose.get(5, TimeUnit.SECONDS))
        assertEquals(CloseCodes.NORMAL, withTimeout(5_000) { run.await() }.code)
    }

    @Test
    fun `malformed and unknown messages are skipped`() = runBlocking {
        enqueueRelay { ws ->
            establish(ws)
            ws.send("not json")
            ws.send("""{"type":"mystery"}""")
            ws.send("""{"type":"ping","timestamp":5}""")
        }
        val run = async(Dispatchers.IO) { client().run() }

        val pong = nextMessage()
        assertEquals(5, pong["timestamp"]!!.jsonPrimitive.int)

        serverSocket.get(1, TimeUnit.SECONDS).close(1000, "bye")
        withTimeout(10_000) { run.await() }
        Unit
    }

    @Test
    fun `a repeated request id on one connection runs the handler once and answers both`() = runBlocking {
        val calls = AtomicInteger(0)
        val wakeLocks = RecordingWakeLocks()
        enqueueRelay { ws ->
            establish(ws)
            ws.send(requestFrame("dup-1", 5))
            ws.send(requestFrame("dup-1", 5))
        }
        val tunnel = client(
            handler = JsonRpcHandler { _, _ ->
                calls.incrementAndGet()
                """{"jsonrpc":"2.0","id":5,"result":{"added":1}}"""
            },
            wakeLocks = wakeLocks,
        )
        val run = async(Dispatchers.IO) { tunnel.run() }

        val first = nextMessage()
        val second = nextMessage()
        assertEquals(first, second)
        assertEquals("dup-1", first["requestId"]!!.jsonPrimitive.content)
        assertEquals("""{"jsonrpc":"2.0","id":5,"result":{"added":1}}""", first["body"]!!.jsonPrimitive.content)
        withTimeout(5_000) { tunnel.awaitRequestsIdle() }
        assertEquals(1, calls.get())
        assertEquals(listOf("request", "request"), wakeLocks.acquired.toList())

        tunnel.disconnect()
        withTimeout(5_000) { run.await() }
        Unit
    }

    @Test
    fun `a response lost with the old socket is replayed on the new one without running the tool again`() = runBlocking {
        val calls = AtomicInteger(0)
        val dedupe = RequestDedupe(ElapsedClock.Monotonic)
        enqueueRelay { ws ->
            establish(ws)
            ws.send(requestFrame("re-1", 6))
        }
        enqueueRelay { ws ->
            establish(ws)
            ws.send(requestFrame("re-1", 6))
        }
        lateinit var old: TunnelClient
        val handler = JsonRpcHandler { _, _ ->
            calls.incrementAndGet()
            // The socket goes away after the tool ran, before its response is sent.
            old.disconnect()
            """{"jsonrpc":"2.0","id":6,"result":{"added":1}}"""
        }
        old = client(handler = handler, dedupe = dedupe)

        // run() on this single thread: the handler returns and the send fails before the loop
        // sees the close.
        assertEquals(CloseCodes.NORMAL, withTimeout(10_000) { old.run() }.code)
        assertTrue("the old socket sent no response", received.isEmpty())

        val tunnel = client(handler = handler, dedupe = dedupe)
        val run = async(Dispatchers.IO) { tunnel.run() }

        val response = nextMessage()
        assertEquals("re-1", response["requestId"]!!.jsonPrimitive.content)
        assertEquals(200, response["statusCode"]!!.jsonPrimitive.int)
        assertEquals("""{"jsonrpc":"2.0","id":6,"result":{"added":1}}""", response["body"]!!.jsonPrimitive.content)
        assertEquals(1, calls.get())

        tunnel.disconnect()
        withTimeout(5_000) { run.await() }
        Unit
    }

    @Test
    fun `a duplicate of a cut-off request gets a JSON-RPC error instead of a second run`() = runBlocking {
        val calls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val dedupe = RequestDedupe(ElapsedClock.Monotonic)
        enqueueRelay { ws ->
            establish(ws)
            ws.send(requestFrame("cut-1", 7))
        }
        enqueueRelay { ws ->
            establish(ws)
            ws.send(requestFrame("cut-1", 7))
        }
        val handler = JsonRpcHandler { _, _ ->
            calls.incrementAndGet()
            started.complete(Unit)
            awaitCancellation()
        }
        val old = client(handler = handler, dedupe = dedupe, requestTimeoutMs = 60_000)
        val oldRun = async(Dispatchers.IO) { old.run() }
        withTimeout(5_000) { started.await() }
        old.abort("Push wake while connected")
        withTimeout(5_000) { oldRun.await() }

        val tunnel = client(handler = handler, dedupe = dedupe)
        val run = async(Dispatchers.IO) { tunnel.run() }

        val response = nextMessage()
        assertEquals("cut-1", response["requestId"]!!.jsonPrimitive.content)
        assertEquals(200, response["statusCode"]!!.jsonPrimitive.int)
        val body = Json.parseToJsonElement(response["body"]!!.jsonPrimitive.content).jsonObject
        assertEquals(7, body["id"]!!.jsonPrimitive.int)
        val error = body["error"]!!.jsonObject
        assertEquals(TunnelClient.INTERRUPTED_CODE, error["code"]!!.jsonPrimitive.int)
        assertEquals(TunnelClient.INTERRUPTED_MESSAGE, error["message"]!!.jsonPrimitive.content)
        assertEquals(1, calls.get())

        tunnel.disconnect()
        withTimeout(5_000) { run.await() }
        Unit
    }

    @Test
    fun `a request aborted before its handler started runs once when the relay re-forwards it`() = runBlocking {
        val calls = AtomicInteger(0)
        val dedupe = RequestDedupe(ElapsedClock.Monotonic)
        enqueueRelay { ws ->
            establish(ws)
            ws.send(requestFrame("never-1", 8))
        }
        enqueueRelay { ws ->
            establish(ws)
            ws.send(requestFrame("never-1", 8))
        }
        val handler = JsonRpcHandler { _, _ ->
            calls.incrementAndGet()
            """{"jsonrpc":"2.0","id":8,"result":{"added":1}}"""
        }
        lateinit var old: TunnelClient
        // The abort is queued while the frame is handled, so on this single thread the loop sees it
        // before the request coroutine is ever dispatched.
        old = client(handler = handler, dedupe = dedupe, onRequest = { old.abort("Push wake while connected") })

        assertEquals(CloseCodes.GOING_AWAY, withTimeout(10_000) { old.run() }.code)
        assertEquals("the handler never ran on the old socket", 0, calls.get())
        assertTrue("the old socket sent no response", received.isEmpty())

        val tunnel = client(handler = handler, dedupe = dedupe)
        val run = async(Dispatchers.IO) { tunnel.run() }

        val response = nextMessage()
        assertEquals("never-1", response["requestId"]!!.jsonPrimitive.content)
        assertEquals(200, response["statusCode"]!!.jsonPrimitive.int)
        assertEquals("""{"jsonrpc":"2.0","id":8,"result":{"added":1}}""", response["body"]!!.jsonPrimitive.content)
        assertEquals(1, calls.get())

        tunnel.disconnect()
        withTimeout(5_000) { run.await() }
        Unit
    }

    @Test
    fun `a duplicate waiting on a running original that is cancelled answers -32001 at once`() = runBlocking {
        val calls = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val log = TunnelLog(mirrorToLogcat = false)
        enqueueRelay { ws ->
            establish(ws)
            ws.send(requestFrame("wait-1", 9))
            ws.send(requestFrame("wait-1", 9))
        }
        val tunnel = client(
            handler = JsonRpcHandler { _, _ ->
                calls.incrementAndGet()
                release.await()
                throw kotlinx.coroutines.CancellationException("cut off")
            },
            // Far above the wait below: the duplicate must wake on the original's end, not time out.
            requestTimeoutMs = 60_000,
            log = log,
        )
        val run = async(Dispatchers.IO) { tunnel.run() }

        withTimeout(5_000) { log.entries.first { entries -> entries.any { "is waiting for the original" in it.message } } }
        release.complete(Unit)

        val response = nextMessage()
        assertEquals("wait-1", response["requestId"]!!.jsonPrimitive.content)
        assertEquals(200, response["statusCode"]!!.jsonPrimitive.int)
        val error = Json.parseToJsonElement(response["body"]!!.jsonPrimitive.content).jsonObject["error"]!!.jsonObject
        assertEquals(TunnelClient.INTERRUPTED_CODE, error["code"]!!.jsonPrimitive.int)
        withTimeout(5_000) { tunnel.awaitRequestsIdle() }
        assertEquals(1, calls.get())
        assertTrue("the cut-off original sent nothing", received.isEmpty())

        tunnel.disconnect()
        withTimeout(5_000) { run.await() }
        Unit
    }
}
