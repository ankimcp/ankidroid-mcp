package ai.ankimcp.ankidroid.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SignInScreen(
    state: SignInState,
    sessionEndedReason: String?,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onOpenFailed: (message: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sign in to AnkiMCP", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Use the same AnkiMCP account you use with Claude. The connection to your phone goes " +
                "through the AnkiMCP tunnel, which requires an account.",
        )
        sessionEndedReason?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        when (state) {
            SignInState.Idle -> Button(onClick = onSignIn) { Text("Sign in") }

            SignInState.RequestingCode -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text("  Requesting a sign-in code…")
            }

            is SignInState.WaitingForUser -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your code", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.userCode,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                    )
                    Text(
                        "Open the sign-in page and confirm that it shows this code. " +
                            "This screen continues automatically once you approve.",
                    )
                    Button(onClick = {
                        val url = state.verificationUriComplete ?: state.verificationUri
                        if (!openInCustomTab(context, url)) {
                            onOpenFailed("Refusing to open a sign-in page that is not https: $url")
                        }
                    }) { Text("Open sign-in page") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  Waiting for approval…", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }

            is SignInState.Error -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onSignIn) { Text("Try again") }
            }
        }
    }
}

/** Opens [url] only if it is https; returns false (and opens nothing) otherwise. */
private fun openInCustomTab(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    if (!"https".equals(uri.scheme, ignoreCase = true)) return false
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
    return true
}
