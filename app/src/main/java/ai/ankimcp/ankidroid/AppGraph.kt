package ai.ankimcp.ankidroid

import ai.ankimcp.ankidroid.anki.AndroidAnkiDroidAvailability
import ai.ankimcp.ankidroid.anki.AnkiDroidAvailability
import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.ContentResolverAnkiProvider
import ai.ankimcp.ankidroid.auth.CredentialStore
import ai.ankimcp.ankidroid.auth.DeviceFlowClient
import ai.ankimcp.ankidroid.auth.KeystoreCredentialCipher
import ai.ankimcp.ankidroid.auth.KeystoreCredentialStore
import ai.ankimcp.ankidroid.auth.SharedPreferencesBlobStorage
import ai.ankimcp.ankidroid.auth.TokenRefresher
import ai.ankimcp.ankidroid.billing.BillingApi
import ai.ankimcp.ankidroid.billing.BillingCoordinator
import ai.ankimcp.ankidroid.billing.PlayBilling
import ai.ankimcp.ankidroid.billing.RegisteredPurchases
import ai.ankimcp.ankidroid.config.AppConfig
import ai.ankimcp.ankidroid.local.LocalServer
import ai.ankimcp.ankidroid.local.LocalServerTokenStore
import ai.ankimcp.ankidroid.local.NanoHttpServerEngine
import ai.ankimcp.ankidroid.log.FileLogSink
import ai.ankimcp.ankidroid.log.LogEntry
import ai.ankimcp.ankidroid.log.LogcatReader
import ai.ankimcp.ankidroid.log.ProcessCommandRunner
import ai.ankimcp.ankidroid.log.SharedLogFiles
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.log.startPeriodicFlush
import ai.ankimcp.ankidroid.mcp.McpServer
import ai.ankimcp.ankidroid.mcp.PromptRegistry
import ai.ankimcp.ankidroid.mcp.ResourceRegistry
import ai.ankimcp.ankidroid.mcp.Tool
import ai.ankimcp.ankidroid.mcp.ToolRegistry
import ai.ankimcp.ankidroid.prompts.AddonPrompts
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
import ai.ankimcp.ankidroid.service.FreezingOem
import ai.ankimcp.ankidroid.service.NetworkMonitor
import ai.ankimcp.ankidroid.service.NotificationPermissionGate
import ai.ankimcp.ankidroid.service.ScreenMonitor
import ai.ankimcp.ankidroid.service.TunnelAlarmReceiver
import ai.ankimcp.ankidroid.service.TunnelForegroundService
import ai.ankimcp.ankidroid.service.WifiMonitor
import ai.ankimcp.ankidroid.service.notificationBlockReason
import ai.ankimcp.ankidroid.settings.ConnectionSettings
import ai.ankimcp.ankidroid.settings.DetailedLogging
import ai.ankimcp.ankidroid.settings.FreezeNotice
import ai.ankimcp.ankidroid.settings.IdleWindow
import ai.ankimcp.ankidroid.settings.LocalServerSettings
import ai.ankimcp.ankidroid.settings.RemoteAccessSettings
import ai.ankimcp.ankidroid.settings.SharedPreferencesKeyValueStore
import ai.ankimcp.ankidroid.anki.AnkiDroidSync
import ai.ankimcp.ankidroid.service.NotificationSyncRequester
import ai.ankimcp.ankidroid.tools.AddNoteTool
import ai.ankimcp.ankidroid.tools.AddNotesTool
import ai.ankimcp.ankidroid.tools.CardBuryTool
import ai.ankimcp.ankidroid.tools.CardChangeDeckTool
import ai.ankimcp.ankidroid.tools.CardSetFlagTool
import ai.ankimcp.ankidroid.tools.CardSuspendTool
import ai.ankimcp.ankidroid.tools.CardsStatsTool
import ai.ankimcp.ankidroid.tools.CreateDeckTool
import ai.ankimcp.ankidroid.tools.CreateModelTool
import ai.ankimcp.ankidroid.tools.DeleteNotesTool
import ai.ankimcp.ankidroid.tools.FilteredDeckGetInfoTool
import ai.ankimcp.ankidroid.tools.FindNotesTool
import ai.ankimcp.ankidroid.tools.ModelFieldAddTool
import ai.ankimcp.ankidroid.tools.RateCardTool
import ai.ankimcp.ankidroid.tools.RatingGuard
import ai.ankimcp.ankidroid.tools.SyncTool
import ai.ankimcp.ankidroid.tools.TagAddTool
import ai.ankimcp.ankidroid.tools.TagBatchTool
import ai.ankimcp.ankidroid.tools.TagRemoveTool
import ai.ankimcp.ankidroid.tools.TagReplaceTool
import ai.ankimcp.ankidroid.tools.UpdateModelStylingTool
import ai.ankimcp.ankidroid.tools.UpdateModelTemplatesTool
import ai.ankimcp.ankidroid.tools.UpdateNoteFieldsTool
import ai.ankimcp.ankidroid.tools.UpdateNotesTool
import ai.ankimcp.ankidroid.tools.GetCardMemoryStateTool
import ai.ankimcp.ankidroid.tools.GetDueCardsTool
import ai.ankimcp.ankidroid.tools.GetFsrsParamsTool
import ai.ankimcp.ankidroid.tools.ListDecksTool
import ai.ankimcp.ankidroid.tools.ModelFieldNamesTool
import ai.ankimcp.ankidroid.tools.ModelNamesTool
import ai.ankimcp.ankidroid.tools.ModelStylingTool
import ai.ankimcp.ankidroid.tools.ModelTemplatesTool
import ai.ankimcp.ankidroid.tools.NotesInfoTool
import ai.ankimcp.ankidroid.tools.PresentCardTool
import ai.ankimcp.ankidroid.tools.TagListTool
import ai.ankimcp.ankidroid.tunnel.ProtocolConstants
import ai.ankimcp.ankidroid.tunnel.ReconnectPolicy
import ai.ankimcp.ankidroid.tunnel.RequestDedupe
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import android.os.Process as AndroidProcess

