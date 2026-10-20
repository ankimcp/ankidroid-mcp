package ai.ankimcp.ankidroid.push

import ai.ankimcp.ankidroid.log.TunnelLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock

/** Background work for registration (WorkManager in the app). */
interface PushWorkScheduler {
    /** Re-runs a failed registration later. */
    fun schedule()

    /** Cancels a pending retry. */
    fun cancel()

    /** Enqueues the daily registration check unless it is already enqueued. */
    fun ensureDailyCheck()

    fun cancelDailyCheck()

    /** Runs [PushCoordinator.retryPendingDelete] once a network is available, retrying until it succeeds. */
    fun scheduleTokenDelete()

    companion object {
        val None = object : PushWorkScheduler {
            override fun schedule() = Unit

            override fun cancel() = Unit

            override fun ensureDailyCheck() = Unit

            override fun cancelDailyCheck() = Unit

            override fun scheduleTokenDelete() = Unit
        }
    }
}

/**
 * Decides when the FCM token is (re-)registered with the server and when it is removed.
 *
 * Registers when signed in and any of: the last attempt did not succeed, the token changed, the
 * app version changed, or the last success is [REREGISTER_AFTER_MS] (7 days) old. Triggers:
 * sign-in, `onNewToken`, app start, a daily WorkManager check, and the WorkManager retry.
 * Transient failures (network, 429, 5xx) schedule a retry; 400/401 wait for the next trigger.
 * With Remote access off (D63) no trigger registers; [onRemoteDisabled] removes the token and
 * [onRemoteEnabled] registers it again.
 */
