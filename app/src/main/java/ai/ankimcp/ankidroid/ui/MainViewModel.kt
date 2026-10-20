package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.anki.AnkiDroidAvailability
import ai.ankimcp.ankidroid.appGraph
import ai.ankimcp.ankidroid.auth.AuthException
import ai.ankimcp.ankidroid.auth.DeviceFlowClient
import ai.ankimcp.ankidroid.billing.BillingState
import ai.ankimcp.ankidroid.billing.CurrentActivity
import ai.ankimcp.ankidroid.local.LocalServerState
import ai.ankimcp.ankidroid.BuildConfig
import ai.ankimcp.ankidroid.log.DetailedReport
import ai.ankimcp.ankidroid.log.DetailedReportInfo
import ai.ankimcp.ankidroid.log.LogReport
import ai.ankimcp.ankidroid.log.LogReportHeader
import ai.ankimcp.ankidroid.push.PushRegistrationState
import ai.ankimcp.ankidroid.service.FreezingOem
import ai.ankimcp.ankidroid.service.REMOTE_ACCESS_OFF_REASON
import ai.ankimcp.ankidroid.service.TunnelForegroundService
import ai.ankimcp.ankidroid.service.allNotificationsShown
import ai.ankimcp.ankidroid.service.hasNotificationPermission
import ai.ankimcp.ankidroid.settings.IdleWindow
import ai.ankimcp.ankidroid.tunnel.TunnelState
import android.app.Application
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class EnvironmentStatus(
    val ankiInstalled: Boolean,
    val ankiPermissionGranted: Boolean,
    val batteryUnrestricted: Boolean,
    /** POST_NOTIFICATIONS (Android 13+); what the runtime permission request can change. */
    val notificationsGranted: Boolean,
    /** The permission, the app's notification switch and every channel are on. */
    val notificationsShown: Boolean,
) {
    val ankiReady: Boolean get() = ankiInstalled && ankiPermissionGranted
}

sealed interface SignInState {
    data object Idle : SignInState

    data object RequestingCode : SignInState

    data class WaitingForUser(
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
    ) : SignInState

    data class Error(val message: String) : SignInState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.appGraph

    val credentials = graph.credentialStore.current

    /** False while the stored credentials are still being decrypted in the background. */
    val credentialsLoaded = graph.credentialStore.loaded
    val tunnelState: StateFlow<TunnelState> = graph.tunnel.state
    val logEntries = graph.log.entries
    val idleWindow: StateFlow<IdleWindow> = graph.settings.idleWindow
    val pushState: StateFlow<PushRegistrationState> = graph.push.state

    /** The current FCM token is registered, so sessions end when idle. */
    val wakeUpsReady: StateFlow<Boolean> = graph.push.wakeUpsReadyState

    /** False when this build has no `google-services.json`. */
    val pushAvailable: Boolean = graph.push.isAvailable

