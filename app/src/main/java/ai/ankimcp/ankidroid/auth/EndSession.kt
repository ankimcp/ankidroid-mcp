package ai.ankimcp.ankidroid.auth

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Where Keycloak sends the browser after sign-out; SignedOutRedirectActivity's intent filter catches it (D64). */
const val SIGNED_OUT_REDIRECT = "ai.ankimcp.ankidroid://signed-out"

/**
 * Keycloak's OIDC RP-initiated logout URL (D64). With [idToken] Keycloak ends the SSO session
 * without asking; without it, [clientId] is sent instead and Keycloak shows a confirm page.
 * The result carries the id_token: never log it.
 */
fun endSessionUrl(authBase: String, idToken: String?, clientId: String, redirect: String): String {
    val params = buildList {
        if (!idToken.isNullOrBlank()) add("id_token_hint" to idToken) else add("client_id" to clientId)
        add("post_logout_redirect_uri" to redirect)
    }
    val query = params.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    return "${authBase.trimEnd('/')}/protocol/openid-connect/logout?$query"
}

// URLEncoder is form encoding; a space must be %20 in a query string.
private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
