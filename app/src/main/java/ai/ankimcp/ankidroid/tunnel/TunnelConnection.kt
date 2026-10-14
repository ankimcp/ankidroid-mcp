package ai.ankimcp.ankidroid.tunnel

import ai.ankimcp.ankidroid.auth.CredentialStore
import ai.ankimcp.ankidroid.auth.Credentials
import ai.ankimcp.ankidroid.auth.RefreshOutcome
import ai.ankimcp.ankidroid.auth.TokenRefresher
import ai.ankimcp.ankidroid.log.TunnelLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock

sealed interface TunnelState {
    /** Never started in this process. */
    data object Idle : TunnelState

    data object WaitingForNetwork : TunnelState

    data class Connecting(val attempt: Int) : TunnelState

    data class Connected(val url: String) : TunnelState

    /**
     * Waiting [delayMs] before the next attempt. [persistent] means the normal attempt budget is
     * used up and "keep connected" keeps retrying at the maximum delay.
     */
    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
        val lastError: String,
        val persistent: Boolean = false,
    ) : TunnelState

    /** Stopped for good until the user connects again. */
    data class Stopped(val code: Int, val reason: String, val reloginRequired: Boolean = false) : TunnelState
}

/** Builds a fresh [TunnelSession] for one attempt. */
fun interface TunnelSessionFactory {
    fun create(credentials: Credentials, onEstablished: (url: String) -> Unit): TunnelSession
}

/**
 * Reconnecting tunnel, a port of `tunnel/reconnect.py`'s `TunnelReconnectManager` with a few
 * mobile-specific changes (see docs/PROTOCOL.md "Deviations" and docs/DECISIONS.md):
 *  - waits for network instead of burning attempts while offline;
 *  - a network change interrupts the backoff sleep;
 *  - a close code received during the handshake follows the close-code policy;
 *  - one immediate retry after an auth-close refresh, then normal backoff, so a server that
 *    keeps rejecting fresh tokens cannot cause a tight loop;
 *  - with [keepRetrying] on, it keeps retrying at the maximum delay instead of giving up;
 *  - the CPU is kept awake (wake locks) during short backoffs and connection attempts; longer
 *    backoffs and liveness checks are woken up by [wakeups] (an alarm) so they still happen in
 *    Doze.
 */
