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
import ai.ankimcp.ankidroid.config.AppSettings
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.mcp.McpServer
import ai.ankimcp.ankidroid.mcp.ToolRegistry
import ai.ankimcp.ankidroid.service.AlarmWakeupScheduler
import ai.ankimcp.ankidroid.service.AndroidElapsedClock
import ai.ankimcp.ankidroid.service.AndroidWakeLocks
import ai.ankimcp.ankidroid.service.NetworkMonitor
import ai.ankimcp.ankidroid.tools.ListDecksTool
import ai.ankimcp.ankidroid.tunnel.ProtocolConstants
import ai.ankimcp.ankidroid.tunnel.ReconnectPolicy
import ai.ankimcp.ankidroid.tunnel.TunnelClient
import ai.ankimcp.ankidroid.tunnel.TunnelConnection
import ai.ankimcp.ankidroid.tunnel.TunnelSessionFactory
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-wired process-wide dependencies. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val config: AppConfig = AppConfig.fromBuildConfig()
    val settings = AppSettings(appContext)
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

    private val wakeLocks = AndroidWakeLocks(appContext)

    val tunnel = TunnelConnection(
        scope = appScope,
        credentialStore = credentialStore,
        refresher = tokenRefresher,
        sessionFactory = TunnelSessionFactory { credentials, onEstablished ->
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
            )
        },
        policy = ReconnectPolicy(),
        networkAvailable = networkMonitor.available,
        log = log,
        keepRetrying = { settings.keepConnected.value },
        wakeLocks = wakeLocks,
        wakeups = AlarmWakeupScheduler(appContext),
    )
}
