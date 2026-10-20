package ai.ankimcp.ankidroid.billing

import ai.ankimcp.ankidroid.auth.CredentialStore
import ai.ankimcp.ankidroid.auth.Credentials
import ai.ankimcp.ankidroid.auth.DeviceFlowClient
import ai.ankimcp.ankidroid.auth.TokenRefresher
import ai.ankimcp.ankidroid.auth.UserInfo
import ai.ankimcp.ankidroid.log.TunnelLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class BillingApiTest {
    private class InMemoryStore(initial: Credentials?) : CredentialStore {
        private val state = MutableStateFlow(initial)
        override val current: StateFlow<Credentials?> = state.asStateFlow()

        override fun save(credentials: Credentials) {
            synchronized(this) { state.value = credentials }
        }

        override fun clear() {
            synchronized(this) { state.value = null }
        }
    }

    private lateinit var server: MockWebServer
    private val log = TunnelLog(mirrorToLogcat = false)
    private val user = UserInfo("u", "a@b.com", "free")
    private val valid = Credentials("at-0", "rt-0", "2099-01-01T00:00:00Z", user)
    private val hexId = "ab".repeat(32)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val wsUrl get() = server.url("/").toString().replaceFirst("http://", "ws://").trimEnd('/')

    private fun api(store: CredentialStore = InMemoryStore(valid)): BillingApi {
        val http = OkHttpClient()
        return BillingApi(wsUrl, http, store, TokenRefresher(store, DeviceFlowClient(wsUrl, http)), log)
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    private fun statusOf(body: String): PlanStatus {
        server.enqueue(json(200, body))
        val result = runBlocking { api().status() }
        return (result as BillingApiResult.Ok).value
    }

    // /prepare

    @Test
    fun `prepare posts an empty body with the bearer token and parses new`() {
        server.enqueue(json(200, """{"obfuscatedAccountId":"$hexId","mode":"new"}"""))
        val result = runBlocking { api().prepare() }
        assertEquals(BillingApiResult.Ok(PrepareResponse(hexId, PrepareMode.New, null)), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/billing/google-play/prepare", request.path)
        assertEquals("Bearer at-0", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `prepare parses replace with the current product and blocked`() {
        server.enqueue(json(200, """{"obfuscatedAccountId":"$hexId","mode":"replace","currentProductId":"ankimcp_plus"}"""))
        server.enqueue(json(200, """{"obfuscatedAccountId":"$hexId","mode":"blocked"}"""))
        assertEquals(
            BillingApiResult.Ok(PrepareResponse(hexId, PrepareMode.Replace, "ankimcp_plus")),
            runBlocking { api().prepare() },
        )
        assertEquals(BillingApiResult.Ok(PrepareResponse(hexId, PrepareMode.Blocked, null)), runBlocking { api().prepare() })
    }

    @Test
    fun `prepare rejects a malformed id or an unknown mode`() {
        server.enqueue(json(200, """{"obfuscatedAccountId":"not-hex","mode":"new"}"""))
        server.enqueue(json(200, """{"obfuscatedAccountId":"$hexId","mode":"later"}"""))
        assertTrue(runBlocking { api().prepare() } is BillingApiResult.Rejected)
        assertTrue(runBlocking { api().prepare() } is BillingApiResult.Rejected)
    }

    @Test
    fun `signed out makes no request`() {
        assertEquals(BillingApiResult.SignedOut, runBlocking { api(InMemoryStore(null)).prepare() })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a 401 refreshes the access token once and retries`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/auth/token" -> json(
                    200,
                    """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"user":{"id":"u","email":"a@b.com","tier":"free"}}""",
                )
                request.getHeader("Authorization") == "Bearer at-1" -> json(200, """{"tier":"pro"}""")
                else -> json(401, """{"error":"invalid_token"}""")
            }
        }
        assertEquals(BillingApiResult.Ok("pro"), runBlocking { api().registerPurchase("purchase-token-1") })
    }

    // /purchase

    @Test
    fun `purchase sends only the token and returns the tier`() {
        server.enqueue(json(200, """{"tier":"plus"}"""))
        assertEquals(BillingApiResult.Ok("plus"), runBlocking { api().registerPurchase("purchase-token-1") })
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/billing/google-play/purchase", request.path)
        assertEquals("""{"purchaseToken":"purchase-token-1"}""", request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `purchase maps 403 409 503 400 and 500`() {
        server.enqueue(json(403, """{"error":"PURCHASE_NOT_OWNED"}"""))
        server.enqueue(json(409, """{"error":"ALREADY_SUBSCRIBED"}"""))
        server.enqueue(json(503, """{"error":"billing disabled"}"""))
        server.enqueue(json(400, """{"message":"purchaseToken is required"}"""))
        server.enqueue(json(500, """{"error":"boom"}"""))
        val api = api()
        assertEquals(BillingApiResult.NotOwned, runBlocking { api.registerPurchase("t") })
        assertEquals(BillingApiResult.AlreadySubscribed, runBlocking { api.registerPurchase("t") })
        assertEquals(BillingApiResult.BillingDisabled, runBlocking { api.registerPurchase("t") })
        assertEquals(BillingApiResult.Rejected("HTTP 400: purchaseToken is required"), runBlocking { api.registerPurchase("t") })
        assertTrue(runBlocking { api.registerPurchase("t") } is BillingApiResult.RetryLater)
    }

    @Test
    fun `a network error is retry later`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(runBlocking { api().registerPurchase("t") } is BillingApiResult.RetryLater)
    }

    @Test
    fun `a 200 without a tier is rejected`() {
        server.enqueue(json(200, """{}"""))
        assertTrue(runBlocking { api().registerPurchase("t") } is BillingApiResult.Rejected)
    }

    @Test
    fun `the log never contains the purchase or access token`() {
        server.enqueue(json(500, "{}"))
        server.enqueue(json(403, "{}"))
        runBlocking {
            api().registerPurchase("secret-purchase-token")
            api().registerPurchase("secret-purchase-token")
        }
        val text = log.entries.value.joinToString("\n") { it.message }
        assertFalse(text.contains("secret-purchase-token"))
        assertFalse(text.contains("at-0"))
    }

    // /billing/status

    @Test
    fun `status is a GET with the bearer token`() {
        statusOf("""{"tier":"free","status":"free","provider":"none","currentPeriodEndsAt":null,"cancelAtPeriodEnd":false,"productId":null}""")
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/billing/status", request.path)
        assertEquals("Bearer at-0", request.getHeader("Authorization"))
    }

    @Test
    fun `free account`() {
        assertEquals(
            PlanStatus("free", PlanStatusKind.Free, PlanProvider.None, null, false, null),
            statusOf("""{"tier":"free","status":"free","provider":"none","currentPeriodEndsAt":null,"cancelAtPeriodEnd":false,"productId":null}"""),
        )
    }

    @Test
    fun `admin grant`() {
        assertEquals(
            PlanStatus("pro", PlanStatusKind.Active, PlanProvider.Admin, null, false, null),
            statusOf("""{"tier":"pro","status":"active","provider":"admin","currentPeriodEndsAt":null,"cancelAtPeriodEnd":false,"productId":null}"""),
        )
    }

    @Test
    fun `google play active, grace and canceled`() {
        val end = Instant.parse("2026-11-18T10:00:00Z")
        assertEquals(
            PlanStatus("plus", PlanStatusKind.Active, PlanProvider.GooglePlay, end, false, "ankimcp_plus"),
            statusOf(
                """{"tier":"plus","status":"active","provider":"google_play","currentPeriodEndsAt":"2026-11-18T10:00:00Z","cancelAtPeriodEnd":false,"productId":"ankimcp_plus"}""",
            ),
        )
        assertEquals(
            PlanStatus("pro", PlanStatusKind.Grace, PlanProvider.GooglePlay, end, true, "ankimcp_pro"),
            statusOf(
                """{"tier":"pro","status":"grace","provider":"google_play","currentPeriodEndsAt":"2026-11-18T10:00:00.000Z","cancelAtPeriodEnd":true,"productId":"ankimcp_pro"}""",
            ),
        )
        assertEquals(
            PlanStatus("pro", PlanStatusKind.Canceled, PlanProvider.GooglePlay, end, true, "ankimcp_pro"),
            statusOf(
                """{"tier":"pro","status":"canceled","provider":"google_play","currentPeriodEndsAt":"2026-11-18T10:00:00Z","cancelAtPeriodEnd":true,"productId":"ankimcp_pro"}""",
            ),
        )
    }

    @Test
    fun `google play without a product id when billing is off on that environment`() {
        assertNull(
            statusOf(
                """{"tier":"plus","status":"active","provider":"google_play","currentPeriodEndsAt":"2026-11-18T10:00:00Z","cancelAtPeriodEnd":false,"productId":null}""",
            ).productId,
        )
    }

    @Test
    fun `polar and paddle, active and canceled`() {
        val polar = statusOf(
            """{"tier":"plus","status":"active","provider":"polar","currentPeriodEndsAt":"2026-11-18T10:00:00Z","cancelAtPeriodEnd":false,"productId":null}""",
        )
        assertEquals(PlanProvider.Polar to PlanStatusKind.Active, polar.provider to polar.status)
        val paddle = statusOf(
            """{"tier":"pro","status":"canceled","provider":"paddle","currentPeriodEndsAt":"2026-11-18T10:00:00Z","cancelAtPeriodEnd":true,"productId":null}""",
        )
        assertEquals(PlanProvider.Paddle to PlanStatusKind.Canceled, paddle.provider to paddle.status)
        assertTrue(paddle.cancelAtPeriodEnd)
    }

    @Test
    fun `dates and unknown values parse defensively`() {
        val status = statusOf(
            """{"tier":"team","status":"paused","provider":"stripe","currentPeriodEndsAt":"next month","productId":""}""",
        )
        assertEquals(PlanStatus("team", PlanStatusKind.Unknown, PlanProvider.Unknown, null, false, null), status)
    }

    @Test
    fun `status without a tier is rejected`() {
        server.enqueue(json(200, """{"status":"free"}"""))
        assertTrue(runBlocking { api().status() } is BillingApiResult.Rejected)
    }

    @Test
    fun `status 404 405 and 501 mean billing is not available on this relay`() {
        for (code in listOf(404, 405, 501)) {
            server.enqueue(json(code, """{"message":"Not Found"}"""))
            assertEquals(BillingApiResult.NotAvailable, runBlocking { api().status() })
        }
        val warnings = log.entries.value.filter { it.level == ai.ankimcp.ankidroid.log.LogEntry.Level.WARN }.map { it.message }
        assertEquals(
            listOf(404, 405, 501).map { "Billing status not available on this relay (HTTP $it)" },
            warnings,
        )
    }

    @Test
    fun `404 from a google-play call stays an error`() {
        server.enqueue(json(404, "{}"))
        assertTrue(runBlocking { api().prepare() } is BillingApiResult.Rejected)
    }

    @Test
    fun `status 500 is retry later and 503 is not billing disabled`() {
        server.enqueue(json(500, "{}"))
        server.enqueue(json(503, "{}"))
        assertTrue(runBlocking { api().status() } is BillingApiResult.RetryLater)
        assertTrue(runBlocking { api().status() } is BillingApiResult.RetryLater)
    }

    @Test
    fun `status 401 after a refresh is rejected`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/auth/token" -> json(
                    200,
                    """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"user":{"id":"u","email":"a@b.com","tier":"free"}}""",
                )
                else -> json(401, "{}")
            }
        }
        assertEquals(BillingApiResult.Rejected("Unauthorized (HTTP 401)"), runBlocking { api().status() })
    }
}
