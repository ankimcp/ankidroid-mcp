package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.anki.AnkiDroidAvailability
import ai.ankimcp.ankidroid.service.FreezingOem
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
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
        Text("Set up AnkiMCP", style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onboarding_intro))

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

        BatteryCard(environment.batteryUnrestricted, oem = FreezingOem.current(), freezeDetected = false)
    }
}

/**
 * Optional battery exemption. On makers whose skins freeze background apps ([oem]) it also names
 * the maker's own switches, which the system exemption does not cover. [freezeDetected] makes
 * it required; the freeze notice itself is its own card above the Status tabs (D59).
 */
@Composable
fun BatteryCard(
    unrestricted: Boolean,
    oem: FreezingOem?,
    freezeDetected: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Not optional on a freezing maker without the exemption, or once a freeze happened.
            val needed = freezeDetected || (oem != null && !unrestricted)
            Text(if (needed || unrestricted) "Battery" else "Optional: battery", style = MaterialTheme.typography.titleMedium)
            if (unrestricted) {
                Text("✓ Battery optimisation is off for AnkiMCP.")
                if (freezeDetected) SupportingText(STILL_PAUSED_TEXT)
            } else {
                Text(
                    if (oem != null) {
                        "This phone pauses background apps soon after the screen turns off, even while " +
                            "Claude is using them. Both AnkiMCP and AnkiDroid must be allowed to run " +
                            "in the background, or Claude's requests time out."
                    } else {
                        "Android may pause the app when the phone is idle, which cuts the connection once " +
                            "you use it. To keep it alive, find " +
                            "AnkiMCP in the list and choose \"Don't optimise\" (or \"Unrestricted\")."
                    },
                )
            }
            if (oem != null) SupportingText(oem.instructions)
            when {
                // The maker's switches live on each app's own page; there is no public intent for its battery sub-page.
                oem != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { openAppSettings(context) }) { Text("AnkiMCP info") }
                    OutlinedButton(onClick = { openAppSettings(context, AnkiDroidAvailability.PACKAGE_NAME) }) {
                        Text("AnkiDroid info")
                    }
                }
                !unrestricted -> OutlinedButton(onClick = { openBatterySettings(context) }) { Text("Open battery settings") }
                freezeDetected -> OutlinedButton(onClick = { openAppSettings(context) }) { Text("Open app info") }
            }
        }
    }
}

/** Under the battery tick after a freeze: the system exemption alone did not keep the app running. */
internal const val STILL_PAUSED_TEXT = "The phone still paused the app; check its background settings in App info."

@Composable
internal fun NotificationsCard(granted: Boolean, use: NotificationsUse, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    StepCard(
        title = "Notifications",
        done = granted,
        doneText = "Notifications are allowed.",
        todoText = when (use) {
            NotificationsUse.Local ->
                "Notifications are off. You won't see that the local server is running, or its Stop button."
            NotificationsUse.Tunnel ->
                "Notifications are off. When Claude wakes the phone you won't see that it connected, " +
                    "you won't be told when you need to sign in again, and Android may deliver wake-ups late."
            NotificationsUse.Idle ->
                "Notifications are off. Once you sign in or turn on the local server, you won't see when " +
                    "the app is connected or running, or its Disconnect and Stop buttons."
        },
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

private fun openAppSettings(context: Context, packageName: String = context.packageName) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }
}

/** The system list; no direct exemption request for the tunnel (docs/DECISIONS.md D42, D56). */
internal fun openBatterySettings(context: Context) {
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
