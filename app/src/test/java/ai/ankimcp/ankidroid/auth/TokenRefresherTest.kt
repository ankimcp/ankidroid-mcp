package ai.ankimcp.ankidroid.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class TokenRefresherTest {
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
    private val user = UserInfo("u", "a@b.com", "free")
    private val old = Credentials("at-0", "rt-0", "2000-01-01T00:00:00Z", user)

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

    private fun refresher(store: CredentialStore) = TokenRefresher(
        store,
        DeviceFlowClient(server.url("/").toString().replaceFirst("http://", "ws://").trimEnd('/'), OkHttpClient()),
    )

    @Test
    fun `concurrent refreshes of the same token hit the server once`() = runBlocking {
        server.enqueue(MockResponse().setBody(tokenBody(1)).setHeadersDelay(200, TimeUnit.MILLISECONDS))
        val store = InMemoryStore(old)
        val refresher = refresher(store)

        val a = async(Dispatchers.Default) { refresher.refresh(old) }
        val b = async(Dispatchers.Default) { refresher.refresh(old) }
        val first = a.await()
        val second = b.await()

        val stored = store.load()!!
        assertEquals("at-1", stored.accessToken)
        assertEquals(RefreshOutcome.Refreshed(stored), first)
        assertEquals(RefreshOutcome.Refreshed(stored), second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `credentials already refreshed by someone else are reused without a request`() = runBlocking {
        val fresh = Credentials("at-9", "rt-9", "2099-01-01T00:00:00Z", user)
        val store = InMemoryStore(fresh)

        val outcome = refresher(store).refresh(old)

        assertEquals(RefreshOutcome.Refreshed(fresh), outcome)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `logout during the refresh is not undone`() = runBlocking {
        server.enqueue(MockResponse().setBody(tokenBody(1)).setHeadersDelay(300, TimeUnit.MILLISECONDS))
        val store = InMemoryStore(old)
        val refresher = refresher(store)

        val outcome = async(Dispatchers.Default) { refresher.refresh(old) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        store.clear()

        assertEquals(RefreshOutcome.SignedOut, outcome.await())
        assertNull(store.load())
    }

    @Test
    fun `signed out before refreshing returns SignedOut without a request`() = runBlocking {
        val outcome = refresher(InMemoryStore(null)).refresh(old)

        assertEquals(RefreshOutcome.SignedOut, outcome)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `invalid_grant clears credentials`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        val store = InMemoryStore(old)

        assertEquals(RefreshOutcome.ReloginRequired, refresher(store).refresh(old))
        assertNull(store.load())
    }

    @Test
    fun `invalid_grant does not wipe a sign-in made during the refresh`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                .setHeadersDelay(300, TimeUnit.MILLISECONDS),
        )
        val store = InMemoryStore(old)
        val refresher = refresher(store)
        val signedInAgain = Credentials("at-new", "rt-new", "2099-01-01T00:00:00Z", user)

        val outcome = async(Dispatchers.Default) { refresher.refresh(old) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        store.save(signedInAgain)

        assertEquals(RefreshOutcome.Refreshed(signedInAgain), outcome.await())
        assertEquals(signedInAgain, store.load())
    }

    @Test
    fun `transient failure keeps the stored credentials`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        val store = InMemoryStore(old)

        val outcome = refresher(store).refresh(old)

        assertEquals(old, (outcome as RefreshOutcome.Failed).previous)
        assertEquals(old, store.load())
    }
}
