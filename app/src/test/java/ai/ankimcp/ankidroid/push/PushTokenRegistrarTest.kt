package ai.ankimcp.ankidroid.push

import ai.ankimcp.ankidroid.auth.CredentialStore
import ai.ankimcp.ankidroid.auth.Credentials
import ai.ankimcp.ankidroid.auth.DeviceFlowClient
import ai.ankimcp.ankidroid.auth.TokenRefresher
import ai.ankimcp.ankidroid.auth.UserInfo
import ai.ankimcp.ankidroid.log.LogEntry
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.settings.InMemoryKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.util.concurrent.TimeUnit

class PushTokenRegistrarTest {
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
    private val expired = Credentials("at-0", "rt-0", "2000-01-01T00:00:00Z", user)

    private fun tokenBody(n: Int) =
        """{"access_token":"at-$n","refresh_token":"rt-$n","expires_in":3600,"user":{"id":"u","email":"a@b.com","tier":"free"}}"""

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

    private fun registrar(store: CredentialStore): PushTokenRegistrar {
        val http = OkHttpClient()
        return PushTokenRegistrar(
            tunnelWsUrl = wsUrl,
            http = http,
            credentialStore = store,
            refresher = TokenRefresher(store, DeviceFlowClient(wsUrl, http)),
            appVersion = "0.1.0",
            log = log,
        )
    }

    private fun takeRequest(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body.readUtf8()) as JsonObject

    @Test
    fun `register PUTs the contract body with a bearer token and accepts 204`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = registrar(InMemoryStore(valid)).register("fcm-token-1")

        assertEquals(PushResult.Ok, result)
        val request = takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/devices/push", request.path)
        assertEquals("Bearer at-0", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals(
            JsonObject(
                mapOf(
                    "clientType" to JsonPrimitive("ankidroid"),
                    "fcmToken" to JsonPrimitive("fcm-token-1"),
                    "platform" to JsonPrimitive("android"),
                    "appVersion" to JsonPrimitive("0.1.0"),
                ),
            ),
            request.json(),
        )
    }

    @Test
    fun `unregister DELETEs the token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = registrar(InMemoryStore(valid)).unregister("fcm-token-1")

        assertEquals(PushResult.Ok, result)
        val request = takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/devices/push", request.path)
        assertEquals("Bearer at-0", request.getHeader("Authorization"))
        assertEquals(JsonObject(mapOf("fcmToken" to JsonPrimitive("fcm-token-1"))), request.json())
    }

    @Test
    fun `401 refreshes the token once and retries with the new one`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(tokenBody(1)))
        server.enqueue(MockResponse().setResponseCode(204))
        val store = InMemoryStore(valid)

        val result = registrar(store).register("fcm-token-1")

        assertEquals(PushResult.Ok, result)
        assertEquals("Bearer at-0", takeRequest().getHeader("Authorization"))
        val refresh = takeRequest()
        assertEquals("/auth/token", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("refresh_token=rt-0"))
        val retry = takeRequest()
        assertEquals("PUT", retry.method)
        assertEquals("Bearer at-1", retry.getHeader("Authorization"))
        assertEquals("at-1", store.load()!!.accessToken)
    }

    @Test
    fun `a second 401 gives up until the next trigger`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(tokenBody(1)))
        server.enqueue(MockResponse().setResponseCode(401))

        val result = registrar(InMemoryStore(valid)).register("fcm-token-1")

        assertTrue(result.toString(), result is PushResult.Rejected)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `401 with a dead refresh token reports signed out`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        val store = InMemoryStore(valid)

        val result = registrar(store).register("fcm-token-1")

        assertEquals(PushResult.SignedOut, result)
        assertNull(store.load())
    }

    @Test
    fun `an expired access token is refreshed before the call`() = runBlocking {
        server.enqueue(MockResponse().setBody(tokenBody(1)))
        server.enqueue(MockResponse().setResponseCode(204))

        val result = registrar(InMemoryStore(expired)).register("fcm-token-1")

        assertEquals(PushResult.Ok, result)
        assertEquals("/auth/token", takeRequest().path)
        assertEquals("Bearer at-1", takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `400 is logged, not retried, and the token never reaches the log`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Invalid platform"}"""))

        val result = registrar(InMemoryStore(valid)).register("secret-fcm-token")

        assertEquals(PushResult.Rejected("HTTP 400: Invalid platform"), result)
        assertEquals(1, server.requestCount)
        val warning = log.entries.value.single()
        assertEquals(LogEntry.Level.WARN, warning.level)
        assertTrue(warning.message, warning.message.contains("HTTP 400: Invalid platform"))
        assertFalse(log.entries.value.any { it.message.contains("secret-fcm-token") })
    }

    @Test
    fun `5xx asks to retry later`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))

        val result = registrar(InMemoryStore(valid)).register("fcm-token-1")

        assertEquals(PushResult.RetryLater("Server error (HTTP 503)"), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `network failure asks to retry later`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = registrar(InMemoryStore(valid)).register("fcm-token-1")

        assertTrue(result.toString(), result is PushResult.RetryLater)
    }

    @Test
    fun `signed out makes no request`() = runBlocking {
        val result = registrar(InMemoryStore(null)).register("fcm-token-1")

        assertEquals(PushResult.SignedOut, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `logout sends the DELETE while the credentials are still there, then clears them`() = runBlocking {
        val store = InMemoryStore(valid)
        var credentialsAtDelete: Credentials? = null
        var deleteAuthorization: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "DELETE") {
                    credentialsAtDelete = store.current.value
                    deleteAuthorization = request.getHeader("Authorization")
                }
                return MockResponse().setResponseCode(204)
            }
        }
        val pushStore = PushRegistrationStore(InMemoryKeyValueStore())
        pushStore.saveRegistered("fcm-token-1", 1_000L, "0.1.0")
        val coordinator = PushCoordinator(
            messaging = object : PushMessaging {
                override val isAvailable = true

                override suspend fun token() = "fcm-token-1"

                override suspend fun deleteToken() = Unit

                override fun setAutoInitEnabled(enabled: Boolean) = Unit
            },
            registrar = registrar(store),
            store = pushStore,
            isSignedIn = { store.load() != null },
            appVersion = "0.1.0",
            work = PushWorkScheduler.None,
            log = log,
        )

        coordinator.logout { store.clear() }

        assertEquals(valid, credentialsAtDelete)
        assertEquals("Bearer at-0", deleteAuthorization)
        assertNull(store.load())
        assertNull(pushStore.state.value.token)
    }
}