    /** Wall-clock time at which the idle timer will disconnect, or null when it is not running. */
    val idleDisconnectAt: StateFlow<Long?> = graph.tunnel.idleDeadlineMs
        .map { deadline -> deadline?.let { System.currentTimeMillis() + (it - SystemClock.elapsedRealtime()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val appVersion: String = graph.config.appVersion

    /** MCP URL to paste into Claude: replaced by the relay's own URL once connected. */
    val defaultMcpUrl: String = DeviceFlowClient.wsUrlToHttp(graph.config.tunnelUrl).trimEnd('/') + "/mcp"

    private val _environment = MutableStateFlow(readEnvironment())
    val environment: StateFlow<EnvironmentStatus> = _environment.asStateFlow()

    private val _signIn = MutableStateFlow<SignInState>(SignInState.Idle)
    val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

    private val _signingOut = MutableStateFlow(false)

    /** True while logout removes the push token before clearing credentials. */
    val signingOut: StateFlow<Boolean> = _signingOut.asStateFlow()

    private val _notificationPermissionRequests = Channel<Unit>(Channel.CONFLATED)

    /**
     * Asks the activity to request POST_NOTIFICATIONS (Android 13+) after sign-in, since a woken
     * session's notification and the "Sign in again" notification are otherwise invisible.
     */
    val notificationPermissionRequests: Flow<Unit> = _notificationPermissionRequests.receiveAsFlow()

    private var signInJob: Job? = null

    /** The phone froze a running tunnel; cleared once battery optimisation is turned off. */
    val freezeDetected: StateFlow<Boolean> = graph.freezeNotice.detected

    /** Non-null on makers whose skins freeze background apps (D42). */
    val freezingOem: FreezingOem? = FreezingOem.current()

    /** The Remote access switch (D63). */
    val remoteEnabled: StateFlow<Boolean> = graph.remoteSettings.enabled

    val localServerEnabled: StateFlow<Boolean> = graph.localSettings.enabled
    val localServerPort: StateFlow<Int> = graph.localSettings.port
    val localServerRequireToken: StateFlow<Boolean> = graph.localSettings.requireToken
    val localServerState: StateFlow<LocalServerState> = graph.localServer.state

    /** The local server's bearer token once read; shown and shared, never logged. */
    val localServerToken: StateFlow<String?> = graph.localTokens.token

    /** Why creating, regenerating or reading the token failed last, for the Local server card. */
    val localServerTokenError: StateFlow<String?> =
        combine(graph.localTokens.saveError, graph.localTokens.readError) { save, read -> save ?: read }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        if (localServerEnabled.value && localServerRequireToken.value) loadLocalServerToken()
    }

    /** The Plan card's state (D62). */
    val billing: StateFlow<BillingState> = graph.billing.state

    /**
     * Plan status, Play products and restore of unregistered purchases. Called when the app comes
     * to the front signed in (throttled in the coordinator) and from the card's Refresh.
     */
    fun refreshBilling(force: Boolean = false) {
        if (credentials.value != null) graph.billing.refresh(force)
    }

    /** The Activity is looked up only when Play's sheet opens, after `/prepare` (D62). */
    fun subscribe(productId: String) {
        graph.billing.subscribe(productId, CurrentActivity::host)
    }

    fun retryBilling() = graph.billing.retry()

    fun dismissBillingMessage() = graph.billing.dismiss()

    fun refreshEnvironment() {
        val previous = _environment.value
        val current = readEnvironment()
        _environment.value = current
        graph.freezeNotice.onBatteryExemption(previous.batteryUnrestricted, current.batteryUnrestricted)
        // The auto-off timer does not run while the phone sleeps; the switch must not show a stale "on".
        graph.detailedLogging.checkExpiry()
    }

    fun dismissFreezeNotice() {
        graph.freezeNotice.dismiss()
    }

    fun startSignIn() {
        if (signInJob?.isActive == true) return
        signInJob = viewModelScope.launch {
            _signIn.value = SignInState.RequestingCode
            try {
                val code = graph.deviceFlowClient.requestDeviceCode()
                _signIn.value = SignInState.WaitingForUser(
                    userCode = code.userCode,
                    verificationUri = code.verificationUri,
                    verificationUriComplete = code.verificationUriComplete,
                )
                val credentials = graph.deviceFlowClient.pollForToken(
                    code.deviceCode,
                    code.intervalSeconds,
                    code.expiresInSeconds,
                )
                graph.credentialStore.save(credentials)
                graph.log.info("Signed in")
                graph.signInNotifier.dismiss()
                // App scope: registration must not be cancelled when the screen goes away.
                graph.appScope.launch(Dispatchers.IO) { graph.push.onSignedIn() }
                graph.billing.onSignedIn()
                _signIn.value = SignInState.Idle
                requestNotificationPermission()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                _signIn.value = SignInState.Error(describeAuthError(e))
            } catch (e: Exception) {
                _signIn.value = SignInState.Error("Sign-in failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        _signIn.value = SignInState.Idle
    }

    /** Aborts sign-in with [message], e.g. when the sign-in page URL is not https. */
    fun failSignIn(message: String) {
        signInJob?.cancel()
        signInJob = null
        _signIn.value = SignInState.Error(message)
    }

    fun connect() {
        if (!remoteEnabled.value) return
        TunnelForegroundService.start(getApplication())
    }

    /** Ends the current session; the next wake-up connects again. */
    fun disconnect() {
        TunnelForegroundService.stop(getApplication())
    }

    /**
     * Off: ends the session (the service stops unless the local server is on), ignores wake-ups
     * and removes the push token from the relay, so Claude's requests no longer wake the phone.
     * On: registers the token again; nothing connects until Connect or the next wake-up.
     */
    fun setRemoteEnabled(enabled: Boolean) {
        if (graph.remoteSettings.isEnabled == enabled) return
        graph.remoteSettings.setEnabled(enabled)
        graph.log.info(if (enabled) "Remote access turned on" else "Remote access turned off")
        // App scope: the DELETE or PUT must not be cancelled when the screen goes away.
        if (enabled) {
            graph.appScope.launch(Dispatchers.IO) { graph.push.onRemoteEnabled() }
        } else {
            TunnelForegroundService.stop(getApplication(), REMOTE_ACCESS_OFF_REASON)
            graph.appScope.launch(Dispatchers.IO) { graph.push.onRemoteDisabled() }
        }
    }

    /**
     * Called while the app is in front: (re)starts the service for a local server that is on
     * but not running, e.g. after the process was killed. Starting it from here is allowed on
     * Android 12+; nothing restarts it in the background.
     */
    fun ensureLocalServer() {
        if (localServerEnabled.value && !graph.localServer.isStarted) TunnelForegroundService.startLocalServer(getApplication())
    }

    fun setLocalServerEnabled(enabled: Boolean) {
        if (graph.localSettings.enabled.value == enabled) return
        graph.localSettings.setEnabled(enabled)
        graph.log.info(if (enabled) "Local server turned on" else "Local server turned off")
        // Turning it off is handled by the running service, which stops the server (and itself
        // unless a tunnel runs).
        if (enabled) {
            if (localServerRequireToken.value) loadLocalServerToken()
            TunnelForegroundService.startLocalServer(getApplication())
            // Its notification carries "Stop local server"; signed-out users were never asked yet.
            requestNotificationPermission()
        }
    }

    /** False for a port outside 1024-65535; a running server moves to the new port. */
    fun setLocalServerPort(port: Int): Boolean {
        if (port == graph.localSettings.port.value) return true
        val changed = graph.localSettings.setPort(port)
        if (changed) graph.log.info("Local server port set to $port")
        return changed
    }

    /**
     * "Require access token". Turning it on creates the token the first time (and reads it
     * later); it applies to the very next request, no restart. Off: anyone on the Wi-Fi can use
     * the server; Host and Origin are still checked.
     */
    fun setLocalServerRequireToken(required: Boolean) {
        if (graph.localSettings.requireToken.value == required) return
        if (required) loadLocalServerToken()
        graph.localSettings.setRequireToken(required)
        graph.log.info(if (required) "Local server: access token required" else "Local server: access token not required")
    }

    /**
     * Clients configured with the old token are refused until they are given the new one. A
     * Keystore failure keeps the old token and shows [localServerTokenError].
     */
    fun regenerateLocalServerToken() {
        graph.appScope.launch(Dispatchers.IO) { graph.localServer.regenerateToken() }
    }

    private fun loadLocalServerToken() {
        graph.appScope.launch(Dispatchers.IO) { graph.localTokens.getOrCreate() }
    }

    fun setIdleWindow(window: IdleWindow) {
        if (graph.settings.idleWindow.value == window) return
        graph.settings.setIdleWindow(window)
        graph.tunnel.onIdleSettingsChanged()
    }

    fun clearLog() {
        graph.log.clear()
    }

    /** The log as shared text: device header, no account details, token- and email-shaped text redacted. */
    fun logReport(): String = LogReport.format(
        header = LogReportHeader(
            appVersion = appVersion,
            androidVersion = androidVersion(),
            device = LogReport.deviceName(Build.MANUFACTURER, Build.MODEL),
            idleWindowMinutes = idleWindow.value.minutes,
            pushRegistered = wakeUpsReady.value,
        ),
        entries = logEntries.value,
        secrets = graph.redactionSecrets(),
    )

    /** "Detailed logging" on the Log tab (D61): debug lines and this app's logcat, for 24 h. */
    val detailedLogging: StateFlow<Boolean> = graph.detailedLogging.enabled

    /** When detailed logging switches itself off, or null when it is off. */
    val detailedLoggingUntil: StateFlow<Long?> = graph.detailedLogging.until

    fun setDetailedLogging(on: Boolean) {
        if (graph.detailedLogging.enabled.value == on) return
        graph.detailedLogging.setEnabled(on)
        graph.log.info(if (on) "Detailed logging turned on for 24 hours" else "Detailed logging turned off")
    }

    /**
     * Builds the detailed log (D61) in `cacheDir/shared_logs/` and returns it for the share sheet,
     * or null if it could not be written. Nothing is sent anywhere by the app.
     */
    suspend fun detailedLogFile(): File? = withContext(Dispatchers.IO) {
        try {
            val detailed = graph.detailedLogging.isEnabled()
            val fileLog = try {
                graph.fileLog.read()
            } catch (e: Exception) {
                "(the app log could not be read: ${e.javaClass.simpleName})\n"
            }
            val logcat = if (detailed) graph.logcat.dump() else null
            val environment = readEnvironment()
            val now = System.currentTimeMillis()
            val text = DetailedReport.format(
                info = DetailedReportInfo(
                    appVersion = appVersion,
                    build = "${BuildConfig.VERSION_CODE} ${BuildConfig.BUILD_TYPE}",
                    androidVersion = androidVersion(),
                    device = LogReport.deviceName(Build.MANUFACTURER, Build.MODEL),
                    romBuild = Build.DISPLAY.orEmpty(),
                    oemSkin = freezingOem?.let { "${it.name} (${it.displayName})" },
                    ankiDroidVersion = graph.ankiAvailability.version()?.let { "${it.name} (${it.code})" },
                    idleWindowMinutes = idleWindow.value.minutes,
                    pushRegistered = wakeUpsReady.value,
                    localServerEnabled = localServerEnabled.value,
                    localServerPort = localServerPort.value,
                    localTokenRequired = localServerRequireToken.value,
                    detailedLoggingUntil = graph.detailedLogging.until.value,
                    batteryUnrestricted = environment.batteryUnrestricted,
                    notificationsGranted = environment.notificationsGranted,
                    notificationsShown = environment.notificationsShown,
                    generatedAtMs = now,
                ),
                fileLog = fileLog,
                logcat = logcat,
                secrets = graph.redactionSecrets(),
            )
            graph.sharedLogs.write(text, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.log.warn("Could not build the detailed log: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun androidVersion() = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /** Removes the push token server-side first; credentials are cleared once that is done. */
    fun logout() {
        if (_signingOut.value) return
        _signingOut.value = true
        // Before the disconnect, so a wake-up arriving in between cannot reconnect.
        graph.push.beginLogout()
        try {
            disconnect()
            graph.appScope.launch(Dispatchers.IO) {
                try {
                    graph.push.logout { graph.credentialStore.clear() }
                    graph.billing.onSignedOut()
                    // A wake-up that got past the signing-out check may have started a run meanwhile.
                    graph.tunnel.stop("Signed out")
                    graph.log.info("Signed out")
                } finally {
                    graph.ankiRepository.releaseProviderClient("sign-out")
                    _signingOut.value = false
                }
            }
        } catch (e: Exception) {
            // Nothing was cleared: leave the button usable and wake-ups/registrations working.
            graph.push.abortLogout()
            _signingOut.value = false
            graph.log.warn("Could not sign out: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun requestNotificationPermission() {
        if (!hasNotificationPermission(getApplication())) _notificationPermissionRequests.trySend(Unit)
    }

    private fun readEnvironment(): EnvironmentStatus {
        val app = getApplication<Application>()
        val power = app.getSystemService(PowerManager::class.java)
        return EnvironmentStatus(
            ankiInstalled = graph.ankiAvailability.isInstalled(),
            ankiPermissionGranted = graph.ankiAvailability.hasPermission(),
            batteryUnrestricted = power.isIgnoringBatteryOptimizations(app.packageName),
            notificationsGranted = hasNotificationPermission(app),
            notificationsShown = allNotificationsShown(app),
        )
    }

    private fun describeAuthError(e: AuthException): String = when (e.errorCode) {
        "expired_token" -> "The sign-in code expired. Please try again."
        "access_denied" -> "Sign-in was declined."
        else -> "Sign-in failed: ${e.message}"
    }

    companion object {
        const val ANKI_PERMISSION = AnkiDroidAvailability.PERMISSION
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