/** Hand-wired process-wide dependencies. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val config: AppConfig = AppConfig.fromBuildConfig()
    private val settingsStore =
        SharedPreferencesKeyValueStore(appContext.getSharedPreferences(ConnectionSettings.PREFS_NAME, Context.MODE_PRIVATE))
    val settings = ConnectionSettings(settingsStore)
    val freezeNotice = FreezeNotice(settingsStore)

    /** The Remote access switch (D63); read by the push coordinator, the wake handler and the UI. */
    val remoteSettings = RemoteAccessSettings(settingsStore)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Read by [redactionSecrets]; the stores it reads are created further down. */
    @Volatile private var secretsReady = false

    /** The Log tab's 24 h switch (D61); the expiry line is logged from the app scope, never inside the log's lock. */
    val detailedLogging: DetailedLogging = DetailedLogging(
        settingsStore,
        onExpired = { appScope.launch { log.info("Detailed logging turned off: 24 hours passed") } },
    )
    private val fileLogExecutor = FileLogSink.newExecutor()

    /** Always-on rotating file log (D61), fed by [log]. */
    val fileLog = FileLogSink(
        dir = File(appContext.filesDir, FILE_LOG_DIR),
        executor = fileLogExecutor,
        isDetailed = detailedLogging::isEnabled,
        secrets = ::redactionSecrets,
    )

    init {
        // File only, and first: marks process restarts (e.g. after the phone killed us overnight)
        // without cluttering the Log tab, ahead of anything this process logs.
        fileLog.append(LogEntry(System.currentTimeMillis(), LogEntry.Level.INFO, "Process started (pid ${AndroidProcess.myPid()})"))
    }

    val log: TunnelLog = TunnelLog(sinks = listOf(fileLog))
    val logcat = LogcatReader(ProcessCommandRunner(), pid = AndroidProcess::myPid)
    val sharedLogs = SharedLogFiles(File(appContext.cacheDir, SharedLogFiles.DIR_NAME))

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

    private val billingApi = BillingApi(config.tunnelUrl, authHttp, credentialStore, tokenRefresher, log)

    /** Google Play subscriptions (D62); Play is bound on first use, never from a push-woken process alone. */
    val billing = BillingCoordinator(
        scope = appScope,
        gateway = PlayBilling(appContext, log),
        backend = billingApi,
        statusSource = billingApi,
        registered = RegisteredPurchases(
            SharedPreferencesKeyValueStore(
                appContext.getSharedPreferences(RegisteredPurchases.PREFS_NAME, Context.MODE_PRIVATE),
            ),
        ),
        log = log,
        onTier = ::applyTier,
        elapsedMs = AndroidElapsedClock::elapsedMs,
    )

    init {
        // Any end of the account resets billing, not only Sign out: TokenRefresher clears the
        // credentials on invalid_grant and TunnelConnection on revoked access (D62).
        appScope.launch {
            credentialStore.current.map { it?.user?.id }.distinctUntilChanged().collect(billing::onAccountChanged)
        }
    }

    val ankiAvailability: AnkiDroidAvailability = AndroidAnkiDroidAvailability(appContext)

    val ankiRepository = AnkiDroidRepository(
        provider = ContentResolverAnkiProvider(appContext.contentResolver, log, AndroidElapsedClock),
        availability = ankiAvailability,
        providerExecutor = AnkiDroidRepository.newProviderExecutor(),
        log = log,
        clock = AndroidElapsedClock,
        freezingOemName = FreezingOem.current()?.displayName,
    )

    val mcpServer = McpServer(
        // notificationPermission is set up further down; the lambda reads it only when sync is called.
        registry = ToolRegistry(
            ankiTools(ankiRepository) { NotificationSyncRequester(appContext, notificationPermission).requestSync() },
        ),
        serverVersion = config.appVersion,
        log = log,
        prompts = PromptRegistry(AddonPrompts.prompts),
        resources = ResourceRegistry(AddonPrompts.resources),
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
        isRemoteEnabled = remoteSettings::isEnabled,
        appVersion = config.appVersion,
        work = WorkManagerPushWorkScheduler(WorkManagerQueue(appContext)),
        log = log,
    )

    private val requestDedupe = RequestDedupe(AndroidElapsedClock)

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
                dedupe = requestDedupe,
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

    val localSettings = LocalServerSettings(settingsStore)

    val localTokens = LocalServerTokenStore(
        storage = SharedPreferencesBlobStorage(
            appContext.getSharedPreferences(LocalServerTokenStore.PREFS_NAME, Context.MODE_PRIVATE),
        ),
        cipher = KeystoreCredentialCipher(),
        warn = log::warn,
    )

    /** Same MCP server, tools and repository as the tunnel (D53). */
    val localServer = LocalServer(
        scope = appScope,
        settings = localSettings,
        tokens = localTokens,
        wifi = WifiMonitor(appContext),
        engineFactory = { NanoHttpServerEngine() },
        handler = mcpServer,
        log = log,
        wakeLocks = wakeLocks,
        clock = AndroidElapsedClock,
    )

    val signInNotifier: SignInNotifier = AndroidSignInNotifier(appContext, notificationPermission)

    val wakeHandler: WakeHandler = buildWakeHandler(
        scope = appScope,
        tunnel = tunnel,
        push = push,
        isSignedIn = { credentialStore.load() != null },
        isRemoteEnabled = remoteSettings::isEnabled,
        startService = { callerStartsTunnel -> TunnelForegroundService.start(appContext, callerStartsTunnel) },
        notifier = signInNotifier,
        log = log,
    )

    init {
        secretsReady = true
        startPeriodicFlush(fileLog, fileLogExecutor)
        detailedLogging.launchAutoOff(appScope)
        if (detailedLogging.expiredAtStart) log.info("Detailed logging turned off: 24 hours passed")
    }

    /**
     * Strings removed verbatim from the file log and shared logs (D48, D61): the account email,
     * the user id when it is long enough to be specific, and the local server's access token.
     */
    fun redactionSecrets(): List<String> {
        if (!secretsReady) return emptyList()
        val user = credentialStore.current.value?.user
        return listOfNotNull(
            user?.email?.takeIf { it.isNotEmpty() },
            user?.id?.takeIf { it.length >= 8 },
            localTokens.token.value,
        )
    }

    /** Shows the server's tier in the account card; a sign-out or new sign-in in between wins (`replace`). */
    private fun applyTier(tier: String) {
        val current = credentialStore.load() ?: return
        if (current.user.tier != tier) credentialStore.replace(current, current.copy(user = current.user.copy(tier = tier)))
    }

    companion object {
        /** `filesDir/logs/`: excluded from backup with the rest of `filesDir` (data_extraction_rules.xml). */
        const val FILE_LOG_DIR = "logs"
    }
}