class TunnelConnection(
    private val scope: CoroutineScope,
    private val credentialStore: CredentialStore,
    private val refresher: TokenRefresher,
    private val sessionFactory: TunnelSessionFactory,
    private val policy: ReconnectPolicy,
    private val networkAvailable: StateFlow<Boolean>,
    private val log: TunnelLog,
    private val keepRetrying: () -> Boolean = { false },
    private val wakeLocks: WakeLocks = WakeLocks.None,
    private val wakeups: WakeupScheduler = WakeupScheduler.None,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private var lastError: String = ""

    private val lock = Any()

    // All guarded by [lock]. [generation] changes on every start/stop; a run loop only publishes
    // state (and touches the shared alarm) while its own generation is current, so a cancelled
    // loop cannot overwrite what stop() or a newer loop did.
    private var job: Job? = null
    private var generation = 0L
    private var activeSession: TunnelSession? = null
    private var wakeupHold: AutoCloseable? = null

    val isRunning: Boolean get() = synchronized(lock) { job?.isActive == true }

    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            val gen = ++generation
            log.info("Connecting tunnel")
            // Set synchronously so observers never see a stale Stopped from a previous run.
            _state.value = TunnelState.Connecting(0)
            job = scope.launch {
                val stopped = try {
                    runLoop(gen)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TunnelState.Stopped(0, "Tunnel error: ${e.message}")
                }
                finish(gen, stopped)
            }
        }
    }

    /** User-initiated disconnect. */
    fun stop(reason: String = "Disconnected by user") {
        val (stoppedJob, session) = synchronized(lock) {
            val current = job?.takeIf { it.isActive } ?: return
            job = null
            generation++
            val session = activeSession
            activeSession = null
            releaseWakeupHoldLocked()
            wakeups.cancel()
            _state.value = TunnelState.Stopped(CloseCodes.NORMAL, reason)
            current to session
        }
        session?.disconnect()
        stoppedJob.cancel()
        log.info("Tunnel stopped: $reason")
    }

    /**
     * Android refused to start the foreground service (e.g. the app was in the background), so
     * no tunnel was started. Shows [reason] as the stopped state, unless a tunnel is running.
     */
    fun reportStartBlocked(reason: String) {
        synchronized(lock) {
            if (job?.isActive == true) return
            _state.value = TunnelState.Stopped(0, reason)
        }
    }

    /**
     * Called by the network monitor when a validated default network is available. Skips any
     * pending backoff; if [changed] (the default network switched) a live socket is dropped,
     * since it is bound to the old network.
     */
    fun onNetworkAvailable(changed: Boolean) {
        if (_state.value is TunnelState.Connected) {
            // A nudge left pending while connected would make the next retry skip its backoff.
            if (!changed) return
            synchronized(lock) { activeSession }?.abort("Network changed")
        }
        nudges.trySend(Unit)
    }

    /**
     * Called when a [WakeupScheduler] alarm fires (possibly in Doze). While connected, runs the
     * liveness check that coroutine timers may have missed while the CPU slept; otherwise cuts
     * the backoff short. A short wake lock bridges the gap until the loop takes its own.
     */
    fun onWakeup() {
        val session: TunnelSession?
        val gen: Long
        synchronized(lock) {
            if (job?.isActive != true) return
            releaseWakeupHoldLocked()
            wakeupHold = wakeLocks.acquire("wakeup", WAKEUP_HOLD_MS)
            gen = generation
            session = activeSession?.takeIf { _state.value is TunnelState.Connected }
        }
        if (session == null) {
            nudges.trySend(Unit)
            return
        }
        // Unhealthy: the session is being dropped and the loop takes over (and the hold) from here.
        if (!session.checkHealth()) return
        synchronized(lock) {
            // The session may have died while checkHealth ran, and the loop may already have armed
            // its backoff alarm; only re-arm the liveness check for the session that is still live.
            if (gen != generation || activeSession !== session || _state.value !is TunnelState.Connected) return
            releaseWakeupHoldLocked()
            wakeups.schedule(LIVENESS_CHECK_MS)
        }
    }

    private suspend fun runLoop(gen: Long): TunnelState.Stopped {
        var attempt = 0
        var authRetryAvailable = true
        while (true) {
            if (!networkAvailable.value) {
                publish(gen, TunnelState.WaitingForNetwork)
                log.info("Waiting for network")
                releaseWakeupHold()
                networkAvailable.first { it }
            }
            nudges.tryReceive()

            val attemptLock = wakeLocks.acquire("connect", ATTEMPT_WAKE_LOCK_MS)
            releaseWakeupHold()
            var credentials: Credentials
            val closed: CloseResult?
            try {
                credentials = credentialStore.load()
                    ?: return TunnelState.Stopped(0, "Not signed in", reloginRequired = true)

                if (credentials.isExpired(clock)) {
                    log.info("Access token expired, refreshing")
                    when (val outcome = refresher.refresh(credentials)) {
                        is RefreshOutcome.Refreshed -> credentials = outcome.credentials
                        RefreshOutcome.ReloginRequired ->
                            return TunnelState.Stopped(0, "Session expired, please sign in again", reloginRequired = true)
                        RefreshOutcome.SignedOut -> return TunnelState.Stopped(0, "Not signed in", reloginRequired = true)
                        // Non-fatal: try anyway, the relay will tell us if the token is dead.
                        is RefreshOutcome.Failed -> log.warn("Token refresh failed (will retry): ${outcome.message}")
                    }
                }

                publish(gen, TunnelState.Connecting(attempt))
                val session = sessionFactory.create(credentials) { url ->
                    attempt = 0
                    authRetryAvailable = true
                    // A nudge that arrived while connecting must not skip the next disconnect's backoff.
                    nudges.tryReceive()
                    if (publish(gen, TunnelState.Connected(url))) scheduleWakeup(gen, LIVENESS_CHECK_MS)
                    attemptLock.close()
                }
                if (!register(gen, session)) throw CancellationException("Tunnel stopped")

                closed = try {
                    session.run()
                } catch (e: TunnelConnectionException) {
                    val message = e.message ?: "Connection failed"
                    log.warn("Tunnel connection failed: $message")
                    lastError = message
                    val code = e.closeCode
                    if (code == null) {
                        attempt++
                        null
                    } else {
                        CloseResult(code, message)
                    }
                } finally {
                    unregister(session)
                    cancelWakeup(gen)
                }
            } finally {
                attemptLock.close()
            }

            if (closed != null) {
                lastError = "${closed.code} ${closed.reason}"
                log.info("Tunnel closed: ${closed.code} ${closed.reason}")
                when (policy.decide(closed.code)) {
                    CloseDecision.STOP -> {
                        val revoked = closed.code == CloseCodes.TOKEN_REVOKED || closed.code == CloseCodes.ACCOUNT_DELETED
                        if (revoked) credentialStore.clear()
                        return TunnelState.Stopped(closed.code, describeStop(closed), reloginRequired = revoked)
                    }
                    CloseDecision.REFRESH_TOKEN_THEN_RECONNECT -> {
                        log.info("Auth-related close ${closed.code}, refreshing token")
                        val outcome = wakeLocks.acquire("refresh", ATTEMPT_WAKE_LOCK_MS).use {
                            refresher.refresh(credentials)
                        }
                        when (outcome) {
                            is RefreshOutcome.Refreshed -> {
                                if (authRetryAvailable) {
                                    authRetryAvailable = false
                                    continue
                                }
                                attempt++
                            }
                            RefreshOutcome.ReloginRequired -> return TunnelState.Stopped(
                                closed.code,
                                "Session expired, please sign in again",
                                reloginRequired = true,
                            )
                            RefreshOutcome.SignedOut ->
                                return TunnelState.Stopped(closed.code, "Not signed in", reloginRequired = true)
                            is RefreshOutcome.Failed -> {
                                log.warn("Token refresh failed: ${outcome.message}")
                                attempt++
                            }
                        }
                    }
                    CloseDecision.RECONNECT -> attempt++
                }
            }

            val persistent = policy.isExhausted(attempt)
            if (persistent && !keepRetrying()) {
                return TunnelState.Stopped(0, "Max reconnection attempts (${policy.maxAttempts}) exhausted")
            }
            val delayMs = if (persistent) policy.maxDelayMs else policy.delayFor(attempt)
            if (persistent) {
                log.info("Keep connected is on: retrying in ${delayMs / 1000}s (attempt $attempt)")
            } else {
                log.info("Reconnecting in ${delayMs / 1000.0}s (attempt $attempt/${policy.maxAttempts})")
            }
            publish(gen, TunnelState.Reconnecting(attempt, delayMs, lastError, persistent))
            waitBeforeRetry(gen, delayMs)
        }
    }

    /**
     * Short waits keep the CPU awake so the coroutine timer fires on time. Longer ones let the
     * device sleep and rely on an alarm (via [onWakeup]) or a network nudge to resume.
     */
    private suspend fun waitBeforeRetry(gen: Long, delayMs: Long) {
        if (delayMs <= ALARM_THRESHOLD_MS) {
            wakeLocks.acquire("backoff", delayMs + WAKE_LOCK_SLACK_MS).use {
                withTimeoutOrNull(delayMs) { nudges.receive() }
            }
        } else {
            scheduleWakeup(gen, delayMs)
            try {
                withTimeoutOrNull(delayMs) { nudges.receive() }
            } finally {
                cancelWakeup(gen)
            }
        }
    }

    private fun publish(gen: Long, state: TunnelState): Boolean = synchronized(lock) {
        if (gen != generation) return false
        _state.value = state
        true
    }

    private fun finish(gen: Long, stopped: TunnelState.Stopped) {
        synchronized(lock) {
            if (gen != generation) return
            releaseWakeupHoldLocked()
            wakeups.cancel()
            _state.value = stopped
        }
        log.info("Tunnel stopped: ${stopped.reason}")
    }

    private fun register(gen: Long, session: TunnelSession): Boolean = synchronized(lock) {
        if (gen != generation) return false
        activeSession = session
        true
    }

    private fun unregister(session: TunnelSession) {
        synchronized(lock) {
            if (activeSession === session) activeSession = null
        }
    }

    /** The alarm is shared by all generations; only the current loop may arm or disarm it. */
    private fun scheduleWakeup(gen: Long, delayMs: Long) {
        synchronized(lock) { if (gen == generation) wakeups.schedule(delayMs) }
    }

    private fun cancelWakeup(gen: Long) {
        synchronized(lock) { if (gen == generation) wakeups.cancel() }
    }

    private fun releaseWakeupHold() {
        synchronized(lock) { releaseWakeupHoldLocked() }
    }

    private fun releaseWakeupHoldLocked() {
        wakeupHold?.close()
        wakeupHold = null
    }

    private fun describeStop(closed: CloseResult): String = when (closed.code) {
        CloseCodes.NORMAL -> "Disconnected"
        CloseCodes.TOKEN_REVOKED -> "Access was revoked from the dashboard, please sign in again"
        CloseCodes.ACCOUNT_DELETED -> "Account deleted"
        CloseCodes.SESSION_REPLACED -> "Another device connected with this account"
        CloseCodes.IDLE_TIMEOUT -> "Disconnected after a long idle period"
        else -> closed.reason
    }

    companion object {
        /** Backoffs up to this long hold a wake lock; longer ones use an alarm. */
        const val ALARM_THRESHOLD_MS = 5_000L

        /** Token refresh (15 s HTTP timeout) + open (10 s) + handshake (10 s), with slack. */
        const val ATTEMPT_WAKE_LOCK_MS = 45_000L

        /** Covers the hop from an alarm to the run loop taking its own wake lock. */
        const val WAKEUP_HOLD_MS = 10_000L

        /** While connected, an alarm re-runs the liveness check this often. */
        const val LIVENESS_CHECK_MS = ProtocolConstants.HEALTH_CHECK_TIMEOUT_MS

        private const val WAKE_LOCK_SLACK_MS = 2_000L
    }
}
