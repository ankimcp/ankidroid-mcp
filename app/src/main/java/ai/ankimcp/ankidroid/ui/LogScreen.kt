package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.log.LogEntry
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun LogScreen(
    entries: List<LogEntry>,
    /** Builds the redacted text for Share and Copy (D48). */
    report: () -> String,
    onClear: () -> Unit,
    detailedLogging: Boolean,
    /** Wall-clock time detailed logging switches itself off, while it is on. */
    detailedLoggingUntil: Long?,
    onDetailedLoggingChange: (Boolean) -> Unit,
    /** Writes the detailed log file (D61); null if that failed. */
    buildDetailedLog: suspend () -> File?,
    /** Shows a snackbar. */
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val timeFormat = remember(locale) { SimpleDateFormat("HH:mm:ss", locale) }
    val status = LocalStatusColors.current
    val newestFirst = entries.asReversed()
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    val failedText = stringResource(R.string.share_detailed_log_failed)
    val subject = stringResource(R.string.share_detailed_log_subject)

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = detailedLogging,
                    role = Role.Switch,
                    onValueChange = onDetailedLoggingChange,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.detailed_logging_title), style = MaterialTheme.typography.titleSmall)
                SupportingText(stringResource(R.string.detailed_logging_description))
                if (detailedLogging && detailedLoggingUntil != null) {
                    val until = remember(detailedLoggingUntil, locale) {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
                            .format(Date(detailedLoggingUntil))
                    }
                    SupportingText(stringResource(R.string.detailed_logging_until, until))
                }
            }
            Spacer(Modifier.width(16.dp))
            // The whole row toggles; the switch itself is not a separate touch target.
            Switch(checked = detailedLogging, onCheckedChange = null)
        }
        HorizontalDivider()
        // Wraps instead of squeezing the labels at large font scales.
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(
                onClick = { shareLog(context, report()) },
                enabled = entries.isNotEmpty(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Share log")
            }
            OutlinedButton(
                onClick = { if (copyToClipboard(context, "AnkiMCP log", report())) onMessage("Log copied") },
                enabled = entries.isNotEmpty(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Copy")
            }
            TextButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text("Clear") }
            // Enabled with an empty Log tab too: the file log reaches back further than the list.
            OutlinedButton(
                onClick = {
                    preparing = true
                    scope.launch {
                        val file = try {
                            buildDetailedLog()
                        } finally {
                            preparing = false
                        }
                        if (file == null || !shareLogFile(context, file, subject)) onMessage(failedText)
                    }
                },
                enabled = !preparing,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(if (preparing) R.string.share_detailed_log_preparing else R.string.share_detailed_log))
            }
        }
        HorizontalDivider()

        if (newestFirst.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                SupportingText("No events yet. Connection events appear here, newest first.")
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(newestFirst) { entry ->
                    Text(
                        "${timeFormat.format(Date(entry.timestampMs))}  ${entry.message}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (entry.level) {
                            LogEntry.Level.ERROR -> MaterialTheme.colorScheme.error
                            LogEntry.Level.WARN -> status.warning
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private fun shareLog(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "AnkiMCP log")
        .putExtra(Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(Intent.createChooser(send, "Share log"))
    } catch (_: ActivityNotFoundException) {
        // A chooser always resolves; nothing to do on the odd device where it does not.
    }
}

/**
 * Hands [file] to the app the user picks through the share sheet, with read access to that one
 * URI only. False if no URI could be made for it.
 */
private fun shareLogFile(context: Context, file: File, subject: String): Boolean {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}$LOG_FILE_AUTHORITY_SUFFIX", file)
    } catch (_: IllegalArgumentException) {
        return false
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // ClipData carries the grant through the chooser to the picked app.
    send.clipData = ClipData.newRawUri(subject, uri)
    return try {
        context.startActivity(
            Intent.createChooser(send, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Matches `android:authorities="${applicationId}.logs"` in the manifest. */
private const val LOG_FILE_AUTHORITY_SUFFIX = ".logs"