/**
 * The add-on tools AnkiDroid can serve (D57), plus the phone's own `sync` (D58). Called once per
 * process (one [McpServer]), so get_due_cards and rate_card share one [RatingGuard].
 */
internal fun ankiTools(
    repository: AnkiDroidRepository,
    ratings: RatingGuard = RatingGuard(),
    sync: AnkiDroidSync,
): List<Tool> = listOf(
    ListDecksTool(repository),
    CreateDeckTool(repository),
    FindNotesTool(repository),
    NotesInfoTool(repository),
    AddNoteTool(repository),
    AddNotesTool(repository),
    UpdateNoteFieldsTool(repository),
    UpdateNotesTool(repository),
    DeleteNotesTool(repository),
    ModelNamesTool(repository),
    ModelFieldNamesTool(repository),
    ModelTemplatesTool(repository),
    ModelStylingTool(repository),
    CreateModelTool(repository),
    UpdateModelStylingTool(repository),
    UpdateModelTemplatesTool(repository),
    ModelFieldAddTool(repository),
    TagAddTool(repository),
    TagRemoveTool(repository),
    TagReplaceTool(repository),
    TagBatchTool(repository),
    TagListTool(repository),
    CardBuryTool(repository),
    CardSuspendTool(repository),
    CardSetFlagTool(repository),
    CardChangeDeckTool(repository),
    FilteredDeckGetInfoTool(repository),
    GetDueCardsTool(repository, ratings),
    PresentCardTool(repository),
    RateCardTool(repository, ratings),
    CardsStatsTool(repository),
    GetCardMemoryStateTool(repository),
    GetFsrsParamsTool(repository),
    SyncTool(repository, sync),
)

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
    isRemoteEnabled: () -> Boolean,
    startService: (callerStartsTunnel: Boolean) -> Boolean,
    notifier: SignInNotifier,
    log: TunnelLog,
): WakeHandler = WakeHandler(
    scope = scope,
    tunnelState = tunnel.state,
    isTunnelRunning = { tunnel.isRunning },
    isSignedIn = isSignedIn,
    isRemoteEnabled = isRemoteEnabled,
    isSigningOut = { push.isSigningOut },
    startService = { startService(true) },
    startTunnel = { tunnel.start(wokenByPush = true) },
    stopTunnel = tunnel::stop,
    wakeRunningTunnel = tunnel::onPushWake,
    notifier = notifier,
    log = log,
)
