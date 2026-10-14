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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelConnectionTest {
    private class InMemoryStore(initial: Credentials?) : CredentialStore {
        private val state = MutableStateFlow(initial)
        var clears = 0
        override val current: StateFlow<Credentials?> = state.asStateFlow()
        override fun save(credentials: Credentials) {
            state.value = credentials
        }

        override fun clear() {
            clears++
            state.value = null
        }
    }

    /** Each script entry decides what one session does. */
    private sealed interface Step {
        data class Fail(val closeCode: Int? = null) : Step
        data class EstablishThenClose(val code: Int, val reason: String = "", val beforeEstablish: () -> Unit = {}) : Step
        data object EstablishThenHang : Step

        /**
         * A session whose run() does not observe cancellation (plain suspendCoroutine) and reports
         * 1001 once disconnected, so the loop keeps going after stop() cancelled it.
         */
        data object EstablishThenHangIgnoringCancel : Step
    }

    private class FakeSession(
        private val step: Step,
        private val onEstablished: (String) -> Unit,
        private val onRequest: () -> Unit,
        private val health: (FakeSession) -> Boolean,
    ) : TunnelSession {
        private val hang = CompletableDeferred<CloseResult>()
        private var uncancellable: Continuation<CloseResult>? = null
        var disconnected = false
        var abortedWith: String? = null
        var healthChecks = 0

        /** Simulates a `request` frame arriving from the relay. */
        fun request() = onRequest()

        override suspend fun run(): CloseResult = when (step) {
            is Step.Fail -> throw TunnelConnectionException("scripted failure", step.closeCode)
            is Step.EstablishThenClose -> {
                step.beforeEstablish()
                onEstablished("https://tunnel.example/mcp")
                CloseResult(step.code, step.reason)
            }
            Step.EstablishThenHang -> {
                onEstablished("https://tunnel.example/mcp")
                hang.await()
            }
            Step.EstablishThenHangIgnoringCancel -> {
                onEstablished("https://tunnel.example/mcp")
                suspendCoroutine { uncancellable = it }
            }
        }

        override fun disconnect() {
            disconnected = true
            if (step == Step.EstablishThenHangIgnoringCancel) {
                uncancellable?.resume(CloseResult(CloseCodes.GOING_AWAY, "Socket dropped"))
            } else {
                hang.complete(CloseResult(CloseCodes.NORMAL, "Client disconnect"))
            }
        }

        override fun abort(reason: String) {
            abortedWith = reason
            hang.complete(CloseResult(CloseCodes.GOING_AWAY, reason))
        }

        override fun checkHealth(): Boolean {
            healthChecks++
            return health(this)
        }
    }

    private class ScriptedFactory(
        steps: List<Step>,
        private val health: (FakeSession) -> Boolean = { true },
    ) : TunnelSessionFactory {
        private val remaining = ArrayDeque(steps)
        val usedTokens = CopyOnWriteArrayList<String>()
        val sessions = CopyOnWriteArrayList<FakeSession>()

        override fun create(credentials: Credentials, onEstablished: (String) -> Unit, onRequest: () -> Unit): TunnelSession {
            usedTokens += credentials.accessToken
            val step = remaining.removeFirstOrNull() ?: Step.Fail()
            return FakeSession(step, onEstablished, onRequest, health).also { sessions += it }
        }
    }

    private class RecordingWakeLocks : WakeLocks {
        val acquired = CopyOnWriteArrayList<String>()
        val released = CopyOnWriteArrayList<String>()

        override fun acquire(tag: String, timeoutMs: Long): AutoCloseable {
            acquired += tag
            var open = true
            return AutoCloseable {
                if (open) {
                    open = false
                    released += tag
                }
            }
        }
    }

    private class RecordingWakeups : WakeupScheduler {
        val scheduled = CopyOnWriteArrayList<Long>()
        var cancels = 0

        /** Delay of the alarm that is currently armed, or null. */
        var pending: Long? = null

        override fun schedule(delayMs: Long) {
            scheduled += delayMs
            pending = delayMs
        }

        override fun cancel() {
            cancels++
            pending = null
        }
    }

    private lateinit var server: MockWebServer
    private val credentials = Credentials("at-0", "rt-0", "2099-01-01T00:00:00Z", UserInfo("u", "a@b.com", "free"))

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

    private fun TestScope.connection(
        store: CredentialStore,
        factory: TunnelSessionFactory,
        network: StateFlow<Boolean> = MutableStateFlow(true),
        keepRetrying: Boolean = false,
        policy: ReconnectPolicy = ReconnectPolicy(random = Random(0)),
        wakeLocks: WakeLocks = WakeLocks.None,
        wakeups: WakeupScheduler = WakeupScheduler.None,
        idleTimeoutMs: () -> Long? = { null },
        idleWakeups: WakeupScheduler = WakeupScheduler.None,
        elapsedClock: ElapsedClock = ElapsedClock { testScheduler.currentTime },
        log: TunnelLog = TunnelLog(mirrorToLogcat = false),
    ): TunnelConnection {
        val authClient = DeviceFlowClient(
            server.url("/").toString().replaceFirst("http://", "ws://").trimEnd('/'),
            OkHttpClient(),
        )
        return TunnelConnection(
            scope = backgroundScope,
            credentialStore = store,
            refresher = TokenRefresher(store, authClient),
            sessionFactory = factory,
            policy = policy,
            networkAvailable = network,
            log = log,
            keepRetrying = { keepRetrying },
            wakeLocks = wakeLocks,
            wakeups = wakeups,
            idleTimeoutMs = idleTimeoutMs,
            idleWakeups = idleWakeups,
            elapsedClock = elapsedClock,
        )
    }

    private suspend fun TunnelConnection.awaitStopped(): TunnelState.Stopped =
        state.first { it is TunnelState.Stopped } as TunnelState.Stopped

    private fun TestScope.recordStates(tunnel: TunnelConnection): MutableList<TunnelState> {
        val states = CopyOnWriteArrayList<TunnelState>()
        backgroundScope.launch { tunnel.state.collect { states += it } }
        return states
    }

    @Test
    fun `permanent close code stops without reconnecting`() = runTest {
        val factory = ScriptedFactory(listOf(Step.EstablishThenClose(CloseCodes.SESSION_REPLACED, "other device")))
        val tunnel = connection(InMemoryStore(credentials), factory)

        tunnel.start()
        val stopped = tunnel.awaitStopped()

        assertEquals(CloseCodes.SESSION_REPLACED, stopped.code)
        assertEquals(false, stopped.reloginRequired)
        assertEquals(1, factory.usedTokens.size)
    }

    @Test
    fun `transient close reconnects after backoff`() = runTest {
        val factory = ScriptedFactory(
            listOf(Step.EstablishThenClose(CloseCodes.SHUTDOWN), Step.EstablishThenClose(CloseCodes.NORMAL)),
        )
        val tunnel = connection(InMemoryStore(credentials), factory)
        val states = recordStates(tunnel)

        tunnel.start()
        tunnel.awaitStopped()

        assertEquals(2, factory.usedTokens.size)
        val reconnecting = states.filterIsInstance<TunnelState.Reconnecting>().single()
        assertEquals(1, reconnecting.attempt)
        assertTrue(reconnecting.delayMs in 2_000L..2_600L)
    }

    @Test
    fun `auth close refreshes the token and reconnects immediately with it`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(tokenBody(1)))
        val store = InMemoryStore(credentials)
        val factory = ScriptedFactory(
            listOf(Step.EstablishThenClose(CloseCodes.AUTH_FAILED), Step.EstablishThenClose(CloseCodes.NORMAL)),
        )
        val tunnel = connection(store, factory)

        tunnel.start()
        tunnel.awaitStopped()

        assertEquals(listOf("at-0", "at-1"), factory.usedTokens.toList())
        assertEquals("at-1", store.load()!!.accessToken)
        assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token=rt-0"))
    }

    @Test
    fun `a second auth close before establishing goes through backoff`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(tokenBody(1)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tokenBody(2)))
        val factory = ScriptedFactory(
            listOf(
                Step.Fail(closeCode = CloseCodes.TUNNEL_AUTH_FAILED),
                Step.Fail(closeCode = CloseCodes.TUNNEL_AUTH_FAILED),
                Step.EstablishThenClose(CloseCodes.NORMAL),
            ),
        )
        val tunnel = connection(InMemoryStore(credentials), factory)
        val states = recordStates(tunnel)

        tunnel.start()
        tunnel.awaitStopped()

        assertEquals(listOf("at-0", "at-1", "at-2"), factory.usedTokens.toList())
        val reconnecting = states.filterIsInstance<TunnelState.Reconnecting>().single()
        assertEquals(1, reconnecting.attempt)
        assertTrue(reconnecting.lastError, reconnecting.lastError.startsWith("4003"))
    }

    @Test
    fun `revoked refresh token stops and clears credentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        val store = InMemoryStore(credentials)
        val tunnel = connection(store, ScriptedFactory(listOf(Step.Fail(closeCode = CloseCodes.TUNNEL_AUTH_FAILED))))

        tunnel.start()
        tunnel.awaitStopped()

        val stopped = tunnel.state.value as TunnelState.Stopped
        assertTrue(stopped.reloginRequired)
        assertNull(store.load())
    }

    @Test
    fun `token revoked close requires relogin`() = runTest {
        val store = InMemoryStore(credentials)
        val tunnel = connection(store, ScriptedFactory(listOf(Step.EstablishThenClose(CloseCodes.TOKEN_REVOKED))))

        tunnel.start()
        val stopped = tunnel.awaitStopped()

        assertTrue(stopped.reloginRequired)
        assertNull(store.load())
    }

    @Test
    fun `gives up after ten consecutive connection failures`() = runTest {
        val factory = ScriptedFactory(emptyList())
        val tunnel = connection(InMemoryStore(credentials), factory)

        tunnel.start()
        val stopped = tunnel.awaitStopped()

        assertTrue(stopped.reason, stopped.reason.contains("Max reconnection attempts"))
        assertEquals(10, factory.usedTokens.size)
    }

    @Test
    fun `keep connected keeps retrying every 30s after the attempt budget is used up`() = runTest {
        val factory = ScriptedFactory(List(11) { Step.Fail() } + Step.EstablishThenClose(CloseCodes.NORMAL))
        val tunnel = connection(InMemoryStore(credentials), factory, keepRetrying = true)
        val states = recordStates(tunnel)

        tunnel.start()
        val stopped = tunnel.awaitStopped()

        assertEquals(CloseCodes.NORMAL, stopped.code)
        assertEquals(12, factory.usedTokens.size)
        val persistent = states.filterIsInstance<TunnelState.Reconnecting>().filter { it.persistent }
        assertEquals(listOf(10, 11), persistent.map { it.attempt })
        assertTrue(persistent.all { it.delayMs == 30_000L })
    }

    @Test
    fun `stop disconnects a live tunnel and cancels its job`() = runTest {
        val factory = ScriptedFactory(listOf(Step.EstablishThenHang))
        val tunnel = connection(InMemoryStore(credentials), factory)

        tunnel.start()
        runCurrent()
        assertEquals(TunnelState.Connected("https://tunnel.example/mcp"), tunnel.state.value)
        assertTrue(tunnel.isRunning)

        tunnel.stop()
        advanceUntilIdle()

        assertTrue(factory.sessions.single().disconnected)
        assertFalse(tunnel.isRunning)
        assertEquals(1, factory.sessions.size)
        assertEquals(TunnelState.Stopped(CloseCodes.NORMAL, "Disconnected by user"), tunnel.state.value)
    }

    @Test
    fun `stop cancels the pending backoff alarm`() = runTest {
        val wakeups = RecordingWakeups()
        val factory = ScriptedFactory(listOf(Step.Fail()))
        val tunnel = connection(
            InMemoryStore(credentials),
            factory,
            policy = ReconnectPolicy(initialDelayMs = 10_000, jitterFactor = 0.0),
            wakeups = wakeups,
        )

        tunnel.start()
        runCurrent()
        assertTrue(tunnel.state.value is TunnelState.Reconnecting)
        assertEquals(20_000L, wakeups.pending)

        tunnel.stop()

        // Cancelled by stop() itself: the cancelled loop no longer owns the alarm.
        assertNull(wakeups.pending)
        advanceUntilIdle()
        assertNull(wakeups.pending)
        assertEquals(1, factory.sessions.size)
    }

    @Test
    fun `a cancelled loop cannot overwrite the state stop published`() = runTest {
        val factory = ScriptedFactory(listOf(Step.EstablishThenHangIgnoringCancel))
        val tunnel = connection(InMemoryStore(credentials), factory)
        val states = recordStates(tunnel)

        tunnel.start()
        runCurrent()
        tunnel.stop()
        advanceUntilIdle()

        assertEquals(TunnelState.Stopped(CloseCodes.NORMAL, "Disconnected by user"), tunnel.state.value)
        assertTrue(states.none { it is TunnelState.Reconnecting })
        assertEquals(1, factory.sessions.size)
    }

    @Test
    fun `start after stop runs a fresh session`() = runTest {
        val factory = ScriptedFactory(listOf(Step.EstablishThenHang, Step.EstablishThenHang))
        val tunnel = connection(InMemoryStore(credentials), factory)

        tunnel.start()
        runCurrent()
        tunnel.stop()
        tunnel.start()
        runCurrent()

        assertEquals(TunnelState.Connected("https://tunnel.example/mcp"), tunnel.state.value)
        assertEquals(2, factory.sessions.size)
        tunnel.stop()
        assertTrue(factory.sessions[1].disconnected)
    }

    @Test
    fun `a blocked service start is shown as stopped when no tunnel runs`() = runTest {
        val tunnel = connection(InMemoryStore(credentials), ScriptedFactory(emptyList()))

        tunnel.reportStartBlocked("Open the app to connect")

        assertEquals(TunnelState.Stopped(0, "Open the app to connect"), tunnel.state.value)
        assertFalse(tunnel.isRunning)
    }

    @Test
    fun `a blocked service start does not override a running tunnel`() = runTest {
        val tunnel = connection(InMemoryStore(credentials), ScriptedFactory(listOf(Step.EstablishThenHang)))
        tunnel.start()
        runCurrent()

        tunnel.reportStartBlocked("Open the app to connect")

        assertEquals(TunnelState.Connected("https://tunnel.example/mcp"), tunnel.state.value)
        tunnel.stop()
    }

    @Test
    fun `not signed in stops without clearing anything`() = runTest {
        val store = InMemoryStore(null)
        val tunnel = connection(store, ScriptedFactory(emptyList()))

        tunnel.start()
        val stopped = tunnel.awaitStopped()

        assertTrue(stopped.reloginRequired)
        assertEquals(0, store.clears)
    }

    @Test
    fun `waits for network without consuming attempts`() = runTest {
        val network = MutableStateFlow(false)
        val factory = ScriptedFactory(listOf(Step.EstablishThenClose(CloseCodes.NORMAL)))
        val tunnel = connection(InMemoryStore(credentials), factory, network = network)

        tunnel.start()
        runCurrent()
        assertEquals(TunnelState.WaitingForNetwork, tunnel.state.value)
        assertEquals(0, factory.usedTokens.size)

        network.value = true
        tunnel.awaitStopped()
        assertEquals(1, factory.usedTokens.size)
    }

    @Test
    fun `network nudge cuts the backoff short`() = runTest {
        val factory = ScriptedFactory(listOf(Step.Fail(), Step.EstablishThenClose(CloseCodes.NORMAL)))
        val tunnel = connection(InMemoryStore(credentials), factory)

        tunnel.start()
        runCurrent()
        assertTrue(tunnel.state.value is TunnelState.Reconnecting)
        val before = currentTime

        tunnel.onNetworkAvailable(changed = false)
        tunnel.awaitStopped()

        assertEquals(before, currentTime)
        assertEquals(2, factory.usedTokens.size)
    }

    @Test
    fun `network change drops a live socket and reconnects immediately`() = runTest {
        val factory = ScriptedFactory(listOf(Step.EstablishThenHang, Step.EstablishThenClose(CloseCodes.NORMAL)))
        val tunnel = connection(InMemoryStore(credentials), factory)

        tunnel.start()
        runCurrent()
        val before = currentTime

        tunnel.onNetworkAvailable(changed = true)
        tunnel.awaitStopped()

        assertEquals("Network changed", factory.sessions[0].abortedWith)
        assertEquals(2, factory.sessions.size)
        assertEquals(before, currentTime)
    }

    @Test
    fun `network callback while connected leaves no pending nudge`() = runTest {
        val factory = ScriptedFactory(listOf(Step.EstablishThenHang, Step.EstablishThenClose(CloseCodes.NORMAL)))
        val tunnel = connection(InMemoryStore(credentials), factory)

        tunnel.start()
        runCurrent()
        tunnel.onNetworkAvailable(changed = false)
        assertNull(factory.sessions[0].abortedWith)
        val before = currentTime

        factory.sessions[0].abort("socket died")
        tunnel.awaitStopped()

        assertTrue(currentTime - before >= 2_000)
    }

    @Test
    fun `nudge that arrived while connecting does not skip the next backoff`() = runTest {
        lateinit var tunnel: TunnelConnection
        val factory = ScriptedFactory(
            listOf(
                Step.EstablishThenClose(CloseCodes.SHUTDOWN, beforeEstablish = { tunnel.onNetworkAvailable(false) }),
                Step.EstablishThenClose(CloseCodes.NORMAL),
            ),
        )
        tunnel = connection(InMemoryStore(credentials), factory)
        val before = currentTime

        tunnel.start()
        tunnel.awaitStopped()

        assertEquals(2, factory.sessions.size)
        assertTrue("backoff was skipped", currentTime - before >= 2_000)
    }

    @Test
    fun `short backoffs hold a wake lock, long ones schedule an alarm`() = runTest {
        val wakeLocks = RecordingWakeLocks()
        val wakeups = RecordingWakeups()
        val factory = ScriptedFactory(List(3) { Step.Fail() } + Step.EstablishThenClose(CloseCodes.NORMAL))
        val tunnel = connection(
            InMemoryStore(credentials),
            factory,
            policy = ReconnectPolicy(jitterFactor = 0.0),
            wakeLocks = wakeLocks,
            wakeups = wakeups,
        )

        tunnel.start()
        tunnel.awaitStopped()

        // 2 s and 4 s backoffs under a wake lock, the 8 s one via an alarm.
        assertEquals(2, wakeLocks.acquired.count { it == "backoff" })
        assertTrue(wakeups.scheduled.contains(8_000L))
        assertEquals(4, wakeLocks.acquired.count { it == "connect" })
        assertEquals(wakeLocks.acquired.sorted(), wakeLocks.released.sorted())
    }

    @Test
    fun `alarm wake-up during a long backoff reconnects immediately`() = runTest {
        val wakeLocks = RecordingWakeLocks()
        val factory = ScriptedFactory(List(3) { Step.Fail() } + Step.EstablishThenClose(CloseCodes.NORMAL))
        val tunnel = connection(
            InMemoryStore(credentials),
            factory,
            policy = ReconnectPolicy(jitterFactor = 0.0),
            wakeLocks = wakeLocks,
            wakeups = RecordingWakeups(),
        )

        tunnel.start()
        tunnel.state.first { it is TunnelState.Reconnecting && it.attempt == 3 }
        runCurrent()
        val before = currentTime

        tunnel.onWakeup()
        tunnel.awaitStopped()

        assertEquals(before, currentTime)
        assertEquals(4, factory.sessions.size)
        assertEquals(1, wakeLocks.acquired.count { it == "wakeup" })
        assertEquals(wakeLocks.acquired.sorted(), wakeLocks.released.sorted())
    }

    @Test
    fun `while connected an alarm runs the liveness check and re-arms itself`() = runTest {
        val wakeLocks = RecordingWakeLocks()
        val wakeups = RecordingWakeups()
        val factory = ScriptedFactory(listOf(Step.EstablishThenHang))
        val tunnel = connection(InMemoryStore(credentials), factory, wakeLocks = wakeLocks, wakeups = wakeups)

        tunnel.start()
        runCurrent()
        assertEquals(listOf(TunnelConnection.LIVENESS_CHECK_MS), wakeups.scheduled.toList())

        tunnel.onWakeup()

        assertEquals(1, factory.sessions.single().healthChecks)
        assertEquals(2, wakeups.scheduled.size)
        assertEquals(listOf("wakeup"), wakeLocks.released.filter { it == "wakeup" })
        assertTrue(tunnel.state.value is TunnelState.Connected)
        tunnel.stop()
    }

    @Test
    fun `failed liveness check does not re-arm the alarm and the loop reconnects`() = runTest {
        val wakeLocks = RecordingWakeLocks()
        val wakeups = RecordingWakeups()
        // Like TunnelClient: an unhealthy session queues its own abort and reports false.
        val factory = ScriptedFactory(listOf(Step.EstablishThenHang, Step.EstablishThenClose(CloseCodes.NORMAL))) { session ->
            session.abort("No server ping in 61s")
            false
        }
        val tunnel = connection(
            InMemoryStore(credentials),
            factory,
            policy = ReconnectPolicy(jitterFactor = 0.0),
            wakeLocks = wakeLocks,
            wakeups = wakeups,
        )

        tunnel.start()
        runCurrent()
        assertEquals(listOf(TunnelConnection.LIVENESS_CHECK_MS), wakeups.scheduled.toList())

        tunnel.onWakeup()

        assertEquals(1, factory.sessions[0].healthChecks)
        assertEquals(listOf(TunnelConnection.LIVENESS_CHECK_MS), wakeups.scheduled.toList())
        // The bridging wake lock stays held until the loop takes over.
        assertFalse(wakeLocks.released.contains("wakeup"))

        runCurrent()
        assertNull("the dead session's liveness alarm is cancelled", wakeups.pending)
        assertTrue(tunnel.state.value is TunnelState.Reconnecting)

        tunnel.awaitStopped()
        assertEquals(2, factory.sessions.size)
        assertEquals(wakeLocks.acquired.sorted(), wakeLocks.released.sorted())
    }

    @Test
    fun `session dying during the liveness check keeps the backoff alarm`() = runTest {
        val wakeups = RecordingWakeups()
        // The socket dies while checkHealth runs, and the loop gets as far as arming its backoff
        // alarm before onWakeup continues: the check itself still saw a healthy session.
        val factory = ScriptedFactory(listOf(Step.EstablishThenHang, Step.EstablishThenHang)) { session ->
            session.abort("socket died")
            testScheduler.runCurrent()
            true
        }
        val tunnel = connection(
            InMemoryStore(credentials),
            factory,
            policy = ReconnectPolicy(initialDelayMs = 10_000, jitterFactor = 0.0),
            wakeups = wakeups,
        )

        tunnel.start()
        runCurrent()
        assertEquals(TunnelConnection.LIVENESS_CHECK_MS, wakeups.pending)

        tunnel.onWakeup()

        assertTrue(tunnel.state.value is TunnelState.Reconnecting)
        assertEquals(listOf(TunnelConnection.LIVENESS_CHECK_MS, 20_000L), wakeups.scheduled.toList())
        assertEquals(20_000L, wakeups.pending)
        tunnel.stop()
    }

    @Test
    fun `idle timer stops the tunnel at the deadline`() = runTest {
        val idleWakeups = RecordingWakeups()
        val log = TunnelLog(mirrorToLogcat = false)
        val factory = ScriptedFactory(listOf(Step.EstablishThenHang))
        val tunnel = connection(
            InMemoryStore(credentials),
            factory,
            idleTimeoutMs = { IDLE_MS },
            idleWakeups = idleWakeups,
            log = log,
        )

        tunnel.start()
        runCurrent()
        assertEquals(IDLE_MS, tunnel.idleDeadlineMs.value)
        assertEquals("the Doze-proof alarm is armed for the deadline", IDLE_MS, idleWakeups.pending)

        advanceTimeBy(IDLE_MS - 1)
        runCurrent()
        assertTrue(tunnel.state.value is TunnelState.Connected)

        advanceTimeBy(1)
        runCurrent()

        val stopped = tunnel.state.value as TunnelState.Stopped
        assertTrue(stopped.idleTimeout)
        assertEquals(CloseCodes.NORMAL, stopped.code)
        assertEquals("No requests for 30 min", stopped.reason)
        assertFalse(stopped.reloginRequired)
        assertFalse(tunnel.isRunning)
        assertTrue(factory.sessions.single().disconnected)
        assertNull(tunnel.idleDeadlineMs.value)
        assertNull(idleWakeups.pending)
        val messages = log.entries.value.map { it.message }
        assertTrue(messages.toString(), messages.contains("Idle timeout: no requests for 30 min, disconnecting"))
        assertTrue(messages.toString(), messages.contains("Tunnel stopped: No requests for 30 min"))
    }

    @Test
    fun `a request frame resets the idle timer`() = runTest {
        val idleWakeups = RecordingWakeups()
        val factory = ScriptedFactory(listOf(Step.EstablishThenHang))
        val tunnel = connection(
            InMemoryStore(credentials),
            factory,
            idleTimeoutMs = { IDLE_MS },
            idleWakeups = idleWakeups,
        )

        tunnel.start()
        runCurrent()
        advanceTimeBy(20 * MINUTE)
        factory.sessions.single().request()
        assertEquals(20 * MINUTE + IDLE_MS, tunnel.idleDeadlineMs.value)

        // The original deadline passes: the timer re-arms for the remaining 20 minutes instead of stopping.
        advanceTimeBy(IDLE_MS - 20 * MINUTE + MINUTE)
        runCurrent()
        assertTrue(tunnel.state.value is TunnelState.Connected)
        assertEquals(20 * MINUTE, idleWakeups.pending)

        advanceTimeBy(20 * MINUTE)
        runCurrent()
        assertTrue((tunnel.state.value as TunnelState.Stopped).idleTimeout)
        assertEquals(51 * MINUTE, currentTime)
    }

    @Test
    fun `idle timer is disabled in always connected mode`() = runTest {
        val idleWakeups = RecordingWakeups()
        val tunnel = connection(
            InMemoryStore(credentials),
            ScriptedFactory(listOf(Step.EstablishThenHang)),
            idleTimeoutMs = { null },
            idleWakeups = idleWakeups,
        )

        tunnel.start()
        advanceTimeBy(3 * 60 * MINUTE)
        runCurrent()

        assertTrue(tunnel.state.value is TunnelState.Connected)
        assertNull(tunnel.idleDeadlineMs.value)
        assertTrue(idleWakeups.scheduled.isEmpty())
        tunnel.stop()
    }

    @Test
    fun `idle alarm stops the tunnel when Doze kept the coroutine timer from firing`() = runTest {
        var elapsed = 0L
        val tunnel = connection(
            InMemoryStore(credentials),
            ScriptedFactory(listOf(Step.EstablishThenHang)),
            idleTimeoutMs = { IDLE_MS },
            elapsedClock = ElapsedClock { elapsed },
        )
        tunnel.start()
        runCurrent()

        // The CPU slept: elapsedRealtime moved on, coroutine time did not.
        elapsed = IDLE_MS + 5 * MINUTE
        tunnel.onIdleAlarm()

        assertTrue((tunnel.state.value as TunnelState.Stopped).idleTimeout)
        assertFalse(tunnel.isRunning)
    }

    @Test
    fun `idle alarm before the deadline re-arms for the remaining time`() = runTest {
        var elapsed = 0L
        val idleWakeups = RecordingWakeups()
        val tunnel = connection(
            InMemoryStore(credentials),
            ScriptedFactory(listOf(Step.EstablishThenHang)),
            idleTimeoutMs = { IDLE_MS },
            idleWakeups = idleWakeups,
            elapsedClock = ElapsedClock { elapsed },
        )
        tunnel.start()
        runCurrent()

        elapsed = IDLE_MS - 10 * MINUTE
        tunnel.onIdleAlarm()

        assertTrue(tunnel.state.value is TunnelState.Connected)
        assertEquals(10 * MINUTE, idleWakeups.pending)
        tunnel.stop()
    }

    @Test
    fun `switching to always connected mid-session cancels the idle timer`() = runTest {
        var window: Long? = IDLE_MS
        val idleWakeups = RecordingWakeups()
        val tunnel = connection(
            InMemoryStore(credentials),
            ScriptedFactory(listOf(Step.EstablishThenHang)),
            idleTimeoutMs = { window },
            idleWakeups = idleWakeups,
        )
        tunnel.start()
        runCurrent()
        assertEquals(IDLE_MS, idleWakeups.pending)

        window = null
        tunnel.onIdleSettingsChanged()

        assertNull(tunnel.idleDeadlineMs.value)
        assertNull(idleWakeups.pending)
        advanceTimeBy(2 * IDLE_MS)
        runCurrent()
        assertTrue(tunnel.state.value is TunnelState.Connected)
        tunnel.stop()
    }

    @Test
    fun `the idle timer of a stopped run cannot stop the next run early`() = runTest {
        val factory = ScriptedFactory(listOf(Step.EstablishThenHang, Step.EstablishThenHang))
        val tunnel = connection(InMemoryStore(credentials), factory, idleTimeoutMs = { IDLE_MS })

        tunnel.start()
        runCurrent()
        advanceTimeBy(10 * MINUTE)
        tunnel.stop()
        tunnel.start()
        runCurrent()

        advanceTimeBy(IDLE_MS - 1)
        runCurrent()
        assertTrue("first run's deadline must not stop the second run", tunnel.state.value is TunnelState.Connected)

        advanceTimeBy(1)
        runCurrent()
        assertTrue((tunnel.state.value as TunnelState.Stopped).idleTimeout)
        assertEquals(40 * MINUTE, currentTime)
    }

    @Test
    fun `the idle timer keeps counting while the tunnel reconnects`() = runTest {
        val factory = ScriptedFactory(List(20) { Step.Fail() })
        val tunnel = connection(
            InMemoryStore(credentials),
            factory,
            keepRetrying = true,
            idleTimeoutMs = { IDLE_MS },
        )

        tunnel.start()
        advanceTimeBy(IDLE_MS)
        runCurrent()

        assertTrue((tunnel.state.value as TunnelState.Stopped).idleTimeout)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val IDLE_MS = 30 * MINUTE
    }
}
