package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.auth.UserInfo
import ai.ankimcp.ankidroid.push.PushRegistrationState
import ai.ankimcp.ankidroid.service.FreezingOem
import ai.ankimcp.ankidroid.service.TunnelForegroundService
import ai.ankimcp.ankidroid.settings.IdleWindow
import ai.ankimcp.ankidroid.tunnel.TunnelState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/** The Plan card's actions (D62). */
class PlanCallbacks(
    val onSubscribe: (productId: String) -> Unit,
    val onRefresh: () -> Unit,
    val onRetry: () -> Unit,
    val onDismiss: () -> Unit,
)

/** Idle window and push registration, as shown on the Status screen. */
data class ConnectionUi(
    val idleWindow: IdleWindow,
    val pushAvailable: Boolean,
    val pushState: PushRegistrationState,
    /** Wake-ups can reach the phone (the current token is registered), so sessions end when idle. */
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
)

/** The one battery card below the Status tabs, always shown (D59). */
internal enum class StatusBattery {
    /** The D42 card: settings links and the maker's own switches, no direct request. */
    Tunnel,

    /** The local server's card with the direct exemption request (D56). */
    Local,
}

/**
 * Which battery card: the local server's while it is on (D56), the D42 card otherwise. Either
 * shows a tick once exempt, plus the maker's own switches on freezing OEMs.
 */
internal fun statusBattery(localEnabled: Boolean): StatusBattery =
    if (localEnabled) StatusBattery.Local else StatusBattery.Tunnel

/** What the Notifications card's "off" text talks about. */
internal enum class NotificationsUse {
    /** Signed in: wake-ups, the session notification and "Sign in again". */
    Tunnel,

    /** Signed out with the local server on: its notification and Stop button. */
    Local,

    /** Signed out, server off: nothing runs yet. */
    Idle,
}

internal fun notificationsUse(signedIn: Boolean, localEnabled: Boolean): NotificationsUse = when {
    signedIn -> NotificationsUse.Tunnel
    localEnabled -> NotificationsUse.Local
    else -> NotificationsUse.Idle
}

@Composable
fun StatusScreen(
    /** Null while signed out: Remote shows only the sign-in card, Local works as usual. */
    user: UserInfo?,
    signIn: SignInState,
    /** Why the last session ended, shown on the sign-in card. */
    sessionEndedReason: String?,
    tunnelState: TunnelState,
    defaultMcpUrl: String,
    connection: ConnectionUi,
    plan: PlanCardUi,
    battery: BatteryUi,
    local: LocalServerUi,
    /** False when the permission, the app's notification switch or one of its channels is off. */
    notificationsShown: Boolean,
    signingOut: Boolean,
    selectedTab: StatusTab,
    onSelectTab: (StatusTab) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onIdleWindowChange: (IdleWindow) -> Unit,
    onDismissFreezeNotice: () -> Unit,
    onLogout: () -> Unit,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onSignInOpenFailed: (String) -> Unit,
    onLocalEnabledChange: (Boolean) -> Unit,
    onLocalPortChange: (Int) -> Boolean,
    onRegenerateLocalToken: () -> Unit,
    onLocalRequireTokenChange: (Boolean) -> Unit,
    plans: PlanCallbacks,
    /** Shows a snackbar. */
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val shortTimeFormat = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
    val dateTimeFormat = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.padding(top = 4.dp)) }

        item { DocumentationCard() }

        item {
            StatusTabRow(
                selected = selectedTab,
                dot = { statusTabDot(it, tunnelState, local.enabled, local.state) },
                onSelect = onSelectTab,
            )
        }

        when (selectedTab) {
            StatusTab.Local -> item {
                LocalServerCard(
                    ui = local,
                    onEnabledChange = onLocalEnabledChange,
                    onPortChange = onLocalPortChange,
                    onRegenerateToken = onRegenerateLocalToken,
                    onRequireTokenChange = onLocalRequireTokenChange,
                    onMessage = onMessage,
                )
            }
            StatusTab.Remote -> if (user == null) {
                item {
                    SignInCard(
                        state = signIn,
                        sessionEndedReason = sessionEndedReason,
                        onSignIn = onSignIn,
                        onCancel = onCancelSignIn,
                        onOpenFailed = onSignInOpenFailed,
                        onMessage = onMessage,
                    )
                }
            } else {
                remoteTab(
                    user = user,
                    tunnelState = tunnelState,
                    defaultMcpUrl = defaultMcpUrl,
                    connection = connection,
                    plan = plan,
                    plans = plans,
                    signingOut = signingOut,
                    formatTime = { shortTimeFormat.format(Date(it)) },
                    formatDateTime = { dateTimeFormat.format(Date(it)) },
                    formatDate = { dateFormat.format(Date.from(it)) },
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onIdleWindowChange = onIdleWindowChange,
                    onLogout = onLogout,
                    onMessage = onMessage,
                )
            }
        }

        // Shared below either tab.
        if (battery.freezeDetected) item { FreezeNoticeCard(onDismiss = onDismissFreezeNotice) }
        when (statusBattery(local.enabled)) {
            StatusBattery.Local -> item { LocalBatteryCard(battery.unrestricted, battery.oem, battery.freezeDetected) }
            StatusBattery.Tunnel -> item { BatteryCard(battery.unrestricted, battery.oem, battery.freezeDetected) }
        }
        item { NotificationsCard(granted = notificationsShown, use = notificationsUse(user != null, local.enabled)) }

        item { Spacer(Modifier.padding(bottom = 4.dp)) }
    }
}