class PushCoordinator(
    private val messaging: PushMessaging,
    private val registrar: PushRegistrar,
    private val store: PushRegistrationStore,
    private val isSignedIn: () -> Boolean,
    /** The Remote access switch (D63); registrations stop while it is off. */
    private val isRemoteEnabled: () -> Boolean = { true },
    private val appVersion: String,
    private val work: PushWorkScheduler,
    private val log: TunnelLog,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    /** The FCM token as last seen (fetched or from `onNewToken`); null until then. */
    @Volatile
    private var currentToken: String? = null

    private val _signingOut = MutableStateFlow(false)
    private val _wakeUpsReady = MutableStateFlow(false)

    val state: StateFlow<PushRegistrationState> = store.state

    val isAvailable: Boolean get() = messaging.isAvailable

    /** True from the start of [logout] (or [beginLogout]) until it has finished. */
    val isSigningOut: Boolean get() = _signingOut.value

    /**
     * True when a wake-up can actually reach this device: Firebase is configured, the last
     * registration attempt succeeded, and the token it registered is the current FCM token.
     * Only then does on-demand mode disconnect when idle.
     */
    val wakeUpsReadyState: StateFlow<Boolean> = _wakeUpsReady.asStateFlow()

    val wakeUpsReady: Boolean get() = _wakeUpsReady.value

    suspend fun onAppStart() {
        // Off: the token may still be registered if the process died mid-switch; nothing syncs.
        if (!isRemoteEnabled()) {
            onRemoteDisabled()
            return
        }
        // Signed in, `sync` finishes it first, before it fetches a token.
        if (!isSignedIn()) retryPendingDelete()
        sync(knownToken = null, Trigger.AppStart)
    }

    /** A (possibly different) account signed in: whatever was registered before no longer counts. */
    suspend fun onSignedIn() {
        registerAfresh()
    }

    /** Remote access turned on again (D63): registers like a sign-in does ([sync] bails if it is off again). */
    suspend fun onRemoteEnabled() {
        registerAfresh()
    }

    private suspend fun registerAfresh() {
        // A sign-in that lands while the previous logout is still deleting the token waits for it.
        _signingOut.first { !it }
        mutex.withLock {
            store.clear()
            updateReadiness()
        }
        // Switch off: nothing registers, but a DELETE that Remote-off left owed (signed out at the
        // time) can be sent now that there are credentials.
        if (!isRemoteEnabled()) retryPendingDelete() else sync(knownToken = null, Trigger.SignIn)
    }

    /**
     * Remote access turned off (D63), also run at app start while it is off: the relay must stop
     * waking this phone, so the registered token is removed server-side as [logout] does it,
     * under the registration lock, but the credentials and the FCM token stay and nothing is
     * treated as a sign-out. The DELETE is recorded as a [PendingTokenDelete] before the
     * registration is cleared, so a process death or a DELETE that does not get through leaves
     * a record the delete worker and the next app start finish; while the switch is off no
     * trigger registers again ([sync]). The switch is read again under the lock: an "off" that
     * runs after the user's final "on" must not remove what that "on" registered. A logout in
     * progress removes the token itself.
     */
    suspend fun onRemoteDisabled() {
        val removed = mutex.withLock {
            if (isRemoteEnabled() || isSigningOut) return
            val token = registeredTokenLocked() ?: return@withLock finishPendingDeleteLocked()
            // Record first: from here on a process death cannot leave the token registered. A record
            // for a different token wins, since it is the one still owed; this cannot happen in
            // practice, as sync finishes the record before it registers anything new.
            store.updatePendingDelete { current ->
                if (current?.serverDelete == true) current else PendingTokenDelete(token, serverDelete = true, localDelete = current?.localDelete == true)
            }
            val owed = store.pendingDelete?.token ?: token
            store.clear()
            updateReadiness()
            work.cancel()
            work.cancelDailyCheck()
            val outcome = try {
                registrar.unregister(owed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PushResult.RetryLater(e.message ?: e.javaClass.simpleName)
            }
            when (outcome) {
                PushResult.Ok -> log.info("Push token removed from the server: Remote access is off")
                is PushResult.RetryLater -> {
                    log.warn("Could not remove the push token: ${outcome.message}")
                    return@withLock false
                }
                // Credentials are gone: nothing can send it now, so the record stays (see
                // finishPendingDeleteLocked) until a sign-in with the switch off or a logout sends it.
                PushResult.SignedOut -> {
                    log.warn("Could not remove the push token: signed out; it will be removed at the next sign-in")
                    return@withLock true
                }
                // Retrying a rejected DELETE would not help.
                is PushResult.Rejected -> log.warn("Could not remove the push token: $outcome")
            }
            store.updatePendingDelete { current -> if (current?.token == owed) current?.copy(serverDelete = false) else current }
            true
        }
        if (!removed) {
            log.warn("The push token will be removed once the device is online")
            work.scheduleTokenDelete()
        }
    }

    /**
     * The token the server may hold for this device: the last registered one, or, when the last
     * attempt is still retrying and none was recorded, the current FCM token (a PUT may have
     * landed before the process died), as [logout] does. Null without Firebase. Must hold [mutex].
     */
    private suspend fun registeredTokenLocked(): String? {
        if (!messaging.isAvailable) return null
        val current = store.state.value
        current.token?.let { return it }
        if (current.lastResult != PushRegistrationState.LastResult.Retrying) return null
        return try {
            messaging.token()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not get the push token to remove: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    suspend fun onNewToken(token: String) {
        sync(knownToken = token, Trigger.NewToken)
    }

    /** Runs from the daily WorkManager check, so a long-lived process still re-registers weekly. */
    suspend fun runDailyCheck() {
        sync(knownToken = null, Trigger.DailyCheck)
    }

    /**
     * Runs from the retry worker. Returns false if it should be retried again later. On the
     * [lastAttempt] a transient failure is recorded as [PushRegistrationState.LastResult.Failed].
     */
    suspend fun runScheduledRetry(lastAttempt: Boolean = false): Boolean =
        sync(knownToken = null, if (lastAttempt) Trigger.FinalRetry else Trigger.Retry) !is PushResult.RetryLater

    /**
     * Finishes a token removal that a sign-out could not complete (see [PendingTokenDelete]):
     * the server DELETE with the current credentials if someone is signed in (skipped otherwise),
     * then `deleteToken()`. Returns true when nothing is left to do. Unless [fromWorker], a
     * failure schedules the network-bound delete worker.
     */
    suspend fun retryPendingDelete(fromWorker: Boolean = false): Boolean {
        if (store.pendingDelete == null) return true
        // A logout in progress handles (and re-records) it itself.
        val done = mutex.withLock { !isSigningOut && finishPendingDeleteLocked() }
        if (!done && !fromWorker) work.scheduleTokenDelete()
        return done
    }

    /**
     * Marks the logout as started, so wake-ups and registrations stop right away even before
     * [logout]'s coroutine runs. [logout] calls it too.
     */
    fun beginLogout() {
        _signingOut.value = true
    }

    /** Undoes [beginLogout] when the sign-out could not be started; never call it once [logout] runs. */
    fun abortLogout() {
        _signingOut.value = false
    }

    /**
     * Removes the token server-side, then forgets the registration and calls [clearCredentials]
     * (the DELETE needs them), all under the registration lock so a queued sync cannot register
     * again for the account that is signing out. Then turns FCM auto-init off and invalidates the
     * FCM token. Before the DELETE a [PendingTokenDelete] is recorded; whatever could not be done
     * (DELETE failed, `deleteToken` failed or timed out) stays in it and is finished at the next
     * opportunity ([retryPendingDelete]). The whole locked part is capped at [LOGOUT_TIMEOUT_MS],
     * including the wait for the lock; the registration and [clearCredentials] are cleared
     * whatever happens.
     */
    suspend fun logout(clearCredentials: () -> Unit) {
        beginLogout()
        try {
            var recorded = false
            var cleared = false
            val clearLocal = {
                if (!cleared) {
                    cleared = true
                    if (!recorded && messaging.isAvailable) {
                        // Timed out waiting for the lock: the DELETE never ran.
                        recorded = true
                        val token = store.state.value.token ?: currentToken
                        store.updatePendingDelete { PendingTokenDelete(token, serverDelete = token != null, localDelete = true) }
                    }
                    currentToken = null
                    try {
                        store.clear()
                        updateReadiness()
                    } finally {
                        try {
                            clearCredentials()
                        } finally {
                            // Here rather than before the lock: a sync that held it cannot re-enqueue them after this.
                            work.cancel()
                            work.cancelDailyCheck()
                        }
                    }
                }
            }
            try {
                val finished = withTimeoutOrNull(LOGOUT_TIMEOUT_MS) {
                    mutex.withLock {
                        try {
                            removeFromServer { recorded = true }
                        } finally {
                            clearLocal()
                        }
                    }
                }
                if (finished == null) log.warn("Removing the push token timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not remove the push token: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                // Timed out (or cancelled) while still waiting for the lock.
                clearLocal()
            }
            if (messaging.isAvailable) {
                try {
                    // Auto-init off first, so Firebase does not mint a new token right after the delete.
                    messaging.setAutoInitEnabled(false)
                } catch (e: Exception) {
                    log.warn("Could not disable push auto-init: ${e.message ?: e.javaClass.simpleName}")
                }
                if (deleteLocalToken()) store.updatePendingDelete { it?.copy(localDelete = false) }
                if (store.pendingDelete != null) {
                    log.warn("The push token will be removed once the device is online")
                    work.scheduleTokenDelete()
                }
            }
        } finally {
            _signingOut.value = false
        }
    }

    /**
     * Records the pending delete first (calling [onRecorded]), then sends the DELETE. A DELETE
     * that does not get through (network, 5xx, an exception, the logout timeout) leaves
     * [PendingTokenDelete.serverDelete] set.
     */
    private suspend fun removeFromServer(onRecorded: () -> Unit) {
        if (!messaging.isAvailable) return
        if (!isSignedIn()) {
            store.updatePendingDelete { PendingTokenDelete(null, serverDelete = false, localDelete = true) }
            onRecorded()
            return
        }
        // A registration that landed after this logout began (recorded by sync) is the one to remove.
        val token = store.pendingDelete?.takeIf { it.serverDelete }?.token
            ?: store.state.value.token
            ?: try {
                messaging.token()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not get the push token to remove: ${e.message ?: e.javaClass.simpleName}")
                null
            }
        store.updatePendingDelete { PendingTokenDelete(token, serverDelete = token != null, localDelete = true) }
        onRecorded()
        if (token == null) return
        when (val outcome = registrar.unregister(token)) {
            PushResult.Ok -> log.info("Push token removed from the server")
            is PushResult.RetryLater -> {
                log.warn("Could not remove the push token: $outcome")
                return
            }
            else -> log.warn("Could not remove the push token: $outcome")
        }
        // Retrying a rejected DELETE would not help.
        store.updatePendingDelete { it?.copy(serverDelete = false) }
    }

    /** See [retryPendingDelete]. Must hold [mutex]. */
    private suspend fun finishPendingDeleteLocked(): Boolean {
        val pending = store.pendingDelete ?: return true
        if (!messaging.isAvailable) {
            store.updatePendingDelete { null }
            return true
        }
        val sameRecord = { current: PendingTokenDelete? -> current?.token == pending.token }
        if (pending.serverDelete) {
            val token = pending.token
            // A Remote-off record (no local delete owed) while signed out: the FCM token is still
            // valid and only credentials can remove it, so it stays owed for the next sign-in with
            // the switch off (registerAfresh) or a logout. True, so the delete worker does not loop.
            if (token != null && !isSignedIn() && !pending.localDelete) return true
            if (token != null && isSignedIn()) {
                val outcome = try {
                    registrar.unregister(token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PushResult.RetryLater(e.message ?: e.javaClass.simpleName)
                }
                if (outcome is PushResult.RetryLater) {
                    log.warn("Could not remove the previous push token from the server: ${outcome.message}")
                    return false
                }
            }
            store.updatePendingDelete { current -> if (sameRecord(current)) current?.copy(serverDelete = false) else current }
        }
        if (pending.localDelete) {
            if (!deleteLocalToken()) return false
            store.updatePendingDelete { current -> if (sameRecord(current)) current?.copy(localDelete = false) else current }
        }
        log.info("Removed the push token left over from the last sign-out")
        return store.pendingDelete == null
    }

    /** `deleteToken()`, capped at [DELETE_TOKEN_TIMEOUT_MS]. Returns false if it failed or timed out. */
    private suspend fun deleteLocalToken(): Boolean = try {
        val deleted = withTimeoutOrNull(DELETE_TOKEN_TIMEOUT_MS) { messaging.deleteToken() } != null
        if (!deleted) log.warn("Deleting the local push token timed out")
        deleted
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not delete the local push token: ${e.message ?: e.javaClass.simpleName}")
        false
    }

    private enum class Trigger(
        val fromRetryWorker: Boolean = false,
        val finalRetry: Boolean = false,
        /** Sign-in and app start also make sure FCM auto-init is on and the daily check is enqueued. */
        val setsUp: Boolean = false,
    ) {
        AppStart(setsUp = true),
        SignIn(setsUp = true),
        NewToken,
        DailyCheck,
        Retry(fromRetryWorker = true),
        FinalRetry(fromRetryWorker = true, finalRetry = true),
    }

    /** True once a logout has started, or has already cleared everything while this sync waited. */
    private fun loggedOutMeanwhile(): Boolean = isSigningOut || !isSignedIn()

    /** Returns null when nothing needed doing. */
    private suspend fun sync(knownToken: String?, trigger: Trigger): PushResult? = mutex.withLock {
        if (!messaging.isAvailable || isSigningOut || !isSignedIn() || !isRemoteEnabled()) return null
        val now = clock.millis()
        // Before any token is fetched, so the token sign-out meant to delete is not registered again.
        val previousRemoved = store.pendingDelete == null || finishPendingDeleteLocked()
        if (trigger.setsUp) setUp()
        if (!previousRemoved) {
            if (loggedOutMeanwhile()) return null
            val message = "Could not remove the previous push token"
            recordTransientFailure(message, now, trigger)
            return PushResult.RetryLater(message)
        }
        val token = knownToken ?: try {
            messaging.token()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (loggedOutMeanwhile()) return null
            val message = "Could not get a push token: ${e.message ?: e.javaClass.simpleName}"
            log.warn(message)
            recordTransientFailure(message, now, trigger)
            return PushResult.RetryLater(message)
        }
        // A logout may have begun (or given up waiting for this lock) while the token was fetched.
        if (loggedOutMeanwhile()) return null
        currentToken = token
        updateReadiness()
        val current = store.state.value
        val registeredAt = current.registeredAtMs
        val upToDate = current.lastResult == PushRegistrationState.LastResult.Registered &&
            current.token == token &&
            current.registeredAppVersion == appVersion &&
            registeredAt != null &&
            now - registeredAt in 0 until REREGISTER_AFTER_MS
        if (upToDate) return null

        val result = registrar.register(token)
        if (loggedOutMeanwhile()) {
            // The logout has removed (or is removing) a different registration; this one landed
            // after it began, so it is owed a DELETE too. A rejected PUT registered nothing.
            if (result !is PushResult.Rejected && result != PushResult.SignedOut) {
                store.updatePendingDelete { PendingTokenDelete(token, serverDelete = true, localDelete = true) }
                log.warn("A push registration finished after sign-out began; it will be removed")
                work.scheduleTokenDelete()
            }
            return null
        }
        when (result) {
            PushResult.Ok -> {
                store.saveRegistered(token, now, appVersion)
                if (!trigger.fromRetryWorker) work.cancel()
                log.info("Push wake-ups registered")
            }
            is PushResult.RetryLater -> recordTransientFailure(result.message, now, trigger)
            is PushResult.Rejected ->
                store.saveFailure(PushRegistrationState.LastResult.Failed, result.message, now)
            PushResult.SignedOut -> Unit
        }
        updateReadiness()
        result
    }

    private fun setUp() {
        try {
            messaging.setAutoInitEnabled(true)
        } catch (e: Exception) {
            log.warn("Could not enable push auto-init: ${e.message ?: e.javaClass.simpleName}")
        }
        work.ensureDailyCheck()
    }

    private fun recordTransientFailure(message: String, now: Long, trigger: Trigger) {
        if (trigger.finalRetry) {
            log.warn("Push registration: giving up retrying until the next trigger")
            store.saveFailure(PushRegistrationState.LastResult.Failed, "Gave up retrying: $message", now)
        } else {
            store.saveFailure(PushRegistrationState.LastResult.Retrying, message, now)
            if (!trigger.fromRetryWorker) work.schedule()
        }
        updateReadiness()
    }

    private fun updateReadiness() {
        val registered = store.state.value
        _wakeUpsReady.value = messaging.isAvailable &&
            registered.lastResult == PushRegistrationState.LastResult.Registered &&
            registered.token != null &&
            registered.token == currentToken
    }

    companion object {
        const val REREGISTER_AFTER_MS = 7L * 24 * 60 * 60 * 1000

        /** Logout waits at most this long (lock + DELETE) before clearing credentials anyway. */
        const val LOGOUT_TIMEOUT_MS = 10_000L

        /** `FirebaseMessaging.deleteToken()` is given this long before it is left to a later retry. */
        const val DELETE_TOKEN_TIMEOUT_MS = 5_000L
    }
}
