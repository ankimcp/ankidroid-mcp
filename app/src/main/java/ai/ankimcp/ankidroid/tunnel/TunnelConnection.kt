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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
     * used up and "Always connected" keeps retrying at the maximum delay.
     */
    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
        val lastError: String,
        val persistent: Boolean = false,
    ) : TunnelState

    /**
     * Stopped for good until the user connects again (or a push wake-up arrives).
     * [idleTimeout] means the on-demand idle window passed without a request. [signedOut] (with
     * [reloginRequired]) means there were no credentials at all, e.g. after a deliberate sign-out,
     * as opposed to a session that expired or was revoked.
     */
    data class Stopped(
        val code: Int,
        val reason: String,
        val reloginRequired: Boolean = false,
        val idleTimeout: Boolean = false,
        val signedOut: Boolean = false,
    ) : TunnelState
}

/** Builds a fresh [TunnelSession] for one attempt. */
fun interface TunnelSessionFactory {
    /** [onRequest] must be called for every `request` frame the session receives. */
    fun create(credentials: Credentials, onEstablished: (url: String) -> Unit, onRequest: () -> Unit): TunnelSession
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
 *    Doze;
 *  - with an [idleTimeoutMs] window set, the run stops once that long has passed (on
 *    [elapsedClock]) since [start] or the last `request` frame. A coroutine timer catches the
 *    deadline while the CPU is awake and an [idleWakeups] alarm catches it in Doze;
 *  - when the stored credentials are cleared by someone else (sign-out), the run stops as
 *    "Not signed in".
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
    /** Idle window in ms, read whenever the deadline is evaluated; null disables the idle timer. */
    private val idleTimeoutMs: () -> Long? = { null },
    private val idleWakeups: WakeupScheduler = WakeupScheduler.None,
    private val elapsedClock: ElapsedClock = ElapsedClock.Monotonic,
) {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _idleDeadline = MutableStateFlow<Long?>(null)

    /** When the run stops for idleness, on [elapsedClock]; null while no idle timer runs. */
    val idleDeadlineMs: StateFlow<Long?> = _idleDeadline.asStateFlow()

    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private var lastError: String = ""

    private val lock = Any()

    // All guarded by [lock]. [generation] changes on every start/stop; a run loop only publishes
    // state (and touches the shared alarm) while its own generation is current, so a cancelled
    // loop cannot overwrite what stop() or a newer loop did. The idle timer follows the same rule.
    private var job: Job? = null
    private var generation = 0L
    private var activeSession: TunnelSession? = null
    private var wakeupHold: AutoCloseable? = null
    private var lastActivityMs = 0L

    /** False when [lastActivityMs] was last set by a settings change rather than a request frame. */
    private var lastActivityWasRequest = false
    private var idleTimerJob: Job? = null

    /**
     * The run that may be clearing the credentials itself (refresh token rejected, access
     * revoked). Its own stop reason wins over the "Not signed in" stop of the credentials observer.
     */
    private var selfClearingGeneration = NO_GENERATION

    init {
        // Only a change to "no credentials" counts; the initial value may be the not-yet-loaded null.
        scope.launch {
            credentialStore.current
                .map { it != null }
                .distinctUntilChanged()
                .drop(1)
                .filter { signedIn -> !signedIn }
                .collect { onCredentialsCleared() }
        }
    }

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
            lastActivityMs = elapsedClock.elapsedMs()
            lastActivityWasRequest = false
            armIdleLocked(gen)
        }
    }

    /** User-initiated disconnect. */
    fun stop(reason: String = "Disconnected by user") {
        stopRun(expectedGeneration = null, stopped = TunnelState.Stopped(CloseCodes.NORMAL, reason))
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

    /** Called when the [idleWakeups] alarm fires: stops the run if its idle deadline has passed. */
    fun onIdleAlarm() {
        checkIdle(synchronized(lock) { generation })
    }

    /**
     * The idle window setting changed. A running timer restarts its countdown from now with the
     * new window (or stops if the idle timer is now disabled).
     */
    fun onIdleSettingsChanged() {
        synchronized(lock) {
            if (job?.isActive != true) return
            lastActivityMs = elapsedClock.elapsedMs()
            lastActivityWasRequest = false
            armIdleLocked(generation)
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
                credentials = credentialStore.load() ?: return notSignedIn(0)

                if (credentials.isExpired(clock)) {
                    log.info("Access token expired, refreshing")
                    when (val outcome = refreshOwned(gen) { refresher.refresh(credentials) }) {
                        is RefreshOutcome.Refreshed -> credentials = outcome.credentials
                        RefreshOutcome.ReloginRequired ->
                            return TunnelState.Stopped(0, "Session expired, please sign in again", reloginRequired = true)
                        RefreshOutcome.SignedOut -> return notSignedIn(0)
                        // Non-fatal: try anyway, the relay will tell us if the token is dead.
                        is RefreshOutcome.Failed -> log.warn("Token refresh failed (will retry): ${outcome.message}")
                    }
                    if (credentialStore.load() == null) return notSignedIn(0)
                }

                publish(gen, TunnelState.Connecting(attempt))
                val session = sessionFactory.create(
                    credentials,
                    { url ->
                        attempt = 0
                        authRetryAvailable = true
                        // A nudge that arrived while connecting must not skip the next disconnect's backoff.
                        nudges.tryReceive()
                        if (publish(gen, TunnelState.Connected(url))) scheduleWakeup(gen, LIVENESS_CHECK_MS)
                        attemptLock.close()
                    },
                    { onRequestFrame(gen) },
                )
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
                        if (revoked) {
                            markSelfClearing(gen)
                            credentialStore.clear()
                        }
                        return TunnelState.Stopped(closed.code, describeStop(closed), reloginRequired = revoked)
                    }
                    CloseDecision.REFRESH_TOKEN_THEN_RECONNECT -> {
                        log.info("Auth-related close ${closed.code}, refreshing token")
                        val outcome = wakeLocks.acquire("refresh", ATTEMPT_WAKE_LOCK_MS).use {
                            refreshOwned(gen) { refresher.refresh(credentials) }
                        }
                        when (outcome) {
                            is RefreshOutcome.Refreshed -> {
                                if (credentialStore.load() == null) return notSignedIn(closed.code)
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
                            RefreshOutcome.SignedOut -> return notSignedIn(closed.code)
                            is RefreshOutcome.Failed -> {
                                if (credentialStore.load() == null) return notSignedIn(closed.code)
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
                log.info("Always connected: retrying in ${delayMs / 1000}s (attempt $attempt)")
            } else {
                log.info("Reconnecting in ${delayMs / 1000.0}s (attempt $attempt/${policy.maxAttempts})")
            }
            publish(gen, TunnelState.Reconnecting(attempt, delayMs, lastError, persistent))
            waitBeforeRetry(gen, delayMs)
        }
    }

    private fun notSignedIn(code: Int) = TunnelState.Stopped(code, "Not signed in", reloginRequired = true, signedOut = true)

    /**
     * Runs a token refresh, which clears the credentials itself when the refresh token is
     * rejected. While it runs (and afterwards, if the run ends because of it) the credentials
     * observer leaves the stop to this run. A refresh that lets the run continue hands the
     * observer back; the caller then re-checks the credentials, since a sign-out the observer
     * skipped meanwhile would otherwise go unnoticed.
     */
    private suspend inline fun refreshOwned(gen: Long, refresh: () -> RefreshOutcome): RefreshOutcome {
        markSelfClearing(gen)
        val outcome = refresh()
        if (outcome is RefreshOutcome.Refreshed || outcome is RefreshOutcome.Failed) {
            synchronized(lock) { if (selfClearingGeneration == gen) selfClearingGeneration = NO_GENERATION }
        }
        return outcome
    }

    private fun markSelfClearing(gen: Long) {
        synchronized(lock) { if (gen == generation) selfClearingGeneration = gen }
    }

    /** The credentials were cleared (sign-out): a running tunnel cannot continue without them. */
    private fun onCredentialsCleared() {
        stopRun(expectedGeneration = null, stopped = notSignedIn(CloseCodes.NORMAL), credentialsCleared = true)
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

    /**
     * Stops the run of [expectedGeneration] (any run if null). Returns false if nothing was
     * stopped. With [idleSinceMs] (an idle stop), it also returns false, and re-arms the idle
     * timer, if a request (or a settings change) moved the activity time after the idle decision
     * was made. With [credentialsCleared], a run that cleared them itself is left to finish with
     * its own reason.
     */
    private fun stopRun(
        expectedGeneration: Long?,
        stopped: TunnelState.Stopped,
        idleSinceMs: Long? = null,
        credentialsCleared: Boolean = false,
    ): Boolean {
        val (stoppedJob, session) = synchronized(lock) {
            val current = job?.takeIf { it.isActive } ?: return false
            if (expectedGeneration != null && expectedGeneration != generation) return false
            if (credentialsCleared && selfClearingGeneration == generation) return false
            if (idleSinceMs != null && lastActivityMs != idleSinceMs) {
                log.info(if (lastActivityWasRequest) "A request arrived, staying connected" else "Idle settings changed, staying connected")
                armIdleLocked(generation)
                return false
            }
            job = null
            generation++
            val session = activeSession
            activeSession = null
            releaseWakeupHoldLocked()
            wakeups.cancel()
            disarmIdleLocked()
            _state.value = stopped
            current to session
        }
        session?.disconnect()
        stoppedJob.cancel()
        log.info("Tunnel stopped: ${stopped.reason}")
        return true
    }

    private fun onRequestFrame(gen: Long) {
        synchronized(lock) {
            if (gen != generation) return
            lastActivityMs = elapsedClock.elapsedMs()
            lastActivityWasRequest = true
            // The timers stay armed for the old deadline; when they fire they see the new one and re-arm.
            if (_idleDeadline.value != null) _idleDeadline.value = idleTimeoutMs()?.let { lastActivityMs + it }
        }
    }

    private fun checkIdle(gen: Long) {
        val window: Long
        val idleSince: Long
        synchronized(lock) {
            if (gen != generation || job?.isActive != true) return
            window = idleTimeoutMs() ?: run {
                disarmIdleLocked()
                return
            }
            idleSince = lastActivityMs
            if (elapsedClock.elapsedMs() < idleSince + window) {
                armIdleLocked(gen)
                return
            }
        }
        val minutes = window / 60_000
        log.info("Idle timeout: no requests for $minutes min, disconnecting")
        stopRun(
            expectedGeneration = gen,
            stopped = TunnelState.Stopped(CloseCodes.NORMAL, "No requests for $minutes min", idleTimeout = true),
            idleSinceMs = idleSince,
        )
    }

    private fun armIdleLocked(gen: Long) {
        idleTimerJob?.cancel()
        idleTimerJob = null
        val window = idleTimeoutMs()
        if (window == null) {
            disarmIdleLocked()
            return
        }
        val deadline = lastActivityMs + window
        _idleDeadline.value = deadline
        val remaining = (deadline - elapsedClock.elapsedMs()).coerceAtLeast(0)
        idleWakeups.schedule(remaining)
        idleTimerJob = scope.launch {
            delay(remaining)
            checkIdle(gen)
        }
    }

    private fun disarmIdleLocked() {
        idleTimerJob?.cancel()
        idleTimerJob = null
        if (_idleDeadline.value != null) idleWakeups.cancel()
        _idleDeadline.value = null
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
            disarmIdleLocked()
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
        private const val NO_GENERATION = -1L
    }
}
