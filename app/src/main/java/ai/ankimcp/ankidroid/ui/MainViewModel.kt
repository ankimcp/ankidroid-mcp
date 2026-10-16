package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.anki.AnkiDroidAvailability
import ai.ankimcp.ankidroid.appGraph
import ai.ankimcp.ankidroid.auth.AuthException
import ai.ankimcp.ankidroid.auth.DeviceFlowClient
import ai.ankimcp.ankidroid.log.LogReport
import ai.ankimcp.ankidroid.log.LogReportHeader
import ai.ankimcp.ankidroid.push.PushRegistrationState
import ai.ankimcp.ankidroid.service.FreezingOem
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun refreshEnvironment() {
        val previous = _environment.value
        val current = readEnvironment()
        _environment.value = current
        graph.freezeNotice.onBatteryExemption(previous.batteryUnrestricted, current.batteryUnrestricted)
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
        TunnelForegroundService.start(getApplication())
    }

    /** Ends the current session; the next wake-up connects again. */
    fun disconnect() {
        TunnelForegroundService.stop(getApplication())
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
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = LogReport.deviceName(Build.MANUFACTURER, Build.MODEL),
            idleWindowMinutes = idleWindow.value.minutes,
            pushRegistered = wakeUpsReady.value,
        ),
        entries = logEntries.value,
        secrets = listOfNotNull(credentials.value?.user?.email, credentials.value?.user?.id?.takeIf { it.length >= 8 }),
    )

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
                    // A wake-up that got past the signing-out check may have started a run meanwhile.
                    graph.tunnel.stop("Signed out")
                    graph.log.info("Signed out")
                } finally {
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
