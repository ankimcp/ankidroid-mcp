package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.push.PushRegistrationState
import ai.ankimcp.ankidroid.settings.IdleWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCardTest {
    private fun connection(wakeUpsReady: Boolean) = ConnectionUi(
        idleWindow = IdleWindow.Minutes15,
        pushAvailable = true,
        pushState = PushRegistrationState(),
        wakeUpsReady = wakeUpsReady,
        idleDisconnectAt = null,
    )

    @Test
    fun `with wake-ups the card explains wake and close in one line`() {
        assertEquals(
            "Claude wakes your phone when it needs your decks; the connection closes when the screen turns " +
                "off or after 15 min without requests.",
            describeConnection(connection(wakeUpsReady = true)),
        )
    }

    @Test
    fun `without wake-ups it says the connection is manual`() {
        assertTrue(describeConnection(connection(wakeUpsReady = false)).contains("stays open until you disconnect"))
    }
}
