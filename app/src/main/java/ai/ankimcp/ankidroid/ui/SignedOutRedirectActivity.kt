package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.appGraph
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/**
 * Catches Keycloak's `ai.ankimcp.ankidroid://signed-out` redirect (D64) and returns to the
 * existing MainActivity, closing the Custom Tab above it. A trampoline rather than an intent
 * filter on MainActivity: singleTop would stack a second MainActivity on the tab, and singleTask
 * would also clear the sign-in tab or the Play purchase sheet whenever the launcher icon is tapped.
 */
class SignedOutRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        } catch (_: RuntimeException) {
            appGraph.log.warn("Could not return to the app after sign-out")
        }
        finish()
    }
}

/**
 * Opens Keycloak's sign-out page; false if no Custom Tab or browser could. Never logs [url] or
 * an exception message: both can hold the id_token.
 */
internal fun openEndSession(context: Context, url: String): Boolean {
    val uri = url.toUri()
    return try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        true
    } catch (_: RuntimeException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: RuntimeException) {
            false
        }
    }
}
