package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.billing.CurrentActivity
import ai.ankimcp.ankidroid.tunnel.TunnelState
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnkiMcpTheme { AppRoot(viewModel) }
        }
    }

    override fun onResume() {
        super.onResume()
        CurrentActivity.resumed(this)
    }

    override fun onDestroy() {
        CurrentActivity.destroyed(this)
        super.onDestroy()
    }
}

/** Why the session ended, for the sign-in card; nothing after a deliberate sign-out. */
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
    val idleWindow by viewModel.idleWindow.collectAsStateWithLifecycle()
    val pushState by viewModel.pushState.collectAsStateWithLifecycle()
    val wakeUpsReady by viewModel.wakeUpsReady.collectAsStateWithLifecycle()
    val idleDisconnectAt by viewModel.idleDisconnectAt.collectAsStateWithLifecycle()
    val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()
    val freezeDetected by viewModel.freezeDetected.collectAsStateWithLifecycle()
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()
    val detailedLogging by viewModel.detailedLogging.collectAsStateWithLifecycle()
    val detailedLoggingUntil by viewModel.detailedLoggingUntil.collectAsStateWithLifecycle()
    val localEnabled by viewModel.localServerEnabled.collectAsStateWithLifecycle()
    val localPort by viewModel.localServerPort.collectAsStateWithLifecycle()
    val localState by viewModel.localServerState.collectAsStateWithLifecycle()
    val localToken by viewModel.localServerToken.collectAsStateWithLifecycle()
    val localTokenError by viewModel.localServerTokenError.collectAsStateWithLifecycle()
    val localRequireToken by viewModel.localServerRequireToken.collectAsStateWithLifecycle()
    val remoteEnabled by viewModel.remoteEnabled.collectAsStateWithLifecycle()
    val billing by viewModel.billing.collectAsStateWithLifecycle()
    val localUi = LocalServerUi(
        enabled = localEnabled,
        port = localPort,
        state = localState,
        requireToken = localRequireToken,
        token = localToken,
        tokenError = localTokenError,
    )

    LifecycleResumeEffect(Unit) {
        viewModel.refreshEnvironment()
        viewModel.ensureLocalServer()
        // Also picks up purchases completed while the app was closed (D62).
        viewModel.refreshBilling()
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

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }
    var tab by rememberSaveable { mutableStateOf(MainTab.Status) }
    val user = credentials?.user
    // Past onboarding: the tabbed Status screen and the bottom bar, signed in or out.
    val ready = environment.ankiReady && credentialsLoaded
    LaunchedEffect(environment.ankiReady) { if (!environment.ankiReady) tab = MainTab.Status }
    var statusTabs by rememberSaveable(stateSaver = StatusTabState.Saver) { mutableStateOf(StatusTabMemory.state) }
    val updateStatusTabs = { next: StatusTabState ->
        statusTabs = next
        StatusTabMemory.state = next
    }
    val accountSignedIn = user != null
    // Not while credentials are still loading: after process death that would discard the restored tab.
    LaunchedEffect(credentialsLoaded, accountSignedIn) {
        if (!credentialsLoaded) return@LaunchedEffect
        // App start: credentials are decrypted after the first resume, so the resume refresh saw no account.
        if (accountSignedIn) viewModel.refreshBilling()
        // A sign-out or an ended session goes to Status, where Remote shows the sign-in card.
        if (statusTabs.isSignOut(accountSignedIn)) tab = MainTab.Status
        updateStatusTabs(statusTabs.settle(accountSignedIn, localEnabled))
    }
    BackHandler(enabled = ready && tab != MainTab.Status) { tab = MainTab.Status }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OwlMark(size = 28.dp)
                        Text("AnkiMCP", Modifier.padding(start = 10.dp))
                    }
                },
            )
        },
        bottomBar = {
            if (ready) {
                NavigationBar {
                    MainTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when {
            !environment.ankiReady -> OnboardingScreen(
                environment = environment,
                onEnvironmentChanged = viewModel::refreshEnvironment,
                modifier = contentModifier,
            )
            !credentialsLoaded -> Box(contentModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            tab == MainTab.Log -> LogScreen(
                entries = logEntries,
                report = viewModel::logReport,
                onClear = viewModel::clearLog,
                detailedLogging = detailedLogging,
                detailedLoggingUntil = detailedLoggingUntil,
                onDetailedLoggingChange = viewModel::setDetailedLogging,
                buildDetailedLog = viewModel::detailedLogFile,
                onMessage = showMessage,
                modifier = contentModifier,
            )
            tab == MainTab.About -> AboutScreen(appVersion = viewModel.appVersion, modifier = contentModifier)
            else -> StatusScreen(
                user = user,
                signIn = signIn,
                sessionEndedReason = sessionEndedReason(tunnelState),
                remoteEnabled = remoteEnabled,
                tunnelState = tunnelState,
                defaultMcpUrl = viewModel.defaultMcpUrl,
                connection = ConnectionUi(
                    idleWindow = idleWindow,
                    pushAvailable = viewModel.pushAvailable,
                    pushState = pushState,
                    wakeUpsReady = wakeUpsReady,
                    idleDisconnectAt = idleDisconnectAt,
                ),
                plan = planCardUi(billing, userTier = user?.tier.orEmpty()),
                battery = BatteryUi(
                    unrestricted = environment.batteryUnrestricted,
                    oem = viewModel.freezingOem,
                    freezeDetected = freezeDetected,
                ),
                local = localUi,
                notificationsShown = environment.notificationsShown,
                signingOut = signingOut,
                selectedTab = statusTabs.selected(accountSignedIn, localEnabled),
                onSelectTab = { picked -> updateStatusTabs(statusTabs.pick(picked, accountSignedIn)) },
                onRemoteEnabledChange = viewModel::setRemoteEnabled,
                onConnect = connect,
                onDisconnect = viewModel::disconnect,
                onIdleWindowChange = viewModel::setIdleWindow,
                onDismissFreezeNotice = viewModel::dismissFreezeNotice,
                onLogout = viewModel::logout,
                onSignIn = viewModel::startSignIn,
                onCancelSignIn = viewModel::cancelSignIn,
                onSignInOpenFailed = viewModel::failSignIn,
                onLocalEnabledChange = viewModel::setLocalServerEnabled,
                onLocalPortChange = viewModel::setLocalServerPort,
                onRegenerateLocalToken = viewModel::regenerateLocalServerToken,
                onLocalRequireTokenChange = viewModel::setLocalServerRequireToken,
                plans = remember(viewModel) {
                    PlanCallbacks(
                        onSubscribe = viewModel::subscribe,
                        onRefresh = { viewModel.refreshBilling(force = true) },
                        onRetry = viewModel::retryBilling,
                        onDismiss = viewModel::dismissBillingMessage,
                    )
                },
                onMessage = showMessage,
                modifier = contentModifier,
            )
        }
    }
}

/** Top-level destinations once signed in (D47). */
internal enum class MainTab(val label: String, @param:DrawableRes val icon: Int) {
    Status("Status", R.drawable.ic_nav_status),
    Log("Log", R.drawable.ic_nav_log),
    About("About", R.drawable.ic_nav_about),
}
