package ai.ankimcp.ankidroid.auth

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class DeviceFlowClientTest {
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Duration>()
    private val timeSource = TestTimeSource()
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): DeviceFlowClient = DeviceFlowClient(
        tunnelWsUrl = server.url("/").toString().replaceFirst("http://", "ws://").trimEnd('/'),
        http = OkHttpClient(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        timeSource = timeSource,
        sleep = {
            sleeps += it
            timeSource += it
        },
    )

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val tokenBody = """
        {"access_token":"at-1","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-1",
         "user":{"id":"u-1","email":"a@b.com","tier":"plus"}}
    """.trimIndent()

    @Test
    fun `requestDeviceCode posts client id and scope and parses response`() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://auth/x",
                   "verification_uri_complete":"https://auth/x?user_code=ABCD-1234","expires_in":600,"interval":5}""",
            ),
        )

        val code = client().requestDeviceCode()

        assertEquals("dc", code.deviceCode)
        assertEquals("ABCD-1234", code.userCode)
        assertEquals("https://auth/x?user_code=ABCD-1234", code.verificationUriComplete)
        assertEquals(600L, code.expiresInSeconds)
        assertEquals(5L, code.intervalSeconds)
        val request = server.takeRequest()
        assertEquals("/auth/device", request.path)
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("client_id=ankimcp-cli"))
        assertTrue(body, body.contains("scope=openid%20offline_access"))
    }

    @Test
    fun `requestDeviceCode defaults interval and tolerates missing complete uri`() = runBlocking {
        server.enqueue(json(200, """{"device_code":"dc","user_code":"U","verification_uri":"https://v","expires_in":600}"""))

        val code = client().requestDeviceCode()

        assertEquals(5L, code.intervalSeconds)
        assertNull(code.verificationUriComplete)
    }

    @Test
    fun `pollForToken keeps polling while authorization is pending, then succeeds`() = runBlocking {
        server.enqueue(json(400, """{"error":"authorization_pending"}"""))
        server.enqueue(json(400, """{"error":"authorization_pending"}"""))
        server.enqueue(json(200, tokenBody))

        val credentials = client().pollForToken("dc", intervalSeconds = 5, expiresInSeconds = 600)

        assertEquals("at-1", credentials.accessToken)
        assertEquals("rt-1", credentials.refreshToken)
        assertEquals(UserInfo("u-1", "a@b.com", "plus"), credentials.user)
        assertEquals(now.plusSeconds(3600), Instant.parse(credentials.expiresAt))
        assertEquals(listOf(5.seconds, 5.seconds, 5.seconds), sleeps)
        val first = server.takeRequest()
        assertEquals("/auth/token", first.path)
        val body = first.body.readUtf8()
        assertTrue(body, body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
        assertTrue(body, body.contains("device_code=dc"))
        assertTrue(body, body.contains("client_id=ankimcp-cli"))
    }

    @Test
    fun `slow_down adds five seconds to the interval`() = runBlocking {
        server.enqueue(json(400, """{"error":"slow_down"}"""))
        server.enqueue(json(400, """{"error":"slow_down"}"""))
        server.enqueue(json(200, tokenBody))

        client().pollForToken("dc", intervalSeconds = 5, expiresInSeconds = 600)

        assertEquals(listOf(5.seconds, 10.seconds, 15.seconds), sleeps)
    }

    @Test
    fun `429 while polling backs off like slow_down and keeps polling`() = runBlocking {
        server.enqueue(json(429, """{"error":"too_many_requests"}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))
        server.enqueue(json(200, tokenBody))

        val credentials = client().pollForToken("dc", intervalSeconds = 5, expiresInSeconds = 600)

        assertEquals("at-1", credentials.accessToken)
        assertEquals(listOf(5.seconds, 10.seconds, 15.seconds), sleeps)
    }

    @Test
    fun `503 without an OAuth error is retried with backoff`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>Service Unavailable</html>"))
        server.enqueue(json(503, """{"message":"overloaded"}"""))
        server.enqueue(json(200, tokenBody))

        val credentials = client().pollForToken("dc", intervalSeconds = 5, expiresInSeconds = 600)

        assertEquals("at-1", credentials.accessToken)
        assertEquals(listOf(5.seconds, 10.seconds, 15.seconds), sleeps)
    }

    @Test
    fun `Retry-After is respected for the next poll`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody("{}"))
        server.enqueue(json(400, """{"error":"authorization_pending"}"""))
        server.enqueue(json(200, tokenBody))

        client().pollForToken("dc", intervalSeconds = 5, expiresInSeconds = 600)

        // 30 s once (Retry-After), then the interval raised by 5 s like slow_down.
        assertEquals(listOf(5.seconds, 30.seconds, 10.seconds), sleeps)
    }

    @Test
    fun `Retry-After seconds past the deadline fail with expired_token without sleeping`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3600").setBody("{}"))
        server.enqueue(json(200, tokenBody))

        val error = assertThrows(AuthException::class.java) {
            runBlocking { client().pollForToken("dc", intervalSeconds = 5, expiresInSeconds = 600) }
        }

        assertEquals("expired_token", error.errorCode)
        assertEquals(listOf(5.seconds), sleeps)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Retry-After HTTP-date past the deadline fails with expired_token without sleeping`() {
        // One day after the fixed clock's "now".
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Retry-After", "Fri, 02 Jan 2026 00:00:00 GMT").setBody("{}"),
        )
        server.enqueue(json(200, tokenBody))

        val error = assertThrows(AuthException::class.java) {
            runBlocking { client().pollForToken("dc", intervalSeconds = 5, expiresInSeconds = 600) }
        }

        assertEquals("expired_token", error.errorCode)
        assertEquals(listOf(5.seconds), sleeps)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `5xx with an OAuth error is still terminal`() {
        server.enqueue(json(500, """{"error":"server_error","error_description":"boom"}"""))

        val error = assertThrows(AuthException::class.java) {
            runBlocking { client().pollForToken("dc", intervalSeconds = 5, expiresInSeconds = 600) }
        }

        assertEquals("server_error", error.errorCode)
        assertFalse(error.transient)
    }

    @Test
    fun `transport errors while polling are retried`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(json(200, tokenBody))

        val credentials = client().pollForToken("dc", intervalSeconds = 5, expiresInSeconds = 600)

        assertEquals("at-1", credentials.accessToken)
        assertEquals(listOf(5.seconds, 5.seconds), sleeps)
    }

    @Test
    fun `parseRetryAfter accepts delta-seconds and HTTP dates`() {
        assertEquals(120L, DeviceFlowClient.parseRetryAfter("120", now))
        assertEquals(90L, DeviceFlowClient.parseRetryAfter("Thu, 01 Jan 2026 00:01:30 GMT", now))
        assertEquals(0L, DeviceFlowClient.parseRetryAfter("Wed, 31 Dec 2025 23:00:00 GMT", now))
        assertNull(DeviceFlowClient.parseRetryAfter("soon", now))
        assertNull(DeviceFlowClient.parseRetryAfter(null, now))
    }

    @Test
    fun `invalid_grant while polling is terminal`() {
        server.enqueue(json(400, """{"error":"invalid_grant","error_description":"Code is not valid"}"""))
        server.enqueue(json(200, tokenBody))

        val error = assertThrows(AuthException::class.java) {
            runBlocking { client().pollForToken("dc", intervalSeconds = 5, expiresInSeconds = 600) }
        }

        assertEquals("invalid_grant", error.errorCode)
        assertEquals("Code is not valid", error.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `polling gives up with expired_token once expires_in elapses`() {
        repeat(5) { server.enqueue(json(400, """{"error":"authorization_pending"}""")) }

        val error = assertThrows(AuthException::class.java) {
            runBlocking { client().pollForToken("dc", intervalSeconds = 5, expiresInSeconds = 10) }
        }

        assertEquals("expired_token", error.errorCode)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `refresh sends refresh_token grant and returns new credentials`() = runBlocking {
        server.enqueue(json(200, tokenBody))

        val credentials = client().refresh("rt-0")

        assertEquals("at-1", credentials.accessToken)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("grant_type=refresh_token"))
        assertTrue(body, body.contains("refresh_token=rt-0"))
    }

    @Test
    fun `refresh surfaces invalid_grant`() {
        server.enqueue(json(400, """{"error":"invalid_grant","error_description":"Token is not active"}"""))

        val error = assertThrows(AuthException::class.java) { runBlocking { client().refresh("rt-0") } }

        assertEquals("invalid_grant", error.errorCode)
    }

    @Test
    fun `token response without user is malformed`() {
        server.enqueue(json(200, """{"access_token":"a","refresh_token":"r","expires_in":60}"""))

        val error = assertThrows(AuthException::class.java) { runBlocking { client().refresh("rt") } }

        assertEquals("invalid_response", error.errorCode)
    }

    @Test
    fun `non-JSON error body has no error code`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))

        val error = assertThrows(AuthException::class.java) { runBlocking { client().refresh("rt") } }

        assertNull(error.errorCode)
        assertTrue(error.transient)
    }

    @Test
    fun `ws url maps to http url by scheme swap`() {
        assertEquals("https://tunnel.ankimcp.ai", DeviceFlowClient.wsUrlToHttp("wss://tunnel.ankimcp.ai"))
        assertEquals("http://10.0.2.2:3004/x", DeviceFlowClient.wsUrlToHttp("ws://10.0.2.2:3004/x"))
        assertThrows(IllegalArgumentException::class.java) { DeviceFlowClient.wsUrlToHttp("https://x") }
    }
}
