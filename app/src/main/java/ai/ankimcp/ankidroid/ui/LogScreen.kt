package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.log.LogEntry
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun LogScreen(
    entries: List<LogEntry>,
    /** Builds the redacted text for Share and Copy (D48). */
    report: () -> String,
    onClear: () -> Unit,
    /** Shows a snackbar. */
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val timeFormat = remember(locale) { SimpleDateFormat("HH:mm:ss", locale) }
    val status = LocalStatusColors.current
    val newestFirst = entries.asReversed()

    Column(modifier) {
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
                onClick = { if (copyToClipboard(context, "AnkiDroid MCP log", report())) onMessage("Log copied") },
                enabled = entries.isNotEmpty(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Copy")
            }
            TextButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text("Clear") }
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
        .putExtra(Intent.EXTRA_SUBJECT, "AnkiDroid MCP log")
        .putExtra(Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(Intent.createChooser(send, "Share log"))
    } catch (_: ActivityNotFoundException) {
        // A chooser always resolves; nothing to do on the odd device where it does not.
    }
}
