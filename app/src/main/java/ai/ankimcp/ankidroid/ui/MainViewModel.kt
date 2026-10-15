package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.anki.AnkiDroidAvailability
import ai.ankimcp.ankidroid.appGraph
import ai.ankimcp.ankidroid.auth.AuthException
import ai.ankimcp.ankidroid.auth.DeviceFlowClient
import ai.ankimcp.ankidroid.service.TunnelForegroundService
import ai.ankimcp.ankidroid.tunnel.TunnelState
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class EnvironmentStatus(
    val ankiInstalled: Boolean,
    val ankiPermissionGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val notificationsGranted: Boolean,
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
    val keepConnected = graph.settings.keepConnected

    /** MCP URL to paste into Claude: replaced by the relay's own URL once connected. */
    val defaultMcpUrl: String = DeviceFlowClient.wsUrlToHttp(graph.config.tunnelUrl).trimEnd('/') + "/mcp"

    private val _environment = MutableStateFlow(readEnvironment())
    val environment: StateFlow<EnvironmentStatus> = _environment.asStateFlow()

    private val _signIn = MutableStateFlow<SignInState>(SignInState.Idle)
    val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

    private var signInJob: Job? = null

    fun refreshEnvironment() {
        _environment.value = readEnvironment()
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
                _signIn.value = SignInState.Idle
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

    /** Also turns off "keep connected" (see [TunnelForegroundService.stop]). */
    fun disconnect() {
        TunnelForegroundService.stop(getApplication())
    }

    fun setKeepConnected(value: Boolean) {
        graph.settings.setKeepConnected(value)
    }

    fun logout() {
        disconnect()
        graph.credentialStore.clear()
        graph.settings.setKeepConnected(false)
        graph.log.info("Signed out")
    }

    /**
     * Auto-connect only when the user opted in; never on first launch (flag defaults to false).
     * Deferred until the process is in the foreground (see [autoConnectWhenForeground]).
     */
    fun autoConnectIfEnabled() {
        viewModelScope.launch {
            autoConnectWhenForeground(
                credentialsLoaded = credentialsLoaded,
                inForeground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
                    .map { it.isAtLeast(Lifecycle.State.STARTED) },
                shouldConnect = {
                    keepConnected.value && credentials.value != null && environment.value.ankiReady &&
                        !graph.tunnel.isRunning
                },
                connect = ::connect,
            )
        }
    }

    private fun readEnvironment(): EnvironmentStatus {
        val app = getApplication<Application>()
        val power = app.getSystemService(PowerManager::class.java)
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return EnvironmentStatus(
            ankiInstalled = graph.ankiAvailability.isInstalled(),
            ankiPermissionGranted = graph.ankiAvailability.hasPermission(),
            batteryUnrestricted = power.isIgnoringBatteryOptimizations(app.packageName),
            notificationsGranted = notificationsGranted,
        )
    }

    private fun describeAuthError(e: AuthException): String = when (e.errorCode) {
        "expired_token" -> "The sign-in code expired. Please try again."
        "access_denied" -> "Sign-in was declined."
        else -> "Sign-in failed: ${e.message}"
    }

    companion object {
        const val ANKI_PERMISSION = AnkiDroidAvailability.PERMISSION
    }
}
