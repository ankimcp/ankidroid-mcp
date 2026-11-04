package ai.ankimcp.ankidroid.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class EndSessionTest {
    private val base = "https://auth.ankimcp.ai/realms/ankimcp"
    private val logout = "$base/protocol/openid-connect/logout"
    private val redirect = "ai.ankimcp.ankidroid%3A%2F%2Fsigned-out"

    @Test
    fun `id_token is sent as id_token_hint without client_id`() {
        assertEquals(
            "$logout?id_token_hint=eyJ.abc-_.sig&post_logout_redirect_uri=$redirect",
            endSessionUrl(base, "eyJ.abc-_.sig", "ankimcp-cli", SIGNED_OUT_REDIRECT),
        )
    }

    @Test
    fun `missing or blank id_token falls back to client_id`() {
        val expected = "$logout?client_id=ankimcp-cli&post_logout_redirect_uri=$redirect"

        assertEquals(expected, endSessionUrl(base, null, "ankimcp-cli", SIGNED_OUT_REDIRECT))
        assertEquals(expected, endSessionUrl(base, "  ", "ankimcp-cli", SIGNED_OUT_REDIRECT))
    }

    @Test
    fun `values are percent-encoded`() {
        assertEquals(
            "$logout?id_token_hint=a%2Bb%2Fc%3D%26d%20e&post_logout_redirect_uri=https%3A%2F%2Fx.test%2Fdone%3Fa%3D1",
            endSessionUrl(base, "a+b/c=&d e", "ankimcp-cli", "https://x.test/done?a=1"),
        )
    }

    @Test
    fun `trailing slash on the auth base is tolerated`() {
        assertEquals(
            endSessionUrl(base, null, "ankimcp-cli", SIGNED_OUT_REDIRECT),
            endSessionUrl("$base/", null, "ankimcp-cli", SIGNED_OUT_REDIRECT),
        )
    }
}
