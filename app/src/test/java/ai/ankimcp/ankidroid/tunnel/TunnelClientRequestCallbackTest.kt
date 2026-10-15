package ai.ankimcp.ankidroid.tunnel

import ai.ankimcp.ankidroid.log.TunnelLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The `onRequest` hook that drives the idle timer: once per `request` frame, never for pings. */
class TunnelClientRequestCallbackTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val received = LinkedBlockingQueue<String>()
    private val serverSocket = CompletableFuture<WebSocket>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `onRequest fires for each request frame and not for pings`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.complete(webSocket)
                        webSocket.send("""{"type":"tunnel_established","url":"https://tunnel.ankimcp.ai/mcp"}""")
                        webSocket.send("""{"type":"ping","timestamp":1}""")
                        webSocket.send(request("r1"))
                        webSocket.send(request("r2"))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received.add(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                    }
                },
            ),
        )
        val requests = AtomicInteger()
        val client = TunnelClient(
            http = http,
            url = server.url("/").toString().replaceFirst("http://", "ws://"),
            accessToken = "token",
            clientVersion = "0.1.0",
            handler = JsonRpcHandler { _, _ -> """{"jsonrpc":"2.0","id":1,"result":{}}""" },
            log = TunnelLog(mirrorToLogcat = false),
            clock = ElapsedClock.Monotonic,
            onRequest = { requests.incrementAndGet() },
        )
        val run = async(Dispatchers.IO) { client.run() }

        // pong + two responses
        repeat(3) { assertNotNull(received.poll(5, TimeUnit.SECONDS)) }
        assertEquals(2, requests.get())

        serverSocket.get(1, TimeUnit.SECONDS).close(CloseCodes.NORMAL, "bye")
        withTimeout(10_000) { run.await() }
        Unit
    }

    private fun request(id: String) =
        """{"type":"request","requestId":"$id","method":"POST","path":"/mcp","headers":{},""" +
            """"body":"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"}"""
}
