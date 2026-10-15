package ai.ankimcp.ankidroid.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(
    environment: EnvironmentStatus,
    onEnvironmentChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        onEnvironmentChanged()
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set up AnkiDroid MCP", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This app lets an AI assistant such as Claude read your AnkiDroid decks while your phone is " +
                "connected. It needs AnkiDroid and permission to access its database.",
        )

        StepCard(
            title = "1. AnkiDroid",
            done = environment.ankiInstalled,
            doneText = "AnkiDroid is installed.",
            todoText = "AnkiDroid is not installed. Install it, open it once to finish its setup, then come back.",
        ) {
            Button(onClick = { openPlayStore(context, "com.ichi2.anki") }) { Text("Install AnkiDroid") }
        }

        StepCard(
            title = "2. Access to AnkiDroid",
            done = environment.ankiPermissionGranted,
            doneText = "Permission granted.",
            todoText = "Allow this app to read your AnkiDroid collection.",
        ) {
            Button(
                enabled = environment.ankiInstalled,
                onClick = { permissionLauncher.launch(MainViewModel.ANKI_PERMISSION) },
            ) { Text("Grant access") }
            if (permissionDenied) {
                Text(
                    "Permission was denied. If Android no longer shows the prompt, enable it under " +
                        "App info > Permissions > Additional permissions.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
            }
        }

        BatteryCard(environment.batteryUnrestricted)
    }
}

@Composable
fun BatteryCard(unrestricted: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    StepCard(
        title = "Optional: battery",
        done = unrestricted,
        doneText = "Battery optimisation is off for this app, so the connection survives Doze.",
        todoText = "Android may cut the connection when the phone is idle. To keep it alive, find " +
            "AnkiDroid MCP in the list and choose \"Don't optimise\" (or \"Unrestricted\").",
        modifier = modifier,
    ) {
        OutlinedButton(onClick = { openBatterySettings(context) }) { Text("Open battery settings") }
    }
}

@Composable
fun NotificationsCard(granted: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    StepCard(
        title = "Notifications",
        done = granted,
        doneText = "Notifications are allowed.",
        todoText = "Notifications are off. When Claude wakes the phone you won't see that it connected, " +
            "you won't be told when you need to sign in again, and Android may deliver wake-ups late.",
        modifier = modifier,
    ) {
        OutlinedButton(onClick = { openNotificationSettings(context) }) { Text("Open notification settings") }
    }
}

@Composable
private fun StepCard(
    title: String,
    done: Boolean,
    doneText: String,
    todoText: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (done) "✓ $doneText" else todoText)
            if (!done) actions()
        }
    }
}

private fun openPlayStore(context: Context, packageName: String) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
    try {
        context.startActivity(market)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(web) }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }
}

/** Opens the system list; we deliberately do not use REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (Play policy). */
private fun openBatterySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        openAppSettings(context)
    }
}

private fun openNotificationSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    } catch (_: ActivityNotFoundException) {
        openAppSettings(context)
    }
}
