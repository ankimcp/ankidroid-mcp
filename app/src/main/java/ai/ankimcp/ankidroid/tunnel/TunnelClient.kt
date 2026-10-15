package ai.ankimcp.ankidroid.tunnel

import ai.ankimcp.ankidroid.log.TunnelLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** How a connection ended. */
data class CloseResult(val code: Int, val reason: String)

/** Connect or handshake failed. [closeCode] is set when the relay closed the socket with a code. */
class TunnelConnectionException(message: String, val closeCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

/** One tunnel connection attempt; [TunnelConnection] creates a fresh one per attempt. */
interface TunnelSession {
    /**
     * Connects, waits for `tunnel_established`, then serves requests until the socket closes.
     * @throws TunnelConnectionException if connecting or the handshake fails.
     */
    suspend fun run(): CloseResult

    /** Graceful client-initiated close (code 1000). */
    fun disconnect()

    /** Drop the socket without a close handshake, e.g. after the default network changed. */
    fun abort(reason: String)

    /**
     * Run the liveness check now (e.g. after an alarm woke the device, when coroutine timers
     * may not have run for a while).
     * @return false if server pings stopped and the socket is being dropped.
     */
    fun checkHealth(): Boolean = true

    /** Returns once no relay request is being handled (at once if none is). */
    suspend fun awaitRequestsIdle() = Unit

    /** True while a relay request is being handled. */
    fun hasRequestsInFlight(): Boolean = false
}

/** Handles one JSON-RPC message body; returns the response body, or null for notifications. */
fun interface JsonRpcHandler {
    /** [headers] are the HTTP request headers the relay forwarded (lower-case names). */
    suspend fun handle(body: String, headers: Map<String, String>): String?
}

/**
 * Single WebSocket connection to the relay over OkHttp, a port of `tunnel/client.py`.
 *
 * The socket must open within [openTimeoutMs] and `tunnel_established` must follow within
 * [handshakeTimeoutMs] of the open, like the add-on's `open_timeout` + handshake wait.
 * Requests are served concurrently, each under a wake lock; pings are answered inline. If no
 * server ping arrives for [healthTimeoutMs] (measured on [clock], which keeps counting while
 * the CPU sleeps) the socket is dropped and [run] returns 1001. [onRequest] is called for every
 * `request` frame before it is handled (drives the idle timer).
 *
 * A check that comes more than one heartbeat interval plus [FROZEN_SLACK_MS] after the previous
 * one on [uptimeClock] (which stops while the CPU sleeps; heartbeat ticks are scheduled on the
 * same kind of clock) means the process was frozen while the CPU ran, and the
 * pings that arrived meanwhile may still be queued behind the check. Instead of dropping the
 * socket, such a check gives the server one more heartbeat interval. This grace is granted once
 * per server ping, so a socket that really went silent is still dropped.
 */
class TunnelClient(
    private val http: OkHttpClient,
    private val url: String,
    private val accessToken: String,
    private val clientVersion: String,
    private val handler: JsonRpcHandler,
    private val log: TunnelLog,
    private val clock: ElapsedClock,
    private val wakeLocks: WakeLocks = WakeLocks.None,
    private val onEstablished: (url: String) -> Unit = {},
    private val onRequest: () -> Unit = {},
    private val onServerError: (code: String, message: String) -> Unit = { _, _ -> },
    private val openTimeoutMs: Long = ProtocolConstants.OPEN_TIMEOUT_MS,
    private val handshakeTimeoutMs: Long = ProtocolConstants.CONNECTION_TIMEOUT_MS,
    private val heartbeatIntervalMs: Long = ProtocolConstants.HEARTBEAT_INTERVAL_MS,
    private val healthTimeoutMs: Long = ProtocolConstants.HEALTH_CHECK_TIMEOUT_MS,
    private val requestTimeoutMs: Long = ProtocolConstants.REQUEST_TIMEOUT_MS,
    private val uptimeClock: ElapsedClock = ElapsedClock.Monotonic,
) : TunnelSession {

    private sealed interface Event {
        data object Opened : Event
        data class Text(val text: String) : Event
        data class Closing(val code: Int, val reason: String) : Event
        data class Closed(val code: Int, val reason: String) : Event
        data class Failure(val error: Throwable, val httpCode: Int?) : Event
        data class Aborted(val reason: String) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val requestsInFlight = MutableStateFlow(0)

    // Watchdog state, guarded by [watchdogLock]: the heartbeat loop and alarm-driven checkHealth
    // calls run on different threads.
    private val watchdogLock = Any()
    private var lastServerPing = 0L
    private var lastCheckUptime = 0L
    private var graceUntil = Long.MIN_VALUE
    private var graceUsed = false

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var established = false

    override suspend fun run(): CloseResult = coroutineScope {
        val ws = http.newWebSocket(buildRequest(), Listener())
        socket = ws
        try {
            awaitOpen(ws)
            synchronized(watchdogLock) {
                lastServerPing = clock.elapsedMs()
                lastCheckUptime = uptimeClock.elapsedMs()
            }
            val tunnelUrl = awaitHandshake()
            established = true
            log.info("Tunnel established: $tunnelUrl")
            onEstablished(tunnelUrl)

            val requestScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
            val health = launch { healthLoop() }
            val result = messageLoop(ws, requestScope)
            health.cancel()
            requestScope.coroutineContext.job.cancel()
            result
        } finally {
            established = false
            socket = null
            // No-op if already closing (server-initiated) or cancelled (failure/abort paths);
            // otherwise starts a graceful close that OkHttp force-cancels after 60 s.
            ws.close(CloseCodes.NORMAL, "Client disconnect")
        }
    }

    override fun disconnect() {
        socket?.close(CloseCodes.NORMAL, "Client disconnect")
    }

    override fun abort(reason: String) {
        events.trySend(Event.Aborted(reason))
    }

    override fun checkHealth(): Boolean = !isUnhealthy()

    override suspend fun awaitRequestsIdle() {
        requestsInFlight.first { it == 0 }
    }

    override fun hasRequestsInFlight(): Boolean = requestsInFlight.value > 0

    /**
     * Queues an abort and returns true if no server ping arrived within [healthTimeoutMs], unless
     * this check found the process had been frozen (see the class doc).
     */
    private fun isUnhealthy(): Boolean {
        if (!established) return false
        val reason = synchronized(watchdogLock) {
            val now = clock.elapsedMs()
            val uptime = uptimeClock.elapsedMs()
            val pausedMs = uptime - lastCheckUptime
            lastCheckUptime = uptime
            val silentMs = now - lastServerPing
            when {
                silentMs <= healthTimeoutMs || now <= graceUntil -> null
                pausedMs > heartbeatIntervalMs + FROZEN_SLACK_MS && !graceUsed -> {
                    graceUsed = true
                    graceUntil = now + heartbeatIntervalMs
                    log.info(
                        "App was frozen for ${pausedMs / 1000}s, waiting ${heartbeatIntervalMs / 1000}s for a server ping",
                    )
                    null
                }
                else -> "No server ping in ${silentMs / 1000}s"
            }
        } ?: return false
        log.warn("$reason, closing connection")
        events.trySend(Event.Aborted(reason))
        return true
    }

    private fun onServerPing() {
        synchronized(watchdogLock) {
            lastServerPing = clock.elapsedMs()
            graceUsed = false
            graceUntil = Long.MIN_VALUE
        }
    }

    private fun buildRequest(): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header(ProtocolConstants.CLIENT_TYPE_HEADER, ProtocolConstants.CLIENT_TYPE)
        ProtocolConstants.normalizeClientVersion(clientVersion)?.let {
            builder.header(ProtocolConstants.CLIENT_VERSION_HEADER, it)
        }
        return builder.build()
    }

    private suspend fun awaitOpen(ws: WebSocket) {
        val opened = withTimeoutOrNull(openTimeoutMs) {
            when (val event = events.receive()) {
                Event.Opened -> true
                else -> throw handshakeFailure(event)
            }
        }
        if (opened == null) {
            ws.cancel()
            throw TunnelConnectionException("Timed out opening the connection to $url (>${openTimeoutMs / 1000.0}s)")
        }
    }

    private suspend fun awaitHandshake(): String {
        val outcome = withTimeoutOrNull(handshakeTimeoutMs) {
            when (val event = events.receive()) {
                is Event.Text -> {
                    val message = try {
                        Protocol.parseServerMessage(event.text)
                    } catch (e: ProtocolException) {
                        throw TunnelConnectionException("Invalid handshake message: ${e.message}", cause = e)
                    }
                    (message as? ServerMessage.TunnelEstablished)?.url
                        ?: throw TunnelConnectionException(
                            "Expected tunnel_established, got '${message.typeName()}'",
                        )
                }
                else -> throw handshakeFailure(event)
            }
        }
        return outcome ?: throw TunnelConnectionException(
            "Timed out waiting for tunnel_established (>${handshakeTimeoutMs / 1000.0}s)",
        )
    }

    private fun handshakeFailure(event: Event): TunnelConnectionException = when (event) {
        is Event.Closing -> TunnelConnectionException(
            "Relay closed the connection during handshake: ${event.code} ${event.reason}".trim(),
            closeCode = event.code,
        )
        is Event.Closed -> TunnelConnectionException(
            "Relay closed the connection during handshake: ${event.code} ${event.reason}".trim(),
            closeCode = event.code,
        )
        is Event.Failure -> TunnelConnectionException(
            buildString {
                append("Failed to connect to $url")
                event.httpCode?.let { append(" (HTTP $it)") }
                append(": ${event.error.message ?: event.error.javaClass.simpleName}")
            },
            cause = event.error,
        )
        is Event.Aborted -> TunnelConnectionException("Connection aborted: ${event.reason}")
        Event.Opened, is Event.Text -> TunnelConnectionException("Unexpected event during handshake: $event")
    }

    private suspend fun messageLoop(ws: WebSocket, requestScope: CoroutineScope): CloseResult {
        for (event in events) {
            when (event) {
                is Event.Text -> {
                    val message = try {
                        Protocol.parseServerMessage(event.text)
                    } catch (e: ProtocolException) {
                        log.warn("Skipping malformed message: ${e.message}")
                        continue
                    }
                    when (message) {
                        is ServerMessage.Request -> {
                            onRequest()
                            // Taken before the coroutine is dispatched so the CPU cannot suspend in between.
                            val wakeLock = wakeLocks.acquire("request", requestTimeoutMs + WAKE_LOCK_SLACK_MS)
                            requestsInFlight.update { it + 1 }
                            // Released on completion, which also covers a request cancelled before it ran.
                            requestScope.launch { handleRequest(ws, message) }
                                .invokeOnCompletion {
                                    wakeLock.close()
                                    requestsInFlight.update { it - 1 }
                                }
                        }
                        is ServerMessage.Ping -> {
                            onServerPing()
                            ws.send(Protocol.encode(ClientMessage.Pong(message.timestamp)))
                        }
                        is ServerMessage.Error -> {
                            log.warn("Relay error ${message.code}: ${message.message}")
                            onServerError(message.code, message.message)
                        }
                        is ServerMessage.TunnelEstablished -> log.warn("Ignoring repeated tunnel_established")
                    }
                }
                Event.Opened -> Unit
                is Event.Closing -> {
                    ws.close(CloseCodes.NORMAL, null)
                    return CloseResult(event.code, event.reason.ifEmpty { "Connection closed" })
                }
                is Event.Closed -> return CloseResult(event.code, event.reason.ifEmpty { "Connection closed" })
                is Event.Failure -> {
                    ws.cancel()
                    return CloseResult(
                        CloseCodes.GOING_AWAY,
                        "Connection lost: ${event.error.message ?: event.error.javaClass.simpleName}",
                    )
                }
                is Event.Aborted -> {
                    ws.cancel()
                    return CloseResult(CloseCodes.GOING_AWAY, event.reason)
                }
            }
        }
        return CloseResult(CloseCodes.GOING_AWAY, "Event stream ended")
    }

    private suspend fun healthLoop() {
        while (true) {
            delay(heartbeatIntervalMs)
            if (isUnhealthy()) return
        }
    }

    private suspend fun handleRequest(ws: WebSocket, request: ServerMessage.Request) {
        val start = clock.elapsedMs()
        val body = request.body
        val response = try {
            val responseBody = if (!body.isNullOrEmpty()) {
                withTimeout(requestTimeoutMs) { handler.handle(body, request.headers) }
            } else {
                null
            }
            ClientMessage.Response(
                requestId = request.requestId,
                statusCode = 200,
                headers = JSON_HEADERS,
                body = responseBody?.takeIf { it.isNotEmpty() },
            )
        } catch (e: TimeoutCancellationException) {
            ClientMessage.Response(
                requestId = request.requestId,
                statusCode = 504,
                headers = JSON_HEADERS,
                body = errorEnvelope(extractRequestId(body), -32004, "Request timed out").toString(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to process ${request.method} ${request.path}: ${e.message}")
            ClientMessage.Response(
                requestId = request.requestId,
                statusCode = 500,
                headers = JSON_HEADERS,
                body = errorEnvelope(JsonNull, -32603, "Internal error: ${e.message}").toString(),
            )
        }
        if (!ws.send(Protocol.encode(response))) {
            log.warn("Could not send response for ${request.requestId}: socket closed")
        }
        log.debug("${request.method} ${request.path} -> ${response.statusCode} (${clock.elapsedMs() - start} ms)")
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            events.trySend(Event.Opened)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            events.trySend(Event.Text(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            events.trySend(Event.Closing(code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            events.trySend(Event.Closed(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            events.trySend(Event.Failure(t, response?.code))
        }
    }

    companion object {
        private val JSON_HEADERS = mapOf("content-type" to "application/json")

        /** Extra wake-lock time on top of the request budget for sending the response. */
        private const val WAKE_LOCK_SLACK_MS = 5_000L

        /** How late a watchdog check may run on uptime before it counts as a freeze. */
        const val FROZEN_SLACK_MS = 10_000L

        internal fun errorEnvelope(id: kotlinx.serialization.json.JsonElement, code: Int, message: String): JsonObject =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                    },
                )
            }

        /** Best-effort `id` of the inner JSON-RPC request, for correlating a 504. */
        internal fun extractRequestId(body: String?): kotlinx.serialization.json.JsonElement {
            if (body.isNullOrEmpty()) return JsonNull
            return try {
                (Protocol.json.parseToJsonElement(body) as? JsonObject)?.get("id") ?: JsonNull
            } catch (_: Exception) {
                JsonNull
            }
        }
    }
}

private fun ServerMessage.typeName(): String = when (this) {
    is ServerMessage.TunnelEstablished -> "tunnel_established"
    is ServerMessage.Request -> "request"
    is ServerMessage.Ping -> "ping"
    is ServerMessage.Error -> "error"
}
