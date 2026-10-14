package ai.ankimcp.ankidroid.tunnel

import ai.ankimcp.ankidroid.auth.CredentialStore
import ai.ankimcp.ankidroid.auth.Credentials
import ai.ankimcp.ankidroid.auth.DeviceFlowClient
import ai.ankimcp.ankidroid.auth.TokenRefresher
import ai.ankimcp.ankidroid.auth.UserInfo
import ai.ankimcp.ankidroid.log.TunnelLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Idle-stop race and the kinds of Stopped state; the reconnect machinery is in TunnelConnectionTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelConnectionStopTest {
    private class InMemoryCredentials(initial: Credentials?) : CredentialStore {
        private val state = MutableStateFlow(initial)
        override val current: StateFlow<Credentials?> = state.asStateFlow()

        override fun save(credentials: Credentials) {
            state.value = credentials
        }

        override fun clear() {
            state.value = null
        }
    }

    private class Session(
        private val onEstablished: (String) -> Unit,
        val onRequest: () -> Unit,
        private val closeWith: CloseResult?,
    ) : TunnelSession {
        private val closed = CompletableDeferred<CloseResult>()
        var disconnected = false

        override suspend fun run(): CloseResult {
            onEstablished("https://tunnel.example/mcp")
            return closeWith ?: closed.await()
        }

        override fun disconnect() {
            disconnected = true
            closed.complete(CloseResult(CloseCodes.NORMAL, "Client disconnect"))
        }

        override fun abort(reason: String) {
            closed.complete(CloseResult(CloseCodes.GOING_AWAY, reason))
        }
    }

    private val credentials = Credentials("at-0", "rt-0", "2099-01-01T00:00:00Z", UserInfo("u", "a@b.com", "free"))
    private val sessions = mutableListOf<Session>()

    private fun TestScope.tunnel(
        store: CredentialStore,
        log: TunnelLog,
        closeWith: CloseResult? = null,
        idleTimeoutMs: Long? = null,
        authBaseUrl: String = "ws://127.0.0.1:9",
    ) = TunnelConnection(
        scope = backgroundScope,
        credentialStore = store,
        refresher = TokenRefresher(store, DeviceFlowClient(authBaseUrl, OkHttpClient())),
        sessionFactory = TunnelSessionFactory { _, onEstablished, onRequest ->
            Session(onEstablished, onRequest, closeWith).also { sessions += it }
        },
        policy = ReconnectPolicy(),
        networkAvailable = MutableStateFlow(true),
        log = log,
        idleTimeoutMs = { idleTimeoutMs },
        elapsedClock = ElapsedClock { testScheduler.currentTime },
    )

    /** A log whose clock runs the hook once, the next time something is logged. */
    private class HookedLog {
        var next: (() -> Unit)? = null
        val log = TunnelLog(
            clock = {
                next?.invoke()
                next = null
                0L
            },
            mirrorToLogcat = false,
        )
    }

    @Test
    fun `an idle stop is called off when a request lands between the decision and the stop`() = runTest {
        // The idle check logs its decision before stopping; the clock of that log line is the
        // only hook between the two, so the "concurrent" request is injected there.
        val hooked = HookedLog()
        val tunnel = tunnel(InMemoryCredentials(credentials), hooked.log, idleTimeoutMs = IDLE_MS)
        tunnel.start()
        runCurrent()
        hooked.next = { sessions.single().onRequest() }

        advanceTimeBy(IDLE_MS)
        runCurrent()

        val messages = hooked.log.entries.value.map { it.message }
        assertTrue(messages.toString(), messages.contains("Idle timeout: no requests for 30 min, disconnecting"))
        assertTrue(messages.toString(), messages.contains("A request arrived, staying connected"))
        assertTrue(tunnel.state.value is TunnelState.Connected)
        assertEquals("counts from the request that arrived", 2 * IDLE_MS, tunnel.idleDeadlineMs.value)

        advanceTimeBy(IDLE_MS)
        runCurrent()
        assertTrue((tunnel.state.value as TunnelState.Stopped).idleTimeout)
    }

    @Test
    fun `an idle stop called off by a settings change says so, not that a request arrived`() = runTest {
        val hooked = HookedLog()
        val tunnel = tunnel(InMemoryCredentials(credentials), hooked.log, idleTimeoutMs = IDLE_MS)
        tunnel.start()
        runCurrent()
        hooked.next = { tunnel.onIdleSettingsChanged() }

        advanceTimeBy(IDLE_MS)
        runCurrent()

        val messages = hooked.log.entries.value.map { it.message }
        assertTrue(messages.toString(), messages.contains("Idle settings changed, staying connected"))
        assertFalse(messages.toString(), messages.contains("A request arrived, staying connected"))
        assertTrue(tunnel.state.value is TunnelState.Connected)
        tunnel.stop()
    }

    @Test
    fun `no credentials is a sign-out, not an expired session`() = runTest {
        val tunnel = tunnel(InMemoryCredentials(null), TunnelLog(mirrorToLogcat = false))

        tunnel.start()
        val stopped = tunnel.state.first { it is TunnelState.Stopped } as TunnelState.Stopped

        assertTrue(stopped.reloginRequired)
        assertTrue(stopped.signedOut)
    }

    @Test
    fun `revoked access needs a new sign-in and is not a sign-out`() = runTest {
        val tunnel = tunnel(
            InMemoryCredentials(credentials),
            TunnelLog(mirrorToLogcat = false),
            closeWith = CloseResult(CloseCodes.TOKEN_REVOKED, "revoked"),
        )

        tunnel.start()
        val stopped = tunnel.state.first { it is TunnelState.Stopped } as TunnelState.Stopped

        assertTrue(stopped.reloginRequired)
        assertFalse(stopped.signedOut)
    }

    @Test
    fun `credentials cleared while connected stop the tunnel as signed out`() = runTest {
        val store = InMemoryCredentials(credentials)
        val tunnel = tunnel(store, TunnelLog(mirrorToLogcat = false))
        tunnel.start()
        runCurrent()
        assertTrue(tunnel.state.value is TunnelState.Connected)

        store.clear()
        runCurrent()

        assertFalse(tunnel.isRunning)
        val stopped = tunnel.state.value as TunnelState.Stopped
        assertTrue(stopped.signedOut)
        assertEquals("Not signed in", stopped.reason)
        assertTrue("the socket is closed", sessions.single().disconnected)
    }

    @Test
    fun `a run that clears the credentials itself keeps its own reason`() = runTest {
        val tunnel = tunnel(
            InMemoryCredentials(credentials),
            TunnelLog(mirrorToLogcat = false),
            closeWith = CloseResult(CloseCodes.TOKEN_REVOKED, "revoked"),
        )

        tunnel.start()
        runCurrent()

        val stopped = tunnel.state.value as TunnelState.Stopped
        assertEquals(CloseCodes.TOKEN_REVOKED, stopped.code)
        assertFalse("not replaced by the sign-out observer", stopped.signedOut)
    }

    @Test
    fun `a refresh rejected with invalid_grant stays a session expiry, not a sign-out`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            val store = InMemoryCredentials(credentials.copy(expiresAt = "2000-01-01T00:00:00Z"))
            val tunnel = tunnel(
                store,
                TunnelLog(mirrorToLogcat = false),
                authBaseUrl = server.url("/").toString().replaceFirst("http://", "ws://").trimEnd('/'),
            )

            tunnel.start()
            tunnel.state.first { it is TunnelState.Stopped }
            runCurrent()

            assertNull(store.load())
            val stopped = tunnel.state.value as TunnelState.Stopped
            assertTrue(stopped.reloginRequired)
            assertFalse(stopped.signedOut)
            assertEquals("Session expired, please sign in again", stopped.reason)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `clearing credentials while stopped does nothing`() = runTest {
        val store = InMemoryCredentials(credentials)
        val log = TunnelLog(mirrorToLogcat = false)
        val tunnel = tunnel(store, log)
        runCurrent()

        store.clear()
        runCurrent()

        assertEquals(TunnelState.Idle, tunnel.state.value)
        assertTrue(log.entries.value.isEmpty())
    }

    private companion object {
        const val IDLE_MS = 30 * 60_000L
    }
}
