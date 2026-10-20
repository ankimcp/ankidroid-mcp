package ai.ankimcp.ankidroid.push

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.settings.InMemoryKeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class PushCoordinatorTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private class FakeMessaging(var available: Boolean = true, var token: String = "token-1") : PushMessaging {
        var tokenError: Exception? = null
        var onToken: suspend () -> Unit = {}
        var deletes = 0
        var onDelete: suspend () -> Unit = {}
        val autoInit = mutableListOf<Boolean>()

        override val isAvailable: Boolean get() = available

        override suspend fun token(): String {
            onToken()
            tokenError?.let { throw it }
            return token
        }

        override suspend fun deleteToken() {
            deletes++
            onDelete()
        }

        override fun setAutoInitEnabled(enabled: Boolean) {
            autoInit += enabled
        }
    }

    private class FakeRegistrar : PushRegistrar {
        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()
        var registerResult: PushResult = PushResult.Ok
        var onRegister: suspend () -> Unit = {}
        var onUnregister: suspend () -> PushResult = { PushResult.Ok }

        override suspend fun register(token: String): PushResult {
            registered += token
            onRegister()
            return registerResult
        }

        override suspend fun unregister(token: String): PushResult {
            unregistered += token
            return onUnregister()
        }
    }

    private class FakeWork : PushWorkScheduler {
        var scheduled = 0
        var cancelled = 0
        var dailyEnsured = 0
        var dailyCancelled = 0
        var tokenDeletesScheduled = 0
        var events: MutableList<String>? = null

        override fun schedule() {
            scheduled++
        }

        override fun cancel() {
            cancelled++
            events?.add("cancel")
        }

        override fun ensureDailyCheck() {
            dailyEnsured++
        }

        override fun cancelDailyCheck() {
            dailyCancelled++
            events?.add("cancelDailyCheck")
        }

        override fun scheduleTokenDelete() {
            tokenDeletesScheduled++
        }
    }

    private val clock = MutableClock(Instant.parse("2026-10-15T10:00:00Z"))
    private val messaging = FakeMessaging()
    private val registrar = FakeRegistrar()
    private val work = FakeWork()
    private val prefs = InMemoryKeyValueStore()
    private val store = PushRegistrationStore(prefs)
    private val log = TunnelLog(mirrorToLogcat = false)
    private var signedIn = true
    private var remoteEnabled = true

    /** A coordinator as a (new) process would build it, over [store] (or a fresh read of the same prefs). */
    private fun newCoordinator(appVersion: String = "0.1.0", store: PushRegistrationStore = this.store) = PushCoordinator(
        messaging = messaging,
        registrar = registrar,
        store = store,
        isSignedIn = { signedIn },
        isRemoteEnabled = { remoteEnabled },
        appVersion = appVersion,
        work = work,
        log = log,
        clock = clock,
    )

    private val coordinator = newCoordinator()

    private fun days(n: Long) = n * 24 * 60 * 60 * 1000

    @Test
    fun `a new token is registered and recorded`() = runBlocking {
        coordinator.onNewToken("token-2")

        assertEquals(listOf("token-2"), registrar.registered)
        val state = store.state.value
        assertEquals("token-2", state.token)
        assertEquals(clock.millis(), state.registeredAtMs)
        assertEquals("0.1.0", state.registeredAppVersion)
        assertEquals(PushRegistrationState.LastResult.Registered, state.lastResult)
        assertTrue(coordinator.wakeUpsReady)
    }

    @Test
    fun `an unchanged token within 7 days does nothing on app start`() = runBlocking {
        coordinator.onAppStart()
        clock.now = clock.now.plusMillis(days(7) - 1)

        coordinator.onAppStart()
        coordinator.onNewToken("token-1")

        assertEquals(listOf("token-1"), registrar.registered)
    }

    @Test
    fun `a registration 7 days old is renewed on app start`() = runBlocking {
        coordinator.onAppStart()
        clock.now = clock.now.plusMillis(days(7))

        coordinator.onAppStart()

        assertEquals(listOf("token-1", "token-1"), registrar.registered)
        assertEquals(clock.millis(), store.state.value.registeredAtMs)
    }

    @Test
    fun `a changed token is registered on app start`() = runBlocking {
        coordinator.onAppStart()
        messaging.token = "token-2"

        coordinator.onAppStart()

        assertEquals(listOf("token-1", "token-2"), registrar.registered)
        assertEquals("token-2", store.state.value.token)
    }

    @Test
    fun `an app update re-registers so the server sees the new version`() = runBlocking {
        coordinator.onAppStart()

        newCoordinator(appVersion = "0.2.0").onAppStart()

        assertEquals(listOf("token-1", "token-1"), registrar.registered)
        assertEquals("0.2.0", store.state.value.registeredAppVersion)
    }

    @Test
    fun `sign-in re-registers even an up-to-date token`() = runBlocking {
        coordinator.onAppStart()

        coordinator.onSignedIn()

        assertEquals(listOf("token-1", "token-1"), registrar.registered)
    }

    @Test
    fun `a server error schedules a retry, and the retry registers`() = runBlocking {
        registrar.registerResult = PushResult.RetryLater("Server error (HTTP 503)")

        coordinator.onNewToken("token-1")

        assertEquals(1, work.scheduled)
        val failed = store.state.value
        assertEquals(PushRegistrationState.LastResult.Retrying, failed.lastResult)
        assertEquals("Server error (HTTP 503)", failed.lastMessage)
        assertNull(failed.token)
        assertFalse(coordinator.wakeUpsReady)

        registrar.registerResult = PushResult.Ok
        assertTrue(coordinator.runScheduledRetry())

        assertEquals(listOf("token-1", "token-1"), registrar.registered)
        assertEquals("token-1", store.state.value.token)
        assertEquals("the worker never re-enqueues itself", 1, work.scheduled)
    }

    @Test
    fun `a failing scheduled retry asks WorkManager to retry instead of scheduling again`() = runBlocking {
        registrar.registerResult = PushResult.RetryLater("Network error")

        assertFalse(coordinator.runScheduledRetry())

        assertEquals(0, work.scheduled)
        assertEquals(PushRegistrationState.LastResult.Retrying, store.state.value.lastResult)
    }

    @Test
    fun `the last retry that fails is shown as failed, not as will retry`() = runBlocking {
        registrar.registerResult = PushResult.RetryLater("Network error")

        assertFalse(coordinator.runScheduledRetry(lastAttempt = true))

        val state = store.state.value
        assertEquals(PushRegistrationState.LastResult.Failed, state.lastResult)
        assertEquals("Gave up retrying: Network error", state.lastMessage)
        assertEquals(0, work.scheduled)
    }

    @Test
    fun `a rejected registration is recorded and not retried`() = runBlocking {
        registrar.registerResult = PushResult.Rejected("HTTP 400: Invalid platform")

        coordinator.onNewToken("token-1")

        assertEquals(0, work.scheduled)
        assertEquals(PushRegistrationState.LastResult.Failed, store.state.value.lastResult)
        assertEquals("HTTP 400: Invalid platform", store.state.value.lastMessage)
    }

    @Test
    fun `a token that cannot be fetched schedules a retry`() = runBlocking {
        messaging.tokenError = IOException("SERVICE_NOT_AVAILABLE")

        coordinator.onAppStart()

        assertTrue(registrar.registered.isEmpty())
        assertEquals(1, work.scheduled)
        assertEquals(PushRegistrationState.LastResult.Retrying, store.state.value.lastResult)
    }

    @Test
    fun `after a failed attempt the same token is registered again, not skipped as up to date`() = runBlocking {
        coordinator.onAppStart()
        messaging.tokenError = IOException("SERVICE_NOT_AVAILABLE")
        coordinator.onAppStart()
        assertFalse(coordinator.wakeUpsReady)

        messaging.tokenError = null
        coordinator.onAppStart()

        assertEquals(listOf("token-1", "token-1"), registrar.registered)
        assertTrue(coordinator.wakeUpsReady)
    }

    @Test
    fun `nothing happens without Firebase or while signed out`() = runBlocking {
        messaging.available = false
        coordinator.onAppStart()
        coordinator.onNewToken("token-1")
        assertFalse(coordinator.wakeUpsReady)

        messaging.available = true
        signedIn = false
        coordinator.onAppStart()
        coordinator.onSignedIn()

        assertTrue(registrar.registered.isEmpty())
        assertEquals(0, work.scheduled)
        assertEquals(0, work.dailyEnsured)
        assertTrue("no FCM auto-init while signed out", messaging.autoInit.isEmpty())
        assertEquals("nothing pending, so no token was deleted", 0, messaging.deletes)
    }

    // wakeUpsReady

    @Test
    fun `wake-ups are ready only once the current token is known to be registered`() = runBlocking {
        coordinator.onAppStart()
        assertTrue(coordinator.wakeUpsReady)

        // A new process with the same stored registration: the FCM token is not known yet.
        val restarted = newCoordinator()
        assertFalse(restarted.wakeUpsReady)

        restarted.onAppStart()
        assertTrue(restarted.wakeUpsReady)
    }

    @Test
    fun `a new token whose registration fails is not ready, though the old one was registered`() = runBlocking {
        coordinator.onAppStart()
        assertTrue(coordinator.wakeUpsReadyState.value)
        registrar.registerResult = PushResult.RetryLater("Network error")

        coordinator.onNewToken("token-2")

        assertEquals("token-1", store.state.value.token)
        assertFalse(coordinator.wakeUpsReadyState.value)
    }

    @Test
    fun `a rejected re-registration is not ready`() = runBlocking {
        coordinator.onAppStart()
        registrar.registerResult = PushResult.Rejected("Unauthorized (HTTP 401)")

        coordinator.onNewToken("token-2")

        assertFalse(coordinator.wakeUpsReady)
    }

    // Daily check and FCM auto-init

    @Test
    fun `app start and sign-in enable FCM auto-init and enqueue the daily check`() = runBlocking {
        coordinator.onAppStart()
        coordinator.onSignedIn()

        assertEquals(listOf(true, true), messaging.autoInit)
        assertEquals("enqueued with KEEP, so repeating it is harmless", 2, work.dailyEnsured)
    }

    @Test
    fun `the daily check renews a 7-day-old registration and otherwise does nothing`() = runBlocking {
        coordinator.onAppStart()
        clock.now = clock.now.plusMillis(days(6))
        coordinator.runDailyCheck()
        assertEquals(listOf("token-1"), registrar.registered)

        clock.now = clock.now.plusMillis(days(1))
        coordinator.runDailyCheck()

        assertEquals(listOf("token-1", "token-1"), registrar.registered)
        assertEquals(clock.millis(), store.state.value.registeredAtMs)
    }

    @Test
    fun `a failing daily check schedules the retry worker`() = runBlocking {
        coordinator.onAppStart()
        clock.now = clock.now.plusMillis(days(7))
        registrar.registerResult = PushResult.RetryLater("Server error (HTTP 503)")

        coordinator.runDailyCheck()

        assertEquals(1, work.scheduled)
    }

    // Logout

    @Test
    fun `logout removes the token server-side before clearing credentials`() = runBlocking {
        coordinator.onAppStart()
        val cancelsBefore = work.cancelled
        val events = mutableListOf<String>()
        work.events = events
        registrar.onUnregister = {
            events += "DELETE"
            PushResult.Ok
        }
        messaging.onDelete = { events += "deleteToken" }

        coordinator.logout {
            events += "clearCredentials"
            signedIn = false
        }

        assertEquals(
            "background work is cancelled under the lock, after the clear",
            listOf("DELETE", "clearCredentials", "cancel", "cancelDailyCheck", "deleteToken"),
            events,
        )
        assertEquals(listOf("token-1"), registrar.unregistered)
        assertEquals(PushRegistrationState(), store.state.value)
        assertEquals("a pending retry is cancelled", cancelsBefore + 1, work.cancelled)
        assertEquals(1, work.dailyCancelled)
        assertEquals("auto-init off before the token is deleted", listOf(true, false), messaging.autoInit)
        assertFalse(coordinator.wakeUpsReady)
        assertFalse(coordinator.isSigningOut)
        assertNull("everything was removed, nothing left pending", store.pendingDelete)
        assertEquals(0, work.tokenDeletesScheduled)
    }

    @Test
    fun `logout clears credentials even if the DELETE throws`() = runBlocking {
        coordinator.onAppStart()
        registrar.onUnregister = { throw IllegalStateException("boom") }
        var cleared = false

        coordinator.logout { cleared = true }

        assertTrue(cleared)
        assertEquals(PushRegistrationState(), store.state.value)
        assertEquals(1, messaging.deletes)
        assertEquals("the DELETE is still owed", PendingTokenDelete("token-1", serverDelete = true, localDelete = false), store.pendingDelete)
    }

    @Test
    fun `logout clears the push store even if fetching the token throws`() = runBlocking {
        messaging.tokenError = IOException("SERVICE_NOT_AVAILABLE")
        store.saveFailure(PushRegistrationState.LastResult.Retrying, "Could not get a push token", 1)
        var cleared = false

        coordinator.logout { cleared = true }

        assertTrue(cleared)
        assertEquals(PushRegistrationState(), store.state.value)
    }

    @Test
    fun `a sync queued during logout does not register the token again`() = runTest {
        coordinator.onAppStart()
        val deleteGate = CompletableDeferred<Unit>()
        registrar.onUnregister = {
            deleteGate.await()
            PushResult.Ok
        }

        val logout = launch {
            coordinator.logout { signedIn = false }
        }
        runCurrent()
        assertTrue(coordinator.isSigningOut)
        // onNewToken arrives while the DELETE is in flight and waits for the lock.
        val queued = launch { coordinator.onNewToken("token-2") }
        runCurrent()
        deleteGate.complete(Unit)
        logout.join()
        queued.join()

        assertEquals(listOf("token-1"), registrar.registered)
        assertEquals(PushRegistrationState(), store.state.value)
    }

    @Test
    fun `a sync already past the entry check when logout begins does not PUT after the clear`() = runTest {
        val tokenGate = CompletableDeferred<Unit>()
        // The sync holds the lock, blocked in the token fetch, having passed the signed-in check.
        messaging.onToken = { tokenGate.await() }
        val sync = launch { coordinator.onAppStart() }
        runCurrent()
        var clearedAt: Long? = null

        val logout = launch {
            coordinator.logout {
                clearedAt = currentTime
                signedIn = false
            }
        }
        advanceTimeBy(PushCoordinator.LOGOUT_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals("logout gave up on the lock and cleared", PushCoordinator.LOGOUT_TIMEOUT_MS, clearedAt)
        logout.join()

        tokenGate.complete(Unit)
        sync.join()

        assertTrue("no PUT after the clear", registrar.registered.isEmpty())
        assertEquals(PushRegistrationState(), store.state.value)
        assertFalse(coordinator.wakeUpsReady)
    }

    @Test
    fun `a token fetch that fails after logout cleared everything does not schedule a retry`() = runTest {
        val tokenGate = CompletableDeferred<Unit>()
        messaging.onToken = { tokenGate.await() }
        messaging.tokenError = IOException("SERVICE_NOT_AVAILABLE")
        val sync = launch { coordinator.onAppStart() }
        runCurrent()

        val logout = launch { coordinator.logout { signedIn = false } }
        advanceTimeBy(PushCoordinator.LOGOUT_TIMEOUT_MS + 1)
        logout.join()
        tokenGate.complete(Unit)
        sync.join()

        assertEquals(0, work.scheduled)
        assertEquals(PushRegistrationState(), store.state.value)
    }

    @Test
    fun `the logout timeout covers waiting for the lock, and a late registration is discarded and marked for removal`() = runTest {
        val putGate = CompletableDeferred<Unit>()
        registrar.onRegister = { putGate.await() }
        // A registration that hangs while holding the lock.
        val sync = launch { coordinator.onAppStart() }
        runCurrent()
        var clearedAt: Long? = null

        val logout = launch {
            coordinator.logout {
                clearedAt = currentTime
                signedIn = false
            }
        }
        advanceTimeBy(PushCoordinator.LOGOUT_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(PushCoordinator.LOGOUT_TIMEOUT_MS, clearedAt)
        assertTrue(registrar.unregistered.isEmpty())
        logout.join()
        val scheduledBefore = work.tokenDeletesScheduled

        putGate.complete(Unit)
        sync.join()
        assertEquals("the PUT that finished after logout is not recorded", PushRegistrationState(), store.state.value)
        assertFalse(coordinator.wakeUpsReady)
        assertEquals(
            "but it is owed a DELETE and a deleteToken",
            PendingTokenDelete("token-1", serverDelete = true, localDelete = true),
            store.pendingDelete,
        )
        assertEquals(scheduledBefore + 1, work.tokenDeletesScheduled)
    }

    @Test
    fun `registration is blocked from the moment logout begins`() = runBlocking {
        coordinator.beginLogout()

        coordinator.onNewToken("token-2")
        coordinator.onAppStart()

        assertTrue(registrar.registered.isEmpty())
        assertTrue(coordinator.isSigningOut)
    }

    @Test
    fun `an aborted logout lets registrations run again`() = runBlocking {
        coordinator.beginLogout()
        coordinator.abortLogout()

        coordinator.onAppStart()

        assertEquals(listOf("token-1"), registrar.registered)
        assertFalse(coordinator.isSigningOut)
    }

    @Test
    fun `logout without Firebase just clears credentials`() = runBlocking {
        messaging.available = false
        var cleared = false

        coordinator.logout { cleared = true }

        assertTrue(cleared)
        assertTrue(registrar.unregistered.isEmpty())
        assertEquals(0, messaging.deletes)
        assertTrue(messaging.autoInit.isEmpty())
        assertNull(store.pendingDelete)
    }

    // Token removal left over from an offline logout

    @Test
    fun `an offline logout leaves a pending delete that the next start finishes`() = runBlocking {
        coordinator.onAppStart()
        registrar.onUnregister = { PushResult.RetryLater("Network error: offline") }
        messaging.onDelete = { throw IOException("SERVICE_NOT_AVAILABLE") }

        coordinator.logout { signedIn = false }

        assertEquals(PendingTokenDelete("token-1", serverDelete = true, localDelete = true), store.pendingDelete)
        assertEquals(1, work.tokenDeletesScheduled)
        assertEquals(PushRegistrationState(), store.state.value)

        // Next process, still signed out, now online: no credentials for the DELETE, so only deleteToken.
        registrar.onUnregister = { PushResult.Ok }
        messaging.onDelete = {}
        val restartedStore = PushRegistrationStore(prefs)
        assertEquals("persisted across processes", store.pendingDelete, restartedStore.pendingDelete)
        newCoordinator(store = restartedStore).onAppStart()

        assertEquals("no DELETE without credentials", listOf("token-1"), registrar.unregistered)
        assertEquals(2, messaging.deletes)
        assertNull(restartedStore.pendingDelete)
        assertNull(PushRegistrationStore(prefs).pendingDelete)
    }

    @Test
    fun `a pending delete is finished with the current credentials before the next registration`() = runBlocking {
        coordinator.onAppStart()
        registrar.onUnregister = { PushResult.RetryLater("Network error: offline") }
        messaging.onDelete = { throw IOException("SERVICE_NOT_AVAILABLE") }
        coordinator.logout { signedIn = false }

        // Signed in again (same or another account) before the record was finished.
        signedIn = true
        val events = mutableListOf<String>()
        registrar.onUnregister = {
            events += "DELETE"
            PushResult.Ok
        }
        messaging.onDelete = { events += "deleteToken" }
        messaging.onToken = { events += "getToken" }
        registrar.onRegister = { events += "PUT" }
        messaging.token = "token-2"

        coordinator.onSignedIn()

        assertEquals(listOf("DELETE", "deleteToken", "getToken", "PUT"), events)
        assertEquals(listOf("token-1", "token-1"), registrar.unregistered)
        assertEquals(listOf("token-1", "token-2"), registrar.registered)
        assertNull(store.pendingDelete)
    }

    @Test
    fun `a pending delete that still fails blocks the registration and schedules a retry`() = runBlocking {
        store.updatePendingDelete { PendingTokenDelete("token-0", serverDelete = true, localDelete = true) }
        registrar.onUnregister = { PushResult.RetryLater("Network error: offline") }

        coordinator.onAppStart()

        assertTrue("the old token is not registered again", registrar.registered.isEmpty())
        assertEquals(1, work.scheduled)
        assertEquals(PushRegistrationState.LastResult.Retrying, store.state.value.lastResult)
        assertEquals(PendingTokenDelete("token-0", serverDelete = true, localDelete = true), store.pendingDelete)
    }

    @Test
    fun `deleteToken that hangs times out, is marked pending and left to the worker`() = runTest {
        coordinator.onAppStart()
        messaging.onDelete = { awaitCancellation() }
        var cleared = false

        val logout = launch { coordinator.logout { cleared = true } }
        advanceTimeBy(PushCoordinator.DELETE_TOKEN_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue("credentials are cleared before the delete", cleared)
        assertTrue("still waiting for deleteToken", coordinator.isSigningOut)

        advanceTimeBy(2)
        logout.join()

        assertFalse(coordinator.isSigningOut)
        assertEquals(PendingTokenDelete("token-1", serverDelete = false, localDelete = true), store.pendingDelete)
        assertEquals(1, work.tokenDeletesScheduled)
        assertTrue(log.entries.value.any { it.message == "Deleting the local push token timed out" })
    }

    @Test
    fun `the delete worker clears the record once it succeeds and does not reschedule itself`() = runBlocking {
        signedIn = false
        store.updatePendingDelete { PendingTokenDelete("token-1", serverDelete = false, localDelete = true) }
        messaging.onDelete = { throw IOException("SERVICE_NOT_AVAILABLE") }

        assertFalse(coordinator.retryPendingDelete(fromWorker = true))
        assertEquals("WorkManager's own retry takes over", 0, work.tokenDeletesScheduled)

        messaging.onDelete = {}
        assertTrue(coordinator.retryPendingDelete(fromWorker = true))
        assertNull(store.pendingDelete)
    }

    @Test
    fun `the pending delete survives the registration being cleared`() {
        store.updatePendingDelete { PendingTokenDelete("token-1", serverDelete = true, localDelete = false) }

        store.clear()

        assertEquals(PendingTokenDelete("token-1", serverDelete = true, localDelete = false), PushRegistrationStore(prefs).pendingDelete)
        assertFalse("never prints the token", store.pendingDelete.toString().contains("token-1"))
    }

    // Remote access switch (D63)

    @Test
    fun `turning Remote access off removes the token from the server and keeps credentials and the FCM token`() = runBlocking {
        coordinator.onAppStart()
        val cancelsBefore = work.cancelled
        var signedInAtDelete = false
        registrar.onUnregister = {
            signedInAtDelete = signedIn
            PushResult.Ok
        }

        remoteEnabled = false
        coordinator.onRemoteDisabled()

        assertEquals(listOf("token-1"), registrar.unregistered)
        assertTrue("the DELETE ran with the credentials still there", signedInAtDelete)
        assertEquals(PushRegistrationState(), store.state.value)
        assertNull(store.pendingDelete)
        assertEquals("the FCM token stays", 0, messaging.deletes)
        assertEquals("auto-init is left as it was", listOf(true), messaging.autoInit)
        assertFalse(coordinator.wakeUpsReady)
        assertFalse("not a sign-out", coordinator.isSigningOut)
        assertEquals(cancelsBefore + 1, work.cancelled)
        assertEquals(1, work.dailyCancelled)
        assertEquals(0, work.tokenDeletesScheduled)
    }

    @Test
    fun `while Remote access is off nothing registers, but a pending delete is still retried`() = runBlocking {
        coordinator.onAppStart()
        registrar.onUnregister = { PushResult.RetryLater("Network error: offline") }
        remoteEnabled = false
        coordinator.onRemoteDisabled()
        assertEquals(PendingTokenDelete("token-1", serverDelete = true, localDelete = false), store.pendingDelete)
        assertEquals("left to the delete worker", 1, work.tokenDeletesScheduled)
        val registeredBefore = registrar.registered.size
        val scheduledBefore = work.scheduled

        coordinator.onAppStart()
        coordinator.onNewToken("token-2")
        coordinator.runDailyCheck()
        assertTrue("the retry worker is done, not retried", coordinator.runScheduledRetry())
        coordinator.onSignedIn()

        assertEquals(registeredBefore, registrar.registered.size)
        assertEquals(scheduledBefore, work.scheduled)
        assertEquals("app start and sign-in each retried the DELETE", listOf("token-1", "token-1", "token-1"), registrar.unregistered)
        assertEquals(PendingTokenDelete("token-1", serverDelete = true, localDelete = false), store.pendingDelete)
        assertFalse(coordinator.wakeUpsReady)

        // Online again: the delete worker finishes it.
        registrar.onUnregister = { PushResult.Ok }
        assertTrue(coordinator.retryPendingDelete(fromWorker = true))
        assertNull(store.pendingDelete)
    }

    @Test
    fun `turning Remote access on registers again like a sign-in`() = runBlocking {
        coordinator.onAppStart()
        remoteEnabled = false
        coordinator.onRemoteDisabled()
        val dailyBefore = work.dailyEnsured

        remoteEnabled = true
        coordinator.onRemoteEnabled()

        assertEquals(listOf("token-1", "token-1"), registrar.registered)
        assertEquals("token-1", store.state.value.token)
        assertTrue(coordinator.wakeUpsReady)
        assertEquals(dailyBefore + 1, work.dailyEnsured)
    }

    @Test
    fun `a sign-in with Remote access off registers nothing`() = runBlocking {
        remoteEnabled = false

        coordinator.onSignedIn()

        assertTrue(registrar.registered.isEmpty())
        assertEquals(0, work.dailyEnsured)
        assertTrue(messaging.autoInit.isEmpty())
    }

    @Test
    fun `turning Remote access off with nothing registered sends no DELETE`() = runBlocking {
        remoteEnabled = false

        coordinator.onRemoteDisabled()

        assertTrue(registrar.unregistered.isEmpty())
        assertNull(store.pendingDelete)
        assertEquals(0, work.tokenDeletesScheduled)
    }

    @Test
    fun `turning Remote access off during a logout leaves the removal to the logout`() = runTest {
        coordinator.onAppStart()
        val deleteGate = CompletableDeferred<Unit>()
        registrar.onUnregister = {
            deleteGate.await()
            PushResult.Ok
        }
        val logout = launch { coordinator.logout { signedIn = false } }
        runCurrent()

        remoteEnabled = false
        val disabled = launch { coordinator.onRemoteDisabled() }
        runCurrent()
        deleteGate.complete(Unit)
        logout.join()
        disabled.join()

        assertEquals("one DELETE, the logout's", listOf("token-1"), registrar.unregistered)
        assertNull(store.pendingDelete)
    }

    @Test
    fun `an off that runs after the user's final on removes nothing`() = runBlocking {
        coordinator.onAppStart()
        val dailyCancelledBefore = work.dailyCancelled

        // Off then on tapped; the coroutines run in reverse: the setting already says on.
        remoteEnabled = true
        coordinator.onRemoteEnabled()
        coordinator.onRemoteDisabled()

        assertTrue(registrar.unregistered.isEmpty())
        assertEquals("token-1", store.state.value.token)
        assertTrue(coordinator.wakeUpsReady)
        assertEquals(dailyCancelledBefore, work.dailyCancelled)
        assertNull(store.pendingDelete)
    }

    @Test
    fun `on then off tapped, run as off then on, ends unregistered`() = runBlocking {
        coordinator.onAppStart()

        remoteEnabled = false
        coordinator.onRemoteDisabled()
        coordinator.onRemoteEnabled()

        assertEquals(listOf("token-1"), registrar.unregistered)
        assertEquals("the on registered nothing with the switch off", listOf("token-1"), registrar.registered)
        assertEquals(PushRegistrationState(), store.state.value)
        assertFalse(coordinator.wakeUpsReady)
    }

    @Test
    fun `off, on, off removes, registers and removes again`() = runBlocking {
        coordinator.onAppStart()

        remoteEnabled = false
        coordinator.onRemoteDisabled()
        remoteEnabled = true
        coordinator.onRemoteEnabled()
        remoteEnabled = false
        coordinator.onRemoteDisabled()

        assertEquals(listOf("token-1", "token-1"), registrar.unregistered)
        assertEquals(listOf("token-1", "token-1"), registrar.registered)
        assertNull(store.pendingDelete)
        assertEquals(PushRegistrationState(), store.state.value)
    }

    @Test
    fun `app start with the switch off and a token still registered removes it`() = runBlocking {
        coordinator.onAppStart()
        // The process died after the setting was written and before the DELETE: a new process.
        remoteEnabled = false
        val restarted = newCoordinator(store = PushRegistrationStore(prefs))

        restarted.onAppStart()

        assertEquals(listOf("token-1"), registrar.unregistered)
        assertEquals(listOf("token-1"), registrar.registered)
        assertEquals(PushRegistrationState(), PushRegistrationStore(prefs).state.value)
        assertNull(PushRegistrationStore(prefs).pendingDelete)
        assertFalse(restarted.wakeUpsReady)

        // Idempotent: the next start finds nothing to remove.
        newCoordinator(store = PushRegistrationStore(prefs)).onAppStart()
        assertEquals(listOf("token-1"), registrar.unregistered)
    }

    @Test
    fun `app start with the switch off and a registration still retrying removes the current FCM token`() = runBlocking {
        // A PUT may have landed before the process died without recording it.
        store.saveFailure(PushRegistrationState.LastResult.Retrying, "Network error", clock.millis())
        remoteEnabled = false

        coordinator.onAppStart()

        assertEquals(listOf("token-1"), registrar.unregistered)
        assertEquals(PushRegistrationState(), store.state.value)
        assertNull(store.pendingDelete)
    }

    @Test
    fun `app start with the switch off and nothing registered sends no DELETE`() = runBlocking {
        remoteEnabled = false

        coordinator.onAppStart()

        assertTrue(registrar.unregistered.isEmpty())
        assertTrue(registrar.registered.isEmpty())
    }

    @Test
    fun `a DELETE answered signed out stays owed until the next sign-in with the switch off`() = runBlocking {
        coordinator.onAppStart()
        // The refresh token died: credentials are gone by the time the DELETE answers.
        registrar.onUnregister = { if (signedIn) PushResult.Ok else PushResult.SignedOut }
        signedIn = false

        remoteEnabled = false
        coordinator.onRemoteDisabled()

        val owed = PendingTokenDelete("token-1", serverDelete = true, localDelete = false)
        assertEquals(owed, store.pendingDelete)
        assertEquals("no worker: only credentials can send it", 0, work.tokenDeletesScheduled)
        assertEquals(PushRegistrationState(), store.state.value)

        // Neither the worker nor an app start while signed out drops it.
        assertTrue(coordinator.retryPendingDelete(fromWorker = true))
        coordinator.onAppStart()
        assertEquals(owed, store.pendingDelete)
        assertEquals(listOf("token-1"), registrar.unregistered)

        signedIn = true
        coordinator.onSignedIn()

        assertEquals(listOf("token-1", "token-1"), registrar.unregistered)
        assertNull(store.pendingDelete)
        assertEquals("the switch is still off", listOf("token-1"), registrar.registered)
        assertEquals(PushRegistrationState(), store.state.value)
    }

    @Test
    fun `on while the off's DELETE is in flight waits for it and registers after`() = runTest {
        coordinator.onAppStart()
        val deleteGate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        registrar.onUnregister = {
            deleteGate.await()
            events += "DELETE"
            PushResult.Ok
        }
        registrar.onRegister = { events += "PUT" }
        remoteEnabled = false
        val off = launch { coordinator.onRemoteDisabled() }
        runCurrent()

        remoteEnabled = true
        val on = launch { coordinator.onRemoteEnabled() }
        runCurrent()
        assertEquals("the on waits for the lock", emptyList<String>(), events)
        deleteGate.complete(Unit)
        off.join()
        on.join()

        assertEquals(listOf("DELETE", "PUT"), events)
        assertEquals("token-1", store.state.value.token)
        assertNull(store.pendingDelete)
        assertTrue(coordinator.wakeUpsReady)
    }

    @Test
    fun `on with a DELETE still owed sends it before the PUT`() = runBlocking {
        coordinator.onAppStart()
        registrar.onUnregister = { PushResult.RetryLater("Network error: offline") }
        remoteEnabled = false
        coordinator.onRemoteDisabled()
        val events = mutableListOf<String>()
        registrar.onUnregister = {
            events += "DELETE"
            PushResult.Ok
        }
        registrar.onRegister = { events += "PUT" }

        remoteEnabled = true
        coordinator.onRemoteEnabled()

        assertEquals(listOf("DELETE", "PUT"), events)
        assertEquals(listOf("token-1", "token-1"), registrar.unregistered)
        assertNull(store.pendingDelete)
        assertEquals("token-1", store.state.value.token)
    }

    @Test
    fun `on with a DELETE still owed that fails again registers nothing and schedules a retry`() = runBlocking {
        coordinator.onAppStart()
        registrar.onUnregister = { PushResult.RetryLater("Network error: offline") }
        remoteEnabled = false
        coordinator.onRemoteDisabled()
        val registeredBefore = registrar.registered.size

        remoteEnabled = true
        coordinator.onRemoteEnabled()

        assertEquals(registeredBefore, registrar.registered.size)
        assertEquals(1, work.scheduled)
        assertEquals(PushRegistrationState.LastResult.Retrying, store.state.value.lastResult)
        assertEquals(PendingTokenDelete("token-1", serverDelete = true, localDelete = false), store.pendingDelete)
    }

    @Test
    fun `logout takes over a DELETE that Remote-off left owed`() = runBlocking {
        coordinator.onAppStart()
        registrar.onUnregister = { PushResult.RetryLater("Network error: offline") }
        remoteEnabled = false
        coordinator.onRemoteDisabled()
        assertEquals(1, work.tokenDeletesScheduled)
        registrar.onUnregister = { PushResult.Ok }

        coordinator.logout { signedIn = false }

        assertEquals("the owed token, not a fresh fetch", listOf("token-1", "token-1"), registrar.unregistered)
        assertNull(store.pendingDelete)
        assertEquals(1, messaging.deletes)
        assertEquals(PushRegistrationState(), store.state.value)
    }
}
