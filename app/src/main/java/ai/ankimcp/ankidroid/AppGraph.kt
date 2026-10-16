package ai.ankimcp.ankidroid

import ai.ankimcp.ankidroid.anki.AndroidAnkiDroidAvailability
import ai.ankimcp.ankidroid.anki.AnkiDroidAvailability
import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.ContentResolverAnkiProvider
import ai.ankimcp.ankidroid.auth.CredentialStore
import ai.ankimcp.ankidroid.auth.DeviceFlowClient
import ai.ankimcp.ankidroid.auth.KeystoreCredentialStore
import ai.ankimcp.ankidroid.auth.TokenRefresher
import ai.ankimcp.ankidroid.config.AppConfig
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.mcp.McpServer
import ai.ankimcp.ankidroid.mcp.ToolRegistry
import ai.ankimcp.ankidroid.push.FirebasePushMessaging
import ai.ankimcp.ankidroid.push.PushCoordinator
import ai.ankimcp.ankidroid.push.PushRegistrationStore
import ai.ankimcp.ankidroid.push.PushTokenRegistrar
import ai.ankimcp.ankidroid.push.SignInNotifier
import ai.ankimcp.ankidroid.push.WakeHandler
import ai.ankimcp.ankidroid.push.WorkManagerPushWorkScheduler
import ai.ankimcp.ankidroid.push.WorkManagerQueue
import ai.ankimcp.ankidroid.service.AlarmWakeupScheduler
import ai.ankimcp.ankidroid.service.AndroidElapsedClock
import ai.ankimcp.ankidroid.service.AndroidSignInNotifier
import ai.ankimcp.ankidroid.service.AndroidUptimeClock
import ai.ankimcp.ankidroid.service.AndroidWakeLocks
import ai.ankimcp.ankidroid.service.NetworkMonitor
import ai.ankimcp.ankidroid.service.NotificationPermissionGate
import ai.ankimcp.ankidroid.service.ScreenMonitor
import ai.ankimcp.ankidroid.service.TunnelAlarmReceiver
import ai.ankimcp.ankidroid.service.TunnelForegroundService
import ai.ankimcp.ankidroid.service.notificationBlockReason
import ai.ankimcp.ankidroid.settings.ConnectionSettings
import ai.ankimcp.ankidroid.settings.FreezeNotice
import ai.ankimcp.ankidroid.settings.IdleWindow
import ai.ankimcp.ankidroid.settings.SharedPreferencesKeyValueStore
import ai.ankimcp.ankidroid.tools.ListDecksTool
import ai.ankimcp.ankidroid.tunnel.ProtocolConstants
import ai.ankimcp.ankidroid.tunnel.ReconnectPolicy
import ai.ankimcp.ankidroid.tunnel.TunnelClient
import ai.ankimcp.ankidroid.tunnel.TunnelConnection
import ai.ankimcp.ankidroid.tunnel.TunnelSessionFactory
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-wired process-wide dependencies. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val config: AppConfig = AppConfig.fromBuildConfig()
    private val settingsStore =
        SharedPreferencesKeyValueStore(appContext.getSharedPreferences(ConnectionSettings.PREFS_NAME, Context.MODE_PRIVATE))
    val settings = ConnectionSettings(settingsStore)
    val freezeNotice = FreezeNotice(settingsStore)
    val log = TunnelLog()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val baseHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val authHttp: OkHttpClient = baseHttp.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Application-level heartbeats only; reads block indefinitely between frames. TunnelClient
     * enforces the overall 10 s open timeout (TCP + TLS + upgrade) itself.
     */
    private val tunnelHttp: OkHttpClient = baseHttp.newBuilder()
        .connectTimeout(ProtocolConstants.OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    val credentialStore: CredentialStore = KeystoreCredentialStore(appContext)
    val deviceFlowClient = DeviceFlowClient(config.tunnelUrl, authHttp)
    val tokenRefresher = TokenRefresher(credentialStore, deviceFlowClient)

    val ankiAvailability: AnkiDroidAvailability = AndroidAnkiDroidAvailability(appContext)

    val ankiRepository = AnkiDroidRepository(
        provider = ContentResolverAnkiProvider(appContext.contentResolver),
        availability = ankiAvailability,
        providerExecutor = AnkiDroidRepository.newProviderExecutor(),
    )

    val mcpServer = McpServer(
        registry = ToolRegistry(listOf(ListDecksTool(ankiRepository))),
        serverVersion = config.appVersion,
        log = log,
    )

    val networkMonitor = NetworkMonitor(appContext)
    val screenMonitor = ScreenMonitor(appContext)

    private val wakeLocks = AndroidWakeLocks(appContext)

    val notificationPermission = NotificationPermissionGate(log) { channelId -> notificationBlockReason(appContext, channelId) }

    val push = PushCoordinator(
        messaging = FirebasePushMessaging(appContext),
        registrar = PushTokenRegistrar(
            tunnelWsUrl = config.tunnelUrl,
            http = authHttp,
            credentialStore = credentialStore,
            refresher = tokenRefresher,
            appVersion = config.appVersion,
            log = log,
        ),
        store = PushRegistrationStore(
            SharedPreferencesKeyValueStore(
                appContext.getSharedPreferences(PushRegistrationStore.PREFS_NAME, Context.MODE_PRIVATE),
            ),
        ),
        isSignedIn = { credentialStore.load() != null },
        appVersion = config.appVersion,
        work = WorkManagerPushWorkScheduler(WorkManagerQueue(appContext)),
        log = log,
    )

    val tunnel = TunnelConnection(
        scope = appScope,
        credentialStore = credentialStore,
        refresher = tokenRefresher,
        sessionFactory = TunnelSessionFactory { credentials, onEstablished, onRequest, onRequestDone ->
            TunnelClient(
                http = tunnelHttp,
                url = config.tunnelUrl,
                accessToken = credentials.accessToken,
                clientVersion = config.appVersion,
                handler = mcpServer,
                log = log,
                clock = AndroidElapsedClock,
                wakeLocks = wakeLocks,
                onEstablished = onEstablished,
                onRequest = onRequest,
                onRequestDone = onRequestDone,
                uptimeClock = AndroidUptimeClock,
            )
        },
        policy = ReconnectPolicy(),
        networkAvailable = networkMonitor.available,
        log = log,
        wakeLocks = wakeLocks,
        wakeups = AlarmWakeupScheduler(appContext),
        idleTimeoutMs = { idleTimeoutMs(settings.idleWindow.value, push.wakeUpsReady) },
        idleWakeups = AlarmWakeupScheduler(
            appContext,
            requestCode = TunnelAlarmReceiver.IDLE_REQUEST_CODE,
            action = TunnelAlarmReceiver.ACTION_IDLE_DEADLINE,
        ),
        elapsedClock = AndroidElapsedClock,
        uptimeClock = AndroidUptimeClock,
        onFrozen = { freezeNotice.markDetected() },
        isScreenOn = { appContext.getSystemService(PowerManager::class.java).isInteractive },
    )

    init {
        appScope.reevaluateIdleTimerOnReadiness(push.wakeUpsReadyState, tunnel)
    }

    val signInNotifier: SignInNotifier = AndroidSignInNotifier(appContext, notificationPermission)

    val wakeHandler: WakeHandler = buildWakeHandler(
        scope = appScope,
        tunnel = tunnel,
        push = push,
        isSignedIn = { credentialStore.load() != null },
        startService = { callerStartsTunnel -> TunnelForegroundService.start(appContext, callerStartsTunnel) },
        notifier = signInNotifier,
        log = log,
    )
}

