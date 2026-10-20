package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.local.ClientConfig
import ai.ankimcp.ankidroid.local.LocalServerState
import ai.ankimcp.ankidroid.settings.LocalServerSettings
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The local Wi-Fi server, as shown on the Status screen. */
data class LocalServerUi(
    val enabled: Boolean,
    val port: Int,
    val state: LocalServerState,
    /** "Require access token"; off by default (D55). */
    val requireToken: Boolean = false,
    /** Null until read from the encrypted store. */
    val token: String?,
    /** Why the last token create/regenerate failed, else null. */
    val tokenError: String? = null,
)

/** The status line under the switch and whether it reads as running, waiting or an error. */
internal fun describeLocalServer(state: LocalServerState): Pair<String, LocalStatusKind> = when (state) {
    LocalServerState.Off -> "Starting…" to LocalStatusKind.Waiting
    LocalServerState.NoWifi -> "Connect to Wi-Fi" to LocalStatusKind.Waiting
    LocalServerState.PublicAddress ->
        "This Wi-Fi gives the phone a public internet address, so the server stays off: it would be " +
            "reachable from outside your network." to LocalStatusKind.Error
    is LocalServerState.Running -> "Running on ${state.host}:${state.port}" to LocalStatusKind.Running
    is LocalServerState.Failed -> state.reason to LocalStatusKind.Error
}

internal enum class LocalStatusKind { Running, Waiting, Error }

@Composable
fun LocalServerCard(
    ui: LocalServerUi,
    onEnabledChange: (Boolean) -> Unit,
    /** Returns false when the port is not allowed. */
    onPortChange: (Int) -> Boolean,
    onRegenerateToken: () -> Unit,
    onRequireTokenChange: (Boolean) -> Unit,
    /** Shows a snackbar. */
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Local server (Wi-Fi)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = ui.enabled, onCheckedChange = onEnabledChange)
            }
            SupportingText(
                "Claude Desktop or Claude Code on a computer on the same Wi-Fi connects straight to this " +
                    "phone, without an account. Plain HTTP, no encryption: use it on Wi-Fi you trust. Claude on " +
                    "the web can't reach it.",
            )
            if (!ui.enabled) return@Column

            StatusLine(ui.state)
            val running = ui.state as? LocalServerState.Running
            // The token goes into the share text only while one is required.
            val clientToken = ui.token.takeIf { ui.requireToken }
            if (running != null) {
                Field(label = "MCP server URL", value = running.url)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CopyButton("Copy URL", onMessage = onMessage, message = "URL copied") {
                        copyToClipboard(context, "MCP server URL", running.url)
                    }
                    if (!ui.requireToken || clientToken != null) {
                        OutlinedButton(onClick = { shareText(context, ClientConfig.shareText(running.url, clientToken)) }) {
                            Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Share")
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Require access token", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = ui.requireToken, onCheckedChange = onRequireTokenChange)
            }
            if (!ui.requireToken) {
                Text(
                    stringResource(R.string.local_server_open_warning),
                    color = LocalStatusColors.current.warning,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                // Without a token after a failed start the status line already says why; nothing is being read.
                val failed = ui.state is LocalServerState.Failed
                if (ui.token != null || !failed) TokenSection(ui.token, readFailed = ui.tokenError != null, onRegenerateToken, onMessage)
                // A failed first creation is already the status line's reason; show it here only otherwise (Regenerate).
                ui.tokenError?.takeIf { it != (ui.state as? LocalServerState.Failed)?.reason }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            PortField(ui.port, onPortChange, onMessage)
        }
    }
}

@Composable
private fun StatusLine(state: LocalServerState) {
    val (text, kind) = describeLocalServer(state)
    val status = LocalStatusColors.current
    val color = when (kind) {
        LocalStatusKind.Running -> status.connected
        LocalStatusKind.Waiting -> status.warning
        LocalStatusKind.Error -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(text, color = color, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun TokenSection(token: String?, readFailed: Boolean, onRegenerate: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    var shown by rememberSaveable { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    Text("Access token", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    if (token == null) {
        // A failed read or create is shown right below this section; "Reading" would be untrue then.
        if (!readFailed) SupportingText("Reading the token…")
        return
    }
    Field(value = if (shown) token else "•".repeat(MASKED_LENGTH))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") }
        CopyButton("Copy token", onMessage = onMessage, message = "Token copied") {
            copyToClipboard(context, "Access token", token, sensitive = true)
        }
        TextButton(onClick = { confirmRegenerate = true }) { Text("Regenerate") }
    }
    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("New access token?") },
            text = { Text("Computers set up with the current token stop working until you give them the new one.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegenerate = false
                    onRegenerate()
                }) { Text("Regenerate") }
            },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PortField(port: Int, onPortChange: (Int) -> Boolean, onMessage: (String) -> Unit) {
    var text by rememberSaveable(port) { mutableStateOf(port.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && LocalServerSettings.isValidPort(parsed)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { value -> text = value.filter(Char::isDigit).take(5) },
            label = { Text("Port") },
            singleLine = true,
            isError = !valid,
            supportingText = { if (!valid) Text("${LocalServerSettings.MIN_PORT}-${LocalServerSettings.MAX_PORT}") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        OutlinedButton(
            enabled = valid && parsed != port,
            onClick = { if (parsed != null && onPortChange(parsed)) onMessage("Port set to $parsed") },
        ) { Text("Apply") }
    }
}

@Composable
private fun Field(value: String, label: String? = null) {
    label?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Copy feedback as on the tunnel card (D47): a snackbar up to 12L, the button label on 13+. */
@Composable
private fun CopyButton(label: String, message: String, onMessage: (String) -> Unit, copy: () -> Boolean) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }
    FilledTonalButton(
        onClick = { if (copy()) onMessage(message) else copied = true },
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(if (copied) "Copied" else label)
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "AnkiMCP on my Wi-Fi")
        .putExtra(Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(Intent.createChooser(send, "Share URL and token"))
    } catch (_: ActivityNotFoundException) {
        // A chooser always resolves; nothing to do on the odd device where it does not.
    }
}

private const val MASKED_LENGTH = 16
private const val COPIED_MS = 2_000L
