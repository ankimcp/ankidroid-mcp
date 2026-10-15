package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.auth.UserInfo
import ai.ankimcp.ankidroid.log.LogEntry
import ai.ankimcp.ankidroid.push.PushRegistrationState
import ai.ankimcp.ankidroid.service.FreezingOem
import ai.ankimcp.ankidroid.service.TunnelForegroundService
import ai.ankimcp.ankidroid.settings.ConnectionMode
import ai.ankimcp.ankidroid.settings.IdleWindow
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

/** Connection mode, idle window and push registration, as shown on the Status screen. */
data class ConnectionUi(
    val mode: ConnectionMode,
    val idleWindow: IdleWindow,
    val pushAvailable: Boolean,
    val pushState: PushRegistrationState,
    /** Wake-ups can reach the phone (the current token is registered), so on-demand sessions end when idle. */
    val wakeUpsReady: Boolean,
    /** Wall-clock millis of the next idle disconnect, or null when no idle timer runs. */
    val idleDisconnectAt: Long?,
)

/** Battery exemption state for the Status screen. */
data class BatteryUi(
    val unrestricted: Boolean,
    /** Non-null on makers whose skins freeze background apps. */
    val oem: FreezingOem?,
    val freezeDetected: Boolean,
) {
    enum class Placement { Hidden, Top, Bottom }

    /**
     * Right below the tunnel card when the phone already froze the connection, or on a freezing
     * maker while the exemption is missing. On those makers it stays (lower down) after the
     * exemption is granted, since their own switches can't be read.
     */
    val placement: Placement
        get() = when {
            freezeDetected || (oem != null && !unrestricted) -> Placement.Top
            !unrestricted || oem != null -> Placement.Bottom
            else -> Placement.Hidden
        }
}

@Composable
fun StatusScreen(
    user: UserInfo,
    tunnelState: TunnelState,
    defaultMcpUrl: String,
    connection: ConnectionUi,
    battery: BatteryUi,
    /** False when the permission, the app's notification switch or one of its channels is off. */
    notificationsShown: Boolean,
    logEntries: List<LogEntry>,
    signingOut: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onModeChange: (ConnectionMode) -> Unit,
    onIdleWindowChange: (IdleWindow) -> Unit,
    onDismissFreezeNotice: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val running = tunnelState !is TunnelState.Idle && tunnelState !is TunnelState.Stopped
    val mcpUrl = (tunnelState as? TunnelState.Connected)?.url ?: defaultMcpUrl
    val locale = LocalLocale.current.platformLocale
    val timeFormat = remember(locale) { SimpleDateFormat("HH:mm:ss", locale) }
    val shortTimeFormat = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
    val dateTimeFormat = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
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
                        color = when {
                            tunnelState is TunnelState.Connected -> Color(0xFF2E7D32)
                            tunnelState is TunnelState.Stopped && !tunnelState.idleTimeout && !tunnelState.phoneAsleep ->
                                MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    (tunnelState as? TunnelState.Reconnecting)?.lastError?.takeIf { it.isNotBlank() }?.let {
                        Text("Last error: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    if (running && connection.idleDisconnectAt != null) {
                        Text(
                            "Disconnects when the screen turns off, or at " +
                                "${shortTimeFormat.format(Date(connection.idleDisconnectAt))} unless Claude sends a request",
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                }
            }
        }

        if (battery.placement == BatteryUi.Placement.Top) {
            item { BatteryCard(battery.unrestricted, battery.oem, battery.freezeDetected, onDismissFreezeNotice) }
        }

        item {
            ConnectionModeCard(
                connection = connection,
                formatDateTime = { dateTimeFormat.format(Date(it)) },
                onModeChange = onModeChange,
                onIdleWindowChange = onIdleWindowChange,
            )
        }

        item { NotificationsCard(granted = notificationsShown) }

        if (battery.placement == BatteryUi.Placement.Bottom) {
            item { BatteryCard(battery.unrestricted, battery.oem, battery.freezeDetected, onDismissFreezeNotice) }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onLogout, enabled = !signingOut) {
                    Text(if (signingOut) "Signing out…" else "Sign out")
                }
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

@Composable
private fun ConnectionModeCard(
    connection: ConnectionUi,
    formatDateTime: (Long) -> String,
    onModeChange: (ConnectionMode) -> Unit,
    onIdleWindowChange: (IdleWindow) -> Unit,
) {
    val onDemand = connection.mode == ConnectionMode.OnDemand
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            ModeOption(
                selected = onDemand,
                title = "On demand",
                description = if (connection.wakeUpsReady) {
                    "Claude wakes the phone when it needs your decks. Disconnects when the screen turns " +
                        "off or after ${connection.idleWindow.minutes} min without requests, and does not " +
                        "reconnect when you turn the screen on: Claude's next request wakes it again."
                } else {
                    "Push wake-ups are not available, so this works like a manual connection: tap " +
                        "Connect, and it stays open until you disconnect."
                },
                onSelect = { onModeChange(ConnectionMode.OnDemand) },
            )
            if (onDemand) {
                Text("Disconnect when idle for", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IdleWindow.entries.forEach { window ->
                        FilterChip(
                            selected = connection.idleWindow == window,
                            onClick = { onIdleWindowChange(window) },
                            label = { Text("${window.minutes} min") },
                        )
                    }
                }
            }
            ModeOption(
                selected = !onDemand,
                title = "Always connected",
                description = "Reconnects when the app starts and after a reboot, and keeps retrying every " +
                    "30 seconds. Uses more battery. Disconnect switches back to on demand.",
                onSelect = { onModeChange(ConnectionMode.Always) },
            )
            val (pushText, pushError) = describePush(connection, formatDateTime)
            Text(
                pushText,
                style = MaterialTheme.typography.bodySmall,
                color = if (pushError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, description: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The push status line and whether it is an error. */
private fun describePush(connection: ConnectionUi, formatDateTime: (Long) -> String): Pair<String, Boolean> {
    if (!connection.pushAvailable) return "Push not configured in this build" to true
    val state = connection.pushState
    val message = state.lastMessage ?: "unknown error"
    return when (state.lastResult) {
        PushRegistrationState.LastResult.Retrying -> "Push registration failed, will retry: $message" to true
        PushRegistrationState.LastResult.Failed -> "Push registration failed: $message" to true
        else -> {
            val at = state.registeredAtMs
            if (state.token != null && at != null) {
                "Push wake-ups registered ${formatDateTime(at)}" to false
            } else {
                "Push wake-ups not registered yet" to false
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("MCP server URL", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
