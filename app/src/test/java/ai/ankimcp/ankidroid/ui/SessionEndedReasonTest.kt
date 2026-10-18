package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.tunnel.CloseCodes
import ai.ankimcp.ankidroid.tunnel.TunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionEndedReasonTest {
    @Test
    fun `an expired or revoked session is explained on the sign-in card`() {
        val expired = TunnelState.Stopped(0, "Session expired, please sign in again", reloginRequired = true)

        assertEquals("Session expired, please sign in again", sessionEndedReason(expired))
    }

    @Test
    fun `a deliberate sign-out shows no reason`() {
        val signedOut = TunnelState.Stopped(CloseCodes.NORMAL, "Not signed in", reloginRequired = true, signedOut = true)

        assertNull(sessionEndedReason(signedOut))
    }

    @Test
    fun `other states show no reason`() {
        assertNull(sessionEndedReason(TunnelState.Stopped(CloseCodes.NORMAL, "Disconnected by user")))
        assertNull(sessionEndedReason(TunnelState.Idle))
    }
}
