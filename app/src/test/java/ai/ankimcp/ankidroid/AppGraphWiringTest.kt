package ai.ankimcp.ankidroid

import ai.ankimcp.ankidroid.auth.CredentialStore
import ai.ankimcp.ankidroid.auth.Credentials
import ai.ankimcp.ankidroid.auth.DeviceFlowClient
import ai.ankimcp.ankidroid.auth.TokenRefresher
import ai.ankimcp.ankidroid.auth.UserInfo
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.push.PushCoordinator
import ai.ankimcp.ankidroid.push.PushMessaging
import ai.ankimcp.ankidroid.push.PushRegistrar
import ai.ankimcp.ankidroid.push.PushRegistrationStore
import ai.ankimcp.ankidroid.push.PushResult
import ai.ankimcp.ankidroid.push.PushWorkScheduler
import ai.ankimcp.ankidroid.push.SignInNotifier
import ai.ankimcp.ankidroid.push.WakeHandler
import ai.ankimcp.ankidroid.service.serviceStartExtras
import ai.ankimcp.ankidroid.service.startTunnelForServiceStart
import ai.ankimcp.ankidroid.settings.IdleWindow
import ai.ankimcp.ankidroid.settings.InMemoryKeyValueStore
import ai.ankimcp.ankidroid.tunnel.CloseCodes
import ai.ankimcp.ankidroid.tunnel.CloseResult
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import ai.ankimcp.ankidroid.tunnel.ReconnectPolicy
import ai.ankimcp.ankidroid.tunnel.TunnelConnection
import ai.ankimcp.ankidroid.tunnel.TunnelSession
import ai.ankimcp.ankidroid.tunnel.TunnelSessionFactory
import ai.ankimcp.ankidroid.tunnel.TunnelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AppGraph wiring between push registration, the idle timer and wake-ups, with real objects. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppGraphWiringTest {
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

    private class HangingSession(private val onEstablished: (String) -> Unit) : TunnelSession {
        private val closed = CompletableDeferred<CloseResult>()

        override suspend fun run(): CloseResult {
            onEstablished("https://tunnel.example/mcp")
            return closed.await()
        }

        override fun disconnect() {
            closed.complete(CloseResult(CloseCodes.NORMAL, "Client disconnect"))
        }

        override fun abort(reason: String) {
            closed.complete(CloseResult(CloseCodes.GOING_AWAY, reason))
        }
    }

    private class FakeMessaging : PushMessaging {
        override val isAvailable = true

        override suspend fun token() = "token-1"

        override suspend fun deleteToken() = Unit

        override fun setAutoInitEnabled(enabled: Boolean) = Unit
    }

    private class FakeRegistrar : PushRegistrar {
        var result: PushResult = PushResult.Ok

        override suspend fun register(token: String) = result

        override suspend fun unregister(token: String) = PushResult.Ok
    }

    private class RecordingNotifier : SignInNotifier {
        var shown = 0

        override fun showSignInRequired() {
            shown++
        }

        override fun dismiss() = Unit
    }

    private val credentials = Credentials("at-0", "rt-0", "2099-01-01T00:00:00Z", UserInfo("u", "a@b.com", "free"))
    private val log = TunnelLog(mirrorToLogcat = false)
    private val registrar = FakeRegistrar()
    private val notifier = RecordingNotifier()

    private fun push(isSignedIn: () -> Boolean = { true }) = PushCoordinator(
        messaging = FakeMessaging(),
        registrar = registrar,
        store = PushRegistrationStore(InMemoryKeyValueStore()),
        isSignedIn = isSignedIn,
        appVersion = "0.1.0",
        work = PushWorkScheduler.None,
        log = log,
    )

    private val push = push()

    private fun TestScope.tunnel(store: CredentialStore, push: PushCoordinator = this@AppGraphWiringTest.push) = TunnelConnection(
        scope = backgroundScope,
        credentialStore = store,
        refresher = TokenRefresher(store, DeviceFlowClient("ws://127.0.0.1:9", OkHttpClient())),
        sessionFactory = TunnelSessionFactory { _, onEstablished, _, _ -> HangingSession(onEstablished) },
        policy = ReconnectPolicy(),
        networkAvailable = MutableStateFlow(true),
        log = log,
        // Same rule AppGraph uses, on demand with the default window.
        idleTimeoutMs = { idleTimeoutMs(IdleWindow.Minutes30, push.wakeUpsReady) },
        elapsedClock = ElapsedClock { testScheduler.currentTime },
    )

    /**
     * The service as the wake path reaches it: AppGraph's [buildWakeHandler] picks the
     * `callerStartsTunnel` value, [serviceStartExtras] puts it on the intent the way
     * `TunnelForegroundService.start` does, and [startTunnelForServiceStart] is what
     * `onStartCommand` runs on that intent.
     */
    private inner class FakeService(private val tunnel: TunnelConnection) {
        private var pendingIntent: Map<String, Boolean>? = null
        var onStart: () -> Unit = {}

        fun start(callerStartsTunnel: Boolean): Boolean {
            pendingIntent = serviceStartExtras(callerStartsTunnel)
            onStart()
            return true
        }

        /** `onStartCommand` arrives after the FCM callback has returned. */
        fun deliverStartCommand() {
            val extras = checkNotNull(pendingIntent) { "the service was never started" }
            startTunnelForServiceStart({ key, default -> extras[key] ?: default }) { tunnel.start() }
        }
    }

    private fun TestScope.wakeHandler(tunnel: TunnelConnection, service: FakeService, push: PushCoordinator, signedIn: () -> Boolean) =
        buildWakeHandler(
            scope = backgroundScope,
            tunnel = tunnel,
            push = push,
            isSignedIn = signedIn,
            isRemoteEnabled = { true },
            startService = service::start,
            notifier = notifier,
            log = log,
        )

    @Test
    fun `the idle timer is armed when registration succeeds mid-session and disarmed when the new token fails`() = runTest {
        val tunnel = tunnel(InMemoryCredentials(credentials))
        backgroundScope.reevaluateIdleTimerOnReadiness(push.wakeUpsReadyState, tunnel)
        tunnel.start()
        runCurrent()
        assertTrue(tunnel.state.value is TunnelState.Connected)
        assertNull("nothing registered yet: a manual session with no idle stop", tunnel.idleDeadlineMs.value)

        advanceTimeBy(5 * MINUTE)
        push.onAppStart()
        runCurrent()
        assertEquals("armed from the moment wake-ups became possible", 35 * MINUTE, tunnel.idleDeadlineMs.value)

        registrar.result = PushResult.RetryLater("Network error")
        push.onNewToken("token-2")
        runCurrent()
        assertNull("the server can't reach the new token: stay connected", tunnel.idleDeadlineMs.value)

        advanceTimeBy(2 * 60 * MINUTE)
        runCurrent()
        assertTrue(tunnel.state.value is TunnelState.Connected)
        tunnel.stop()
    }

    @Test
    fun `a wake-up whose run ends at once is not started again by the service`() = runTest {
        // Signed in when the wake-up was checked, signed out by the time the run reads credentials.
        val tunnel = tunnel(InMemoryCredentials(null))
        val service = FakeService(tunnel)
        val handler = wakeHandler(tunnel, service, push, signedIn = { true })

        assertEquals(WakeHandler.Outcome.Started, handler.onMessage(mapOf("type" to "wake")))
        runCurrent()
        assertTrue(tunnel.state.value is TunnelState.Stopped)

        service.deliverStartCommand()
        runCurrent()

        assertEquals(1, log.entries.value.count { it.message == "Connecting tunnel" })
        assertTrue((tunnel.state.value as TunnelState.Stopped).signedOut)
        assertEquals("not an expired session", 0, notifier.shown)
    }

    @Test
    fun `a wake-up that passes the signing-out check just before logout begins does not leave the tunnel running`() = runTest {
        val store = InMemoryCredentials(credentials)
        val push = push(isSignedIn = { store.current.value != null })
        val tunnel = tunnel(store, push)
        val service = FakeService(tunnel)
        // The ViewModel's beginLogout() lands while the wake-up starts the service.
        service.onStart = { push.beginLogout() }
        val handler = wakeHandler(tunnel, service, push, signedIn = { store.current.value != null })

        assertEquals(WakeHandler.Outcome.SigningOut, handler.onMessage(mapOf("type" to "wake")))
        service.deliverStartCommand()
        runCurrent()
        push.logout { store.clear() }
        runCurrent()

        assertFalse(tunnel.isRunning)
        assertTrue(tunnel.state.value is TunnelState.Stopped)
        assertEquals(0, notifier.shown)
    }

    @Test
    fun `a woken tunnel stops as signed out once logout clears the credentials`() = runTest {
        val store = InMemoryCredentials(credentials)
        val push = push(isSignedIn = { store.current.value != null })
        val tunnel = tunnel(store, push)
        val service = FakeService(tunnel)
        val handler = wakeHandler(tunnel, service, push, signedIn = { store.current.value != null })

        assertEquals(WakeHandler.Outcome.Started, handler.onMessage(mapOf("type" to "wake")))
        service.deliverStartCommand()
        runCurrent()
        assertTrue(tunnel.state.value is TunnelState.Connected)

        // The logout began after the wake-up's re-check, so only the credentials observer can stop it.
        push.logout { store.clear() }
        runCurrent()

        assertFalse(tunnel.isRunning)
        val stopped = tunnel.state.value as TunnelState.Stopped
        assertTrue(stopped.signedOut)
        assertEquals("Not signed in", stopped.reason)
        assertEquals("a deliberate sign-out posts nothing", 0, notifier.shown)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
