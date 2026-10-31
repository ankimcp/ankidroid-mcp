package ai.ankimcp.ankidroid.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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

/** The Remote tab's account card while signed out, with the whole device-code flow (D59). */
@Composable
internal fun SignInCard(
    state: SignInState,
    sessionEndedReason: String?,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onOpenFailed: (message: String) -> Unit,
    /** Shows a snackbar. */
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Not signed in", style = MaterialTheme.typography.titleMedium)
            SupportingText("Sign in to connect through the AnkiMCP tunnel; works from Claude anywhere, including claude.ai.")
            sessionEndedReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            when (state) {
                SignInState.Idle -> Button(onClick = onSignIn) { Text("Sign in") }

                SignInState.RequestingCode -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("  Requesting a sign-in code…")
                }

                is SignInState.WaitingForUser -> {
                    Text("Your code", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    SelectionContainer {
                        Text(
                            state.userCode,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                        )
                    }
                    Text(
                        "Open the sign-in page and confirm that it shows this code. " +
                            "The app continues automatically once you approve.",
                    )
                    Text(
                        "Signed up with Google or GitHub? Tap that button on the sign-in page; " +
                            "don't type your email into the password form.",
                        fontWeight = FontWeight.Bold,
                    )
                    SelectionContainer { SupportingText(state.verificationUri) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = {
                            val url = state.verificationUriComplete ?: state.verificationUri
                            if (!openInCustomTab(context, url)) {
                                onOpenFailed("Refusing to open a sign-in page that is not https: $url")
                            }
                        }) { Text("Open sign-in page") }
                        FilledTonalButton(onClick = {
                            if (copyToClipboard(context, "Sign-in code", state.userCode)) onMessage("Code copied")
                        }) { Text("Copy code") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  Waiting for approval…", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }

                is SignInState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onSignIn) { Text("Try again") }
                }
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
