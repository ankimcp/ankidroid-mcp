package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.local.LocalServerState
import ai.ankimcp.ankidroid.tunnel.TunnelState
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTabsTest {
    @Test
    fun `default is Remote, or Local when signed out with the local server on`() {
        assertEquals(StatusTab.Remote, StatusTabState().selected(signedIn = true, localEnabled = false))
        assertEquals(StatusTab.Remote, StatusTabState().selected(signedIn = true, localEnabled = true))
        assertEquals(StatusTab.Remote, StatusTabState().selected(signedIn = false, localEnabled = false))
        assertEquals(StatusTab.Local, StatusTabState().selected(signedIn = false, localEnabled = true))
    }

    @Test
    fun `a pick holds while the account state stays the same`() {
        val picked = StatusTabState().withAccount(true).pick(StatusTab.Local, signedIn = true)
        assertEquals(StatusTab.Local, picked.withAccount(true).selected(signedIn = true, localEnabled = false))
        val signedOut = StatusTabState().withAccount(false).pick(StatusTab.Remote, signedIn = false)
        assertEquals(StatusTab.Remote, signedOut.withAccount(false).selected(signedIn = false, localEnabled = true))
        val signedOutLocal = StatusTabState().withAccount(false).pick(StatusTab.Local, signedIn = false)
        assertEquals(StatusTab.Local, signedOutLocal.withAccount(false).selected(signedIn = false, localEnabled = false))
    }

    @Test
    fun `a sign-out and sign-in round trip drops the pick`() {
        val picked = StatusTabState().withAccount(true).pick(StatusTab.Local, signedIn = true)
        val signedOut = picked.withAccount(false)
        assertNull(signedOut.choice)
        assertEquals(StatusTab.Remote, signedOut.selected(signedIn = false, localEnabled = false))
        assertEquals(StatusTab.Local, signedOut.selected(signedIn = false, localEnabled = true))
        val signedInAgain = signedOut.withAccount(true)
        assertNull(signedInAgain.choice)
        assertEquals(StatusTab.Remote, signedInAgain.selected(signedIn = true, localEnabled = true))
    }

    @Test
    fun `signing in from a signed-out pick drops the pick`() {
        val picked = StatusTabState().withAccount(false).pick(StatusTab.Local, signedIn = false)
        assertEquals(StatusTab.Remote, picked.withAccount(true).selected(signedIn = true, localEnabled = true))
        assertNull(picked.withAccount(true).choice)
    }

    @Test
    fun `a pick for the other account state is ignored before the flip is seen`() {
        val picked = StatusTabState().withAccount(true).pick(StatusTab.Local, signedIn = true)
        assertEquals(StatusTab.Remote, picked.selected(signedIn = false, localEnabled = false))
        assertEquals(
            StatusTab.Remote,
            StatusTabState(true, StatusTabChoice(StatusTab.Local, signedIn = false)).selected(signedIn = true, localEnabled = false),
        )
    }

    @Test
    fun `an expired session with the server on lands on Remote`() {
        val signedIn = StatusTabState().settle(signedIn = true, localEnabled = true).pick(StatusTab.Local, signedIn = true)
        assertTrue(signedIn.isSignOut(signedIn = false))
        val expired = signedIn.settle(signedIn = false, localEnabled = true)
        assertEquals(StatusTab.Remote, expired.selected(signedIn = false, localEnabled = true))
        assertEquals(StatusTabChoice(StatusTab.Remote, signedIn = false), expired.choice)
    }

    @Test
    fun `stopping the server from its notification keeps the tab on screen`() {
        val firstLaunch = StatusTabState().settle(signedIn = false, localEnabled = true)
        assertFalse(firstLaunch.isSignOut(signedIn = false))
        assertEquals(StatusTab.Local, firstLaunch.selected(signedIn = false, localEnabled = true))
        assertEquals(StatusTab.Local, firstLaunch.selected(signedIn = false, localEnabled = false))
    }

    @Test
    fun `settle keeps a pick made in the current account state`() {
        val picked = StatusTabState().withAccount(false).pick(StatusTab.Remote, signedIn = false)
        assertEquals(picked, picked.settle(signedIn = false, localEnabled = true))
    }

    @Test
    fun `signing in settles on Remote`() {
        val signedOut = StatusTabState().settle(signedIn = false, localEnabled = true)
        val signedIn = signedOut.settle(signedIn = true, localEnabled = true)
        assertFalse(signedOut.isSignOut(signedIn = true))
        assertEquals(StatusTabChoice(StatusTab.Remote, signedIn = true), signedIn.choice)
    }

    @Test
    fun `the saved form round-trips`() {
        listOf(
            StatusTabState(),
            StatusTabState(seenSignedIn = false),
            StatusTabState(true, StatusTabChoice(StatusTab.Local, signedIn = true)),
        ).forEach { state ->
            val saved = with(StatusTabState.Saver) { SaverScope { true }.save(state) }
            assertEquals(state, StatusTabState.Saver.restore(saved!!))
        }
    }

    private val running = LocalServerState.Running("192.168.1.20", 8765)

    private fun remoteDot(state: TunnelState, remoteEnabled: Boolean = true) =
        statusTabDot(StatusTab.Remote, remoteEnabled, state, localEnabled = true, running)

    private fun localDot(enabled: Boolean, state: LocalServerState) =
        statusTabDot(StatusTab.Local, remoteEnabled = false, TunnelState.Connected("https://x/mcp"), enabled, state)

    @Test
    fun `Remote dot is green when connected, amber while a run goes on, grey when stopped`() {
        assertEquals(TabDot.Connected, remoteDot(TunnelState.Connected("https://x/mcp")))
        listOf(TunnelState.WaitingForNetwork, TunnelState.Connecting(1), TunnelState.Reconnecting(1, 1_000, "boom"))
            .forEach { assertEquals("$it", TabDot.Connecting, remoteDot(it)) }
        listOf(
            TunnelState.Idle,
            TunnelState.Stopped(1000, "Idle", idleTimeout = true),
            TunnelState.Stopped(4001, "Session expired", reloginRequired = true),
        ).forEach { assertEquals("$it", TabDot.Off, remoteDot(it)) }
    }

    @Test
    fun `Remote dot is grey with Remote access off, whatever the tunnel state says`() {
        listOf(TunnelState.Connected("https://x/mcp"), TunnelState.Connecting(1), TunnelState.Idle)
            .forEach { assertEquals("$it", TabDot.Off, remoteDot(it, remoteEnabled = false)) }
        assertEquals("the Local dot does not care", TabDot.Connected, localDot(enabled = true, running))
    }

    @Test
    fun `Local dot is green when listening, amber while starting or waiting for Wi-Fi, grey when off or failed`() {
        assertEquals(TabDot.Connected, localDot(enabled = true, running))
        assertEquals(TabDot.Connecting, localDot(enabled = true, LocalServerState.Off))
        assertEquals(TabDot.Connecting, localDot(enabled = true, LocalServerState.NoWifi))
        assertEquals(TabDot.Off, localDot(enabled = true, LocalServerState.PublicAddress))
        assertEquals(TabDot.Off, localDot(enabled = true, LocalServerState.Failed("Port in use")))
        assertEquals(TabDot.Off, localDot(enabled = false, running))
        assertEquals(TabDot.Off, localDot(enabled = false, LocalServerState.Off))
    }

    @Test
    fun `each tab has a TalkBack word for every dot state`() {
        StatusTab.entries.forEach { assertEquals(TabDot.entries.size, it.stateWords.size) }
    }
}