/**
 * Sessions end after the idle window, but only when a push wake-up can bring the tunnel back;
 * without push, a session is a manual one that stays up until Disconnect.
 */
internal fun idleTimeoutMs(window: IdleWindow, wakeUpsReady: Boolean): Long? =
    if (wakeUpsReady) window.millis else null

/**
 * The idle timer reads [wakeUpsReady] only when it is (re)armed, so a registration that succeeds
 * or breaks mid-session must re-arm it: otherwise a session could stay up forever, or keep
 * disconnecting while the server can no longer wake the phone.
 */
internal fun CoroutineScope.reevaluateIdleTimerOnReadiness(wakeUpsReady: StateFlow<Boolean>, tunnel: TunnelConnection): Job =
    launch(start = CoroutineStart.UNDISPATCHED) {
        wakeUpsReady.collect { tunnel.onIdleSettingsChanged() }
    }

/**
 * Wake-ups start the service with `callerStartsTunnel = true` (see
 * [ai.ankimcp.ankidroid.service.startTunnelUnlessCallerDid]) and own the tunnel run themselves;
 * [startService] is `TunnelForegroundService.start` in the app.
 */
internal fun buildWakeHandler(
    scope: CoroutineScope,
    tunnel: TunnelConnection,
    push: PushCoordinator,
    isSignedIn: () -> Boolean,
    startService: (callerStartsTunnel: Boolean) -> Boolean,
    notifier: SignInNotifier,
    log: TunnelLog,
): WakeHandler = WakeHandler(
    scope = scope,
    tunnelState = tunnel.state,
    isTunnelRunning = { tunnel.isRunning },
    isSignedIn = isSignedIn,
    isSigningOut = { push.isSigningOut },
    startService = { startService(true) },
    startTunnel = { tunnel.start(wokenByPush = true) },
    stopTunnel = tunnel::stop,
    wakeRunningTunnel = tunnel::onPushWake,
    notifier = notifier,
    log = log,
)
