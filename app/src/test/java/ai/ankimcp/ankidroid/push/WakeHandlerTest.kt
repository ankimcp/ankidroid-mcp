package ai.ankimcp.ankidroid.push

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.CloseCodes
import ai.ankimcp.ankidroid.tunnel.TunnelState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakeHandlerTest {
    private class RecordingNotifier : SignInNotifier {
        var shown = 0

        override fun showSignInRequired() {
            shown++
        }

        override fun dismiss() = Unit
    }

    private val wake = mapOf("type" to "wake", "reason" to "mcp_request")
    private val tunnelState = MutableStateFlow<TunnelState>(TunnelState.Idle)
    private var running = false
    private var signedIn = true
    private var signingOut = false
    private var serviceStartAllowed = true
    private var onServiceStart: () -> Unit = {}
    private var serviceStarts = 0
    private var tunnelStarts = 0
    private var tunnelStops = 0
    private var nudges = 0
    private val notifier = RecordingNotifier()
    private val log = TunnelLog(mirrorToLogcat = false)

    private fun TestScope.handler() = WakeHandler(
        scope = backgroundScope,
        tunnelState = tunnelState,
        isTunnelRunning = { running },
        isSignedIn = { signedIn },
        isSigningOut = { signingOut },
        startService = {
            serviceStarts++
            onServiceStart()
            serviceStartAllowed
        },
        startTunnel = {
            tunnelStarts++
            running = true
            tunnelState.value = TunnelState.Connecting(0)
        },
        stopTunnel = {
            tunnelStops++
            running = false
            tunnelState.value = TunnelState.Stopped(CloseCodes.NORMAL, "Signing out")
        },
        nudgeTunnel = { nudges++ },
        notifier = notifier,
        log = log,
    )

    @Test
    fun `an open session makes the wake-up a no-op`() = runTest {
        running = true
        tunnelState.value = TunnelState.Connected("https://tunnel.example/mcp")

        assertEquals(WakeHandler.Outcome.AlreadyConnected, handler().onMessage(wake))

        assertEquals(0, serviceStarts)
        assertEquals(0, tunnelStarts)
        assertEquals(0, nudges)
    }

    @Test
    fun `a tunnel waiting to reconnect is nudged instead of restarted`() = runTest {
        running = true
        tunnelState.value = TunnelState.Reconnecting(attempt = 4, delayMs = 16_000, lastError = "1001")

        assertEquals(WakeHandler.Outcome.Nudged, handler().onMessage(wake))

        assertEquals(1, nudges)
        assertEquals(0, serviceStarts)
    }

    @Test
    fun `no session starts the foreground service and the tunnel`() = runTest {
        val outcome = handler().onMessage(wake)
        tunnelState.value = TunnelState.Connected("https://tunnel.example/mcp")
        runCurrent()

        assertEquals(WakeHandler.Outcome.Started, outcome)
        assertEquals(1, serviceStarts)
        assertEquals(1, tunnelStarts)
        assertEquals(0, tunnelStops)
        assertEquals(0, notifier.shown)
    }

    @Test
    fun `a dead refresh token posts the sign-in notification`() = runTest {
        val outcome = handler().onMessage(wake)
        runCurrent()
        // What TunnelConnection publishes when the refresh before connecting gets invalid_grant.
        tunnelState.value = TunnelState.Stopped(0, "Session expired, please sign in again", reloginRequired = true)
        runCurrent()

        assertEquals(WakeHandler.Outcome.Started, outcome)
        assertEquals(1, notifier.shown)
    }

    @Test
    fun `a woken run that finds no credentials posts nothing`() = runTest {
        handler().onMessage(wake)
        runCurrent()
        // Signed out between the check and the attempt: not an expired session.
        tunnelState.value = TunnelState.Stopped(0, "Not signed in", reloginRequired = true, signedOut = true)
        runCurrent()

        assertEquals(0, notifier.shown)
    }

    @Test
    fun `a failure that does not need sign-in posts nothing`() = runTest {
        handler().onMessage(wake)
        runCurrent()
        tunnelState.value = TunnelState.Stopped(CloseCodes.SESSION_REPLACED, "Another device connected")
        runCurrent()

        assertEquals(0, notifier.shown)
    }

    @Test
    fun `the outcome watch gives up after its timeout`() = runTest {
        handler().onMessage(wake)
        advanceTimeBy(WakeHandler.WATCH_TIMEOUT_MS + 1)
        tunnelState.value = TunnelState.Stopped(0, "Session expired", reloginRequired = true)
        runCurrent()

        assertEquals(0, notifier.shown)
    }

    @Test
    fun `signed out logs, posts nothing and does not connect`() = runTest {
        signedIn = false

        assertEquals(WakeHandler.Outcome.NotSignedIn, handler().onMessage(wake))

        assertEquals("a deliberate sign-out is not an expired session", 0, notifier.shown)
        assertEquals(0, serviceStarts)
        assertEquals(0, tunnelStarts)
        val messages = log.entries.value.map { it.message }
        assertTrue(messages.toString(), messages.contains("Wake-up (mcp_request): not signed in, not connecting"))
    }

    @Test
    fun `a wake-up during sign-out does not restart the tunnel`() = runTest {
        // Logout has started; credentials still exist while the DELETE is in flight.
        signingOut = true

        assertEquals(WakeHandler.Outcome.SigningOut, handler().onMessage(wake))

        assertEquals(0, serviceStarts)
        assertEquals(0, tunnelStarts)
        assertEquals(0, notifier.shown)
    }

    @Test
    fun `a logout that begins after the signing-out check stops the run the wake-up started`() = runTest {
        // beginLogout() lands between the entry check and startTunnel().
        onServiceStart = { signingOut = true }

        assertEquals(WakeHandler.Outcome.SigningOut, handler().onMessage(wake))
        runCurrent()

        assertEquals(1, tunnelStarts)
        assertEquals(1, tunnelStops)
        assertFalse(running)
        assertEquals(0, notifier.shown)
        val messages = log.entries.value.map { it.message }
        assertTrue(messages.toString(), messages.contains("Wake-up (mcp_request): signing out, disconnecting"))
    }

    @Test
    fun `a refused service start does not start the tunnel`() = runTest {
        serviceStartAllowed = false

        assertEquals(WakeHandler.Outcome.StartBlocked, handler().onMessage(wake))

        assertEquals(1, serviceStarts)
        assertEquals(0, tunnelStarts)
    }

    @Test
    fun `other message types are ignored`() = runTest {
        val outcome = handler().onMessage(mapOf("type" to "something_else"))

        assertEquals(WakeHandler.Outcome.Ignored, outcome)
        assertTrue(serviceStarts == 0 && tunnelStarts == 0 && notifier.shown == 0)
    }
}
