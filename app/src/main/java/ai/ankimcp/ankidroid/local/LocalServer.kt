package ai.ankimcp.ankidroid.local

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.settings.LocalServerSettings
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import ai.ankimcp.ankidroid.tunnel.JsonRpcHandler
import ai.ankimcp.ankidroid.tunnel.WakeLocks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.BindException

sealed interface LocalServerState {
    data object Off : LocalServerState

    /** Enabled, but the phone is not on Wi-Fi. */
    data object NoWifi : LocalServerState

    /** Enabled, but the Wi-Fi only gave the phone public addresses, so it does not listen. */
    data object PublicAddress : LocalServerState

    data class Running(val host: String, val port: Int) : LocalServerState {
        val url: String get() = ClientConfig.url(host, port)
    }

    data class Failed(val reason: String) : LocalServerState
}

/**
 * The MCP server on the phone's Wi-Fi address (D53-D56). Runs between [start] and [stop],
 * which the foreground service calls, so the server lives exactly as long as the service.
 * While running it follows the Wi-Fi address and the port setting: every change closes the
 * old socket and binds the new address; without Wi-Fi it waits. Requests go through
 * [McpHttpAdapter] to the same [JsonRpcHandler] (MCP server, tools, AnkiDroid repository) as
 * the tunnel, so both can run at once.
 *
 * Engine calls block, so they run one at a time on [ioDispatcher] limited to one thread, in
 * the order they were requested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalServer(
    private val scope: CoroutineScope,
    private val settings: LocalServerSettings,
    private val tokens: LocalServerTokenStore,
    private val wifi: WifiAddressSource,
    private val engineFactory: HttpServerEngine.Factory,
    handler: JsonRpcHandler,
    private val log: TunnelLog,
    wakeLocks: WakeLocks,
    clock: ElapsedClock,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val serial = ioDispatcher.limitedParallelism(1)
    private val lock = Any()
    private var job: Job? = null

    /** Only touched on [serial]. */
    private var engine: HttpServerEngine? = null

    @Volatile
    private var bound: BoundAddress? = null

    private val _state = MutableStateFlow<LocalServerState>(LocalServerState.Off)
    val state: StateFlow<LocalServerState> = _state.asStateFlow()

    private val adapter = McpHttpAdapter(
        handler = handler,
        address = { bound },
        token = { tokens.token.value },
        log = log,
        wakeLocks = wakeLocks,
        clock = clock,
        requireToken = { settings.requireToken.value },
    )

    val isStarted: Boolean get() = synchronized(lock) { job?.isActive == true }

    /** Starts serving if the setting is on; does nothing if already started. */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true || !settings.enabled.value) return
            // A failure from an earlier run (port in use, token) must not outlive an off/on.
            _state.value = LocalServerState.Off
            wifi.start()
            job = scope.launch(serial) {
                // The token is created when "Require access token" is turned on; here it is only
                // read (and recreated if it can never be decrypted). An open server needs none.
                if (settings.requireToken.value && tokens.getOrCreate() == null) {
                    wifi.stop()
                    _state.value = LocalServerState.Failed(
                        tokens.saveError.value ?: "Could not read the access token. Turn the server off and on again.",
                    )
                    return@launch
                }
                combine(wifi.address, settings.port) { address, port -> address to port }
                    .distinctUntilChanged()
                    .collectLatest { (address, port) -> bindWithRetry(address, port) }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val running = job ?: return
            job = null
            running.cancel()
            wifi.stop()
            scope.launch(serial) {
                val wasListening = engine != null
                closeEngine()
                synchronized(lock) { if (job == null) _state.value = LocalServerState.Off }
                if (wasListening) log.info("Local server stopped")
            }
        }
    }

    /** Android refused to start the foreground service, so the server could not start. */
    fun reportStartBlocked(reason: String) {
        if (!isStarted) _state.value = LocalServerState.Failed(reason)
    }

    /** Replaces the token; false (old token kept, reason in the token store) when it could not be stored. */
    fun regenerateToken(): Boolean {
        val ok = tokens.regenerate() != null
        if (ok) log.info("Local server access token regenerated")
        return ok
    }

    /**
     * Binds, and after an address-gone failure re-reads the monitor's current address after
     * [ADDRESS_GONE_RETRY_MS]: if the same address is still (or again) there, the monitor emits
     * no change and nothing else would retry. Runs inside `collectLatest`, so a new monitor or
     * port value, or [stop], cancels the retries.
     */
    private suspend fun bindWithRetry(address: WifiAddress, port: Int) {
        if (!bind(address, port)) return
        for (delayMs in ADDRESS_GONE_RETRY_MS) {
            delay(delayMs)
            val current = wifi.address.value as? WifiAddress.Usable ?: return
            if (!bind(current, port)) return
        }
    }

    /** Returns true when the bind failed because the address is gone (EADDRNOTAVAIL). */
    private suspend fun bind(address: WifiAddress, port: Int): Boolean {
        closeEngine()
        val host = when (address) {
            WifiAddress.None -> {
                _state.value = LocalServerState.NoWifi
                log.info("Local server: no Wi-Fi, waiting")
                return false
            }
            WifiAddress.PublicOnly -> {
                _state.value = LocalServerState.PublicAddress
                log.warn("Local server: the Wi-Fi gave the phone a public address, not listening")
                return false
            }
            is WifiAddress.Usable -> address.host
        }
        val created = engineFactory.create()
        bound = BoundAddress(host, port)
        try {
            created.start(host, port, screen = adapter::screen, handler = adapter::handle)
        } catch (e: Exception) {
            bound = null
            if (isAddressGone(e)) {
                // The Wi-Fi address went away between the callback and the bind: the monitor's
                // next value binds again, and bindWithRetry re-checks in case none comes.
                _state.value = LocalServerState.NoWifi
                log.info("Local server: $host is no longer this phone's address, waiting for Wi-Fi")
                return true
            }
            _state.value = LocalServerState.Failed(
                if (e is BindException) "Port $port is in use. Pick another port." else "Could not listen on $host:$port",
            )
            log.warn("Local server could not listen on $host:$port: ${e.javaClass.simpleName}")
            return false
        }
        engine = created
        if (!currentCoroutineContext().isActive) return false
        _state.value = LocalServerState.Running(host, port)
        log.info("Local server listening on $host:$port")
        return false
    }

    private fun closeEngine() {
        engine?.stop()
        engine = null
        bound = null
    }

    internal companion object {
        /** Re-checks after an address-gone bind failure: 2 s, then 10 s twice, three at most. */
        val ADDRESS_GONE_RETRY_MS = listOf(2_000L, 10_000L, 10_000L)

        /**
         * EADDRNOTAVAIL ("Cannot assign requested address"): the address is not on any
         * interface any more, as opposed to EADDRINUSE ("Address already in use"). Java only
         * tells them apart by the message, which comes from the OS's strerror.
         */
        fun isAddressGone(e: Exception): Boolean =
            e is BindException && e.message.orEmpty().let { it.contains("assign requested address", ignoreCase = true) || it.contains("EADDRNOTAVAIL") }
    }
}