private fun LazyListScope.remoteTab(
    user: UserInfo,
    tunnelState: TunnelState,
    defaultMcpUrl: String,
    connection: ConnectionUi,
    plan: PlanCardUi,
    plans: PlanCallbacks,
    signingOut: Boolean,
    formatTime: (Long) -> String,
    formatDateTime: (Long) -> String,
    formatDate: (Instant) -> String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onIdleWindowChange: (IdleWindow) -> Unit,
    onLogout: () -> Unit,
    onMessage: (String) -> Unit,
) {
    item {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Signed in", style = MaterialTheme.typography.titleMedium)
                    Text(user.email.ifEmpty { "(no email)" })
                    Text(
                        "Plan: ${user.tier}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onLogout, enabled = !signingOut) {
                    Text(if (signingOut) "Signing out…" else "Sign out")
                }
            }
        }
    }

    item {
        PlanCard(
            ui = plan,
            formatDate = formatDate,
            onSubscribe = plans.onSubscribe,
            onRefresh = plans.onRefresh,
            onRetry = plans.onRetry,
            onDismiss = plans.onDismiss,
        )
    }

    item {
        TunnelCard(
            tunnelState = tunnelState,
            defaultMcpUrl = defaultMcpUrl,
            idleDisconnectAt = connection.idleDisconnectAt,
            formatTime = formatTime,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onMessage = onMessage,
        )
    }
    item { ConnectionCard(connection = connection, formatDateTime = formatDateTime, onIdleWindowChange = onIdleWindowChange) }
}

/** "The phone paused the app" (D42 `FreezeNotice`), right above the battery card. */
@Composable
private fun FreezeNoticeCard(onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.freeze_notice_title), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.freeze_notice_text),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun TunnelCard(
    tunnelState: TunnelState,
    defaultMcpUrl: String,
    idleDisconnectAt: Long?,
    formatTime: (Long) -> String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val running = tunnelState !is TunnelState.Idle && tunnelState !is TunnelState.Stopped
    val mcpUrl = (tunnelState as? TunnelState.Connected)?.url ?: defaultMcpUrl
    val status = LocalStatusColors.current
    val stateColor = when {
        tunnelState is TunnelState.Connected -> status.connected
        tunnelState is TunnelState.Stopped && !tunnelState.idleTimeout && !tunnelState.phoneAsleep ->
            MaterialTheme.colorScheme.error
        running -> status.warning
        else -> null
    }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_LABEL_MS)
            copied = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tunnel", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(stateColor ?: MaterialTheme.colorScheme.outline, CircleShape))
                Text(
                    TunnelForegroundService.describe(tunnelState),
                    color = stateColor ?: Color.Unspecified,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            (tunnelState as? TunnelState.Reconnecting)?.lastError?.takeIf { it.isNotBlank() }?.let {
                SupportingText("Last error: $it")
            }
            if (running && idleDisconnectAt != null) {
                SupportingText(
                    "Disconnects when the screen turns off, or at ${formatTime(idleDisconnectAt)} " +
                        "unless Claude sends a request",
                )
            }
            if (running) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
            } else {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            }

            Text(
                "MCP server URL for Claude",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(mcpUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
            FilledTonalButton(
                onClick = {
                    // One confirmation only: the snackbar on Android 12L and lower, the label otherwise.
                    if (copyToClipboard(context, "MCP server URL", mcpUrl)) {
                        onMessage("MCP server URL copied")
                    } else {
                        copied = true
                    }
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(
                    painterResource(R.drawable.ic_copy),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(if (copied) "Copied" else "Copy URL")
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    connection: ConnectionUi,
    formatDateTime: (Long) -> String,
    onIdleWindowChange: (IdleWindow) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            SupportingText(describeConnection(connection))
            Text("Disconnect when idle for", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IdleWindow.entries.forEach { window ->
                    FilterChip(
                        selected = connection.idleWindow == window,
                        onClick = { onIdleWindowChange(window) },
                        label = { Text("${window.minutes} min") },
                    )
                }
            }
            val (pushText, pushError) = describePush(connection, formatDateTime)
            Text(
                pushText,
                style = MaterialTheme.typography.bodySmall,
                color = if (pushError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The one-line explanation on the Connection card. */
internal fun describeConnection(connection: ConnectionUi): String =
    if (connection.wakeUpsReady) {
        "Claude wakes your phone when it needs your decks; the connection closes when the screen turns " +
            "off or after ${connection.idleWindow.minutes} min without requests."
    } else {
        "Push wake-ups are not available, so this works like a manual connection: tap Connect, and it " +
            "stays open until you disconnect."
    }

@Composable
internal fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
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

private const val COPIED_LABEL_MS = 2_000L
