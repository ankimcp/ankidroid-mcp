package ai.ankimcp.ankidroid.local

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Real sockets on loopback: NanoHTTPD runs on the JVM as on Android. */
class NanoHttpServerEngineTest {
    private val engine = NanoHttpServerEngine()
    private val requests = CopyOnWriteArrayList<HttpRequest>()
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private var port = freePort()
    private val base get() = "http://127.0.0.1:$port"

    /** [freePort] closes its probe socket before we bind, so another process can take it; retry once on a fresh port. */
    private fun startEngine(screen: (HttpRequest) -> HttpResponse? = { null }, handler: suspend (HttpRequest) -> HttpResponse) {
        try {
            engine.start("127.0.0.1", port, screen, handler)
        } catch (_: BindException) {
            port = freePort()
            engine.start("127.0.0.1", port, screen, handler)
        }
    }

    private fun rawRequest(text: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(text.toByteArray())
        socket.getInputStream().readBytes().toString(Charsets.US_ASCII)
    }

    @After
    fun tearDown() {
        engine.stop()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun startEcho() {
        startEngine { request ->
            requests += request
            when (request.method) {
                "POST" -> HttpResponse(200, "application/json", request.body.toString(Charsets.UTF_8), mapOf("X-Test" to "1"))
                else -> HttpResponse(405, "application/json", "{}", mapOf("Allow" to "POST"))
            }
        }
    }

    @Test
    fun `serves a POST body and response headers over a real socket, keeping the connection`() {
        startEcho()
        val body = """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"name":"Deck ü"}}"""

        repeat(2) {
            http.newCall(
                Request.Builder().url("$base/mcp?x=1").header("Authorization", "Bearer abc")
                    .post(body.toRequestBody("application/json".toMediaType())).build(),
            ).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("application/json", response.header("Content-Type"))
                assertEquals("1", response.header("X-Test"))
                assertEquals(body, response.body!!.string())
            }
        }

        val request = requests.first()
        assertEquals("/mcp", request.path)
        assertEquals("Bearer abc", request.header("Authorization"))
        assertEquals("127.0.0.1:$port", request.header("host"))
        assertNull(request.headers["remote-addr"])
        assertEquals(1, http.connectionPool.connectionCount())
    }

    @Test
    fun `GET gets the handler's 405 and a 202 has no body`() {
        startEngine { request ->
            if (request.method == "GET") HttpResponse(405, "application/json", "{}", mapOf("Allow" to "POST")) else HttpResponse(202)
        }

        http.newCall(Request.Builder().url("$base/mcp").get().build()).execute().use { response ->
            assertEquals(405, response.code)
            assertEquals("POST", response.header("Allow"))
        }
        http.newCall(Request.Builder().url("$base/mcp").post("{}".toRequestBody()).build()).execute().use { response ->
            assertEquals(202, response.code)
            assertNull(response.header("Content-Type"))
            assertEquals("", response.body!!.string())
        }
    }

    @Test
    fun `a body above the limit is refused unread and the connection is closed`() {
        startEcho()
        val reply = rawRequest("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: ${HttpServerEngine.MAX_BODY_BYTES + 1}\r\n\r\n")
        assertTrue(reply, reply.startsWith("HTTP/1.1 200"))
        assertTrue(reply, reply.contains("connection: close", ignoreCase = true))
        assertEquals(HttpRequest.BodyProblem.TooLarge, requests.single().bodyProblem)
        assertEquals(0, requests.single().body.size)
    }

    private fun startRefusingUnauthenticated() {
        startEngine(
            screen = { request -> if (request.header("authorization") == null) HttpResponse(403, "application/json", "{}") else null },
        ) { request ->
            requests += request
            HttpResponse(200)
        }
    }

    @Test
    fun `a request refused on its headers is answered without reading the body`() {
        startRefusingUnauthenticated()

        // Announces a body it never sends: if the engine waited for it, this would time out.
        val reply = rawRequest("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 500000\r\n\r\n")

        assertTrue(reply, reply.startsWith("HTTP/1.1 403"))
        assertTrue(reply, reply.contains("connection: close", ignoreCase = true))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a refused request whose body arrives after the headers still gets its 403, not a reset`() {
        startRefusingUnauthenticated()
        // Bigger than NanoHTTPD's 8 KB header buffer and sent separately, so it is still unread
        // in the socket when the 403 is written; only the drain keeps the close from being a RST.
        val body = ByteArray(20 * 1024) { 'x'.code.toByte() }

        repeat(5) {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val out = socket.getOutputStream()
                out.write("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                out.flush()
                Thread.sleep(100)
                out.write(body)
                out.flush()
                val reply = socket.getInputStream().readBytes().toString(Charsets.US_ASCII)
                assertTrue(reply, reply.startsWith("HTTP/1.1 403"))
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `body framing rules`() {
        assertNull(NanoHttpServerEngine.bodyProblem(mapOf("content-length" to "10")))
        assertNull(NanoHttpServerEngine.bodyProblem(emptyMap()))
        assertEquals(HttpRequest.BodyProblem.LengthRequired, NanoHttpServerEngine.bodyProblem(mapOf("transfer-encoding" to "chunked")))
        assertEquals(HttpRequest.BodyProblem.LengthRequired, NanoHttpServerEngine.bodyProblem(mapOf("content-length" to "x")))
        assertEquals(HttpRequest.BodyProblem.TooLarge, NanoHttpServerEngine.bodyProblem(mapOf("content-length" to "2000000")))
    }

    @Test
    fun `a handler exception becomes 500 instead of killing the connection thread`() {
        startEngine { throw IllegalStateException("boom") }

        http.newCall(Request.Builder().url("$base/mcp").post("{}".toRequestBody()).build()).execute().use { response ->
            assertEquals(500, response.code)
        }
    }

    @Test
    fun `stop frees the port and a second bind on a taken port throws`() {
        startEcho()
        val other = NanoHttpServerEngine()
        try {
            other.start("127.0.0.1", port) { HttpResponse(200) }
            fail("expected the bind to fail")
        } catch (_: IOException) {
            // expected: port in use
        }

        engine.stop()
        engine.stop()
        other.start("127.0.0.1", port) { HttpResponse(204) }
        other.stop()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
