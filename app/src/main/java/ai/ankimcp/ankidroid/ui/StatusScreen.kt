package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.auth.UserInfo
import ai.ankimcp.ankidroid.log.LogEntry
import ai.ankimcp.ankidroid.service.TunnelForegroundService
import ai.ankimcp.ankidroid.tunnel.TunnelState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun StatusScreen(
    user: UserInfo,
    tunnelState: TunnelState,
    defaultMcpUrl: String,
    keepConnected: Boolean,
    batteryUnrestricted: Boolean,
    logEntries: List<LogEntry>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onKeepConnectedChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val running = tunnelState !is TunnelState.Idle && tunnelState !is TunnelState.Stopped
    val mcpUrl = (tunnelState as? TunnelState.Connected)?.url ?: defaultMcpUrl
    val locale = LocalLocale.current.platformLocale
    val timeFormat = remember(locale) { SimpleDateFormat("HH:mm:ss", locale) }
    val newestFirst = logEntries.asReversed()

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Signed in", style = MaterialTheme.typography.titleMedium)
                    Text(user.email.ifEmpty { "(no email)" })
                    Text("Plan: ${user.tier}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tunnel", style = MaterialTheme.typography.titleMedium)
                    Text(
                        TunnelForegroundService.describe(tunnelState),
                        color = when (tunnelState) {
                            is TunnelState.Connected -> Color(0xFF2E7D32)
                            is TunnelState.Stopped -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    (tunnelState as? TunnelState.Reconnecting)?.lastError?.takeIf { it.isNotBlank() }?.let {
                        Text("Last error: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("MCP server URL for Claude:", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(mcpUrl, fontFamily = FontFamily.Monospace)
                        }
                        TextButton(onClick = { copyToClipboard(context, mcpUrl) }) { Text("Copy") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (running) {
                            Button(onClick = onDisconnect) { Text("Disconnect") }
                        } else {
                            Button(onClick = onConnect) { Text("Connect") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep connected")
                            Text(
                                "Reconnect automatically when the app starts and after the phone reboots, " +
                                    "and keep retrying every 30 seconds if the connection keeps failing. " +
                                    "Disconnect turns this off.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = keepConnected, onCheckedChange = onKeepConnectedChange)
                    }
                }
            }
        }

        if (!batteryUnrestricted) {
            item { BatteryCard(unrestricted = false) }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onLogout) { Text("Sign out") }
            }
            HorizontalDivider()
        }

        if (newestFirst.isEmpty()) {
            item { Text("No events yet.", style = MaterialTheme.typography.bodySmall) }
        }
        items(newestFirst) { entry ->
            Text(
                "${timeFormat.format(Date(entry.timestampMs))}  ${entry.message}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = when (entry.level) {
                    LogEntry.Level.ERROR -> MaterialTheme.colorScheme.error
                    LogEntry.Level.WARN -> Color(0xFFB26A00)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        item { Text("", Modifier.padding(bottom = 16.dp)) }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("MCP server URL", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
