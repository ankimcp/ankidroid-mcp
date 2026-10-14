package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.tunnel.TunnelState
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) viewModel.autoConnectIfEnabled()
        setContent {
            AnkiMcpTheme { AppRoot(viewModel) }
        }
    }
}

/** Why the session ended, for the sign-in screen; nothing after a deliberate sign-out. */
internal fun sessionEndedReason(state: TunnelState): String? =
    (state as? TunnelState.Stopped)?.takeIf { it.reloginRequired && !it.signedOut }?.reason

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(viewModel: MainViewModel) {
    val environment by viewModel.environment.collectAsStateWithLifecycle()
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()
    val credentialsLoaded by viewModel.credentialsLoaded.collectAsStateWithLifecycle()
    val signIn by viewModel.signIn.collectAsStateWithLifecycle()
    val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
    val connectionMode by viewModel.connectionMode.collectAsStateWithLifecycle()
    val idleWindow by viewModel.idleWindow.collectAsStateWithLifecycle()
    val pushState by viewModel.pushState.collectAsStateWithLifecycle()
    val wakeUpsReady by viewModel.wakeUpsReady.collectAsStateWithLifecycle()
    val idleDisconnectAt by viewModel.idleDisconnectAt.collectAsStateWithLifecycle()
    val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refreshEnvironment()
        onPauseOrDispose { }
    }

    // Connect regardless of the answer: without it the service still runs, just without a visible notification.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshEnvironment()
        viewModel.connect()
    }
    val connect = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !environment.notificationsGranted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.connect()
        }
    }

    // After sign-in and when on demand is chosen. Only while started: sign-in usually completes
    // while the browser is in front, and the request waits until the user is back.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshEnvironment()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.notificationPermissionRequests.collect {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { CenterAlignedTopAppBar(title = { Text("AnkiDroid MCP") }) },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        val user = credentials?.user
        when {
            !environment.ankiReady -> OnboardingScreen(
                environment = environment,
                onEnvironmentChanged = viewModel::refreshEnvironment,
                modifier = contentModifier,
            )
            !credentialsLoaded -> Box(contentModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            user == null -> SignInScreen(
                state = signIn,
                sessionEndedReason = sessionEndedReason(tunnelState),
                onSignIn = viewModel::startSignIn,
                onCancel = viewModel::cancelSignIn,
                onOpenFailed = viewModel::failSignIn,
                modifier = contentModifier,
            )
            else -> StatusScreen(
                user = user,
                tunnelState = tunnelState,
                defaultMcpUrl = viewModel.defaultMcpUrl,
                connection = ConnectionUi(
                    mode = connectionMode,
                    idleWindow = idleWindow,
                    pushAvailable = viewModel.pushAvailable,
                    pushState = pushState,
                    wakeUpsReady = wakeUpsReady,
                    idleDisconnectAt = idleDisconnectAt,
                ),
                batteryUnrestricted = environment.batteryUnrestricted,
                notificationsShown = environment.notificationsShown,
                logEntries = logEntries,
                signingOut = signingOut,
                onConnect = connect,
                onDisconnect = viewModel::disconnect,
                onModeChange = viewModel::setConnectionMode,
                onIdleWindowChange = viewModel::setIdleWindow,
                onLogout = viewModel::logout,
                modifier = contentModifier,
            )
        }
    }
}
