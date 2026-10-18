package ai.ankimcp.ankidroid.billing

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.settings.InMemoryKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillingCoordinatorTest {
    private val accountId = "cd".repeat(32)
    private val plus = SubscriptionProduct(BillingProducts.PLUS, "€4.99", "P1M", "offer-plus")
    private val pro = SubscriptionProduct(BillingProducts.PRO, "€9.99", "P1M", "offer-pro")
    private val host = object : PurchaseHost {}

    private class FakeGateway : BillingGateway {
        val updatesFlow = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 16)
        override val updates = updatesFlow
        var productsResult: GatewayResult<List<SubscriptionProduct>> = GatewayResult.Ok(emptyList())
        var purchasesResult: GatewayResult<List<PlayPurchase>> = GatewayResult.Ok(emptyList())
        var launchResult: GatewayResult<Unit> = GatewayResult.Ok(Unit)
        val launches = mutableListOf<LaunchRequest>()
        val calls = mutableListOf<String>()

        override suspend fun products(productIds: List<String>): GatewayResult<List<SubscriptionProduct>> {
            calls += "products"
            return productsResult
        }

        var purchasesDelayMs = 0L

        override suspend fun purchases(): GatewayResult<List<PlayPurchase>> {
            calls += "purchases"
            if (purchasesDelayMs > 0) delay(purchasesDelayMs)
            return purchasesResult
        }

        override suspend fun launch(host: PurchaseHost, request: LaunchRequest): GatewayResult<Unit> {
            calls += "launch"
            launches += request
            return launchResult
        }
    }

    private class FakeBackend(private val accountId: String) : BillingBackend {
        var prepareResult: BillingApiResult<PrepareResponse> =
            BillingApiResult.Ok(PrepareResponse(accountId, PrepareMode.New, null))
        val registerResults = ArrayDeque<BillingApiResult<String>>()
        var defaultRegister: BillingApiResult<String> = BillingApiResult.Ok("plus")
        var registerDelayMs = 0L
        val sentTokens = mutableListOf<String>()
        var prepareCalls = 0
        var prepareDelayMs = 0L

        override suspend fun prepare(): BillingApiResult<PrepareResponse> {
            prepareCalls++
            if (prepareDelayMs > 0) delay(prepareDelayMs)
            return prepareResult
        }

        override suspend fun registerPurchase(purchaseToken: String): BillingApiResult<String> {
            sentTokens += purchaseToken
            if (registerDelayMs > 0) delay(registerDelayMs)
            return registerResults.removeFirstOrNull() ?: defaultRegister
        }
    }

    private class FakeStatus : BillingStatusSource {
        var result: BillingApiResult<PlanStatus> =
            BillingApiResult.Ok(PlanStatus("free", PlanStatusKind.Free, PlanProvider.None, null, false, null))

        /** Served first, one per call, before [result]. */
        val queued = ArrayDeque<BillingApiResult<PlanStatus>>()
        var delayMs = 0L
        var calls = 0

        override suspend fun status(): BillingApiResult<PlanStatus> {
            calls++
            val answer = queued.removeFirstOrNull() ?: result
            if (delayMs > 0) delay(delayMs)
            return answer
        }
    }

    private class Harness(
        val gateway: FakeGateway,
        val backend: FakeBackend,
        val status: FakeStatus,
        val store: InMemoryKeyValueStore,
        val tiers: MutableList<String>,
        val coordinator: BillingCoordinator,
        val log: TunnelLog,
    )

    /**
     * Same test dispatcher, but not a child of the test: `advanceUntilIdle` runs its tasks (it
     * skips `backgroundScope`'s) and runTest does not wait for the never-ending updates collector.
     */
    private fun TestScope.harness(scope: CoroutineScope = CoroutineScope(coroutineContext + SupervisorJob())): Harness {
        val gateway = FakeGateway().apply { productsResult = GatewayResult.Ok(listOf(pro, plus)) }
        val backend = FakeBackend(accountId)
        val status = FakeStatus()
        val store = InMemoryKeyValueStore()
        val tiers = mutableListOf<String>()
        val log = TunnelLog(mirrorToLogcat = false)
        val coordinator = BillingCoordinator(
            scope = scope,
            gateway = gateway,
            backend = backend,
            statusSource = status,
            registered = RegisteredPurchases(store),
            log = log,
            onTier = { tiers += it },
            elapsedMs = { currentTime },
        )
        runCurrent()
        return Harness(gateway, backend, status, store, tiers, coordinator, log)
    }

    private fun purchase(
        token: String,
        product: String = BillingProducts.PLUS,
        state: PlayPurchase.State = PlayPurchase.State.Purchased,
        acknowledged: Boolean = false,
        account: String? = accountId,
    ) = PlayPurchase(listOf(product), token, state, acknowledged, account)

    private fun Harness.loaded() {
        coordinator.refresh(force = true)
    }

    @Test
    fun `refresh loads status and Play products sorted by tier`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        val state = h.coordinator.state.value
        assertEquals(Catalog.Loaded(listOf(plus, pro)), state.catalog)
        assertTrue(state.plan is PlanLoad.Loaded)
        assertEquals(listOf("free"), h.tiers)
    }

    @Test
    fun `new purchase passes the obfuscated id, registers once and ends done`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.coordinator.subscribe(BillingProducts.PLUS) { host }
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Launching(BillingProducts.PLUS), h.coordinator.state.value.flow)
        val launch = h.gateway.launches.single()
        assertEquals(accountId, launch.obfuscatedAccountId)
        assertEquals(plus, launch.product)
        assertNull(launch.replace)

        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-1"))))
        advanceUntilIdle()
        assertEquals(listOf("tok-1"), h.backend.sentTokens)
        assertEquals(PurchaseFlow.Done("plus"), h.coordinator.state.value.flow)
        assertTrue(RegisteredPurchases(h.store).contains("tok-1"))
        assertTrue("status reloaded after the purchase", h.status.calls >= 2)

        // Play repeats the purchase later (restore): not sent again once acknowledged.
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-1", acknowledged = true)))
        h.coordinator.refresh(force = true)
        advanceUntilIdle()
        assertEquals(listOf("tok-1"), h.backend.sentTokens)
    }

    @Test
    fun `upgrade replaces the old purchase with prorated charge`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.backend.prepareResult = BillingApiResult.Ok(PrepareResponse(accountId, PrepareMode.Replace, BillingProducts.PLUS))
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("old", acknowledged = true)))
        RegisteredPurchases(h.store).add("old")
        h.coordinator.subscribe(BillingProducts.PRO) { host }
        advanceUntilIdle()
        assertEquals(
            ReplaceRequest("old", BillingProducts.PLUS, ReplacementMode.ChargeProratedPrice),
            h.gateway.launches.single().replace,
        )
        assertEquals(accountId, h.gateway.launches.single().obfuscatedAccountId)
    }

    @Test
    fun `downgrade is deferred`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.backend.prepareResult = BillingApiResult.Ok(PrepareResponse(accountId, PrepareMode.Replace, BillingProducts.PRO))
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("old", product = BillingProducts.PRO, acknowledged = true)))
        RegisteredPurchases(h.store).add("old")
        h.coordinator.subscribe(BillingProducts.PLUS) { host }
        advanceUntilIdle()
        assertEquals(ReplacementMode.Deferred, h.gateway.launches.single().replace?.mode)
    }

    @Test
    fun `replace with the current product launches nothing`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.backend.prepareResult = BillingApiResult.Ok(PrepareResponse(accountId, PrepareMode.Replace, BillingProducts.PLUS))
        h.coordinator.subscribe(BillingProducts.PLUS) { host }
        advanceUntilIdle()
        assertTrue(h.gateway.launches.isEmpty())
        assertEquals(PurchaseFlow.Idle, h.coordinator.state.value.flow)
    }

    @Test
    fun `replace without the old purchase on this Google account fails`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.backend.prepareResult = BillingApiResult.Ok(PrepareResponse(accountId, PrepareMode.Replace, BillingProducts.PLUS))
        h.coordinator.subscribe(BillingProducts.PRO) { host }
        advanceUntilIdle()
        assertTrue(h.gateway.launches.isEmpty())
        assertEquals(PurchaseFlow.Failed(BillingError.ReplaceOtherAccount), h.coordinator.state.value.flow)
    }

    @Test
    fun `blocked never opens Play`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.backend.prepareResult = BillingApiResult.Ok(PrepareResponse(accountId, PrepareMode.Blocked, null))
        h.coordinator.subscribe(BillingProducts.PRO) { host }
        advanceUntilIdle()
        assertTrue(h.gateway.launches.isEmpty())
        assertEquals(PurchaseFlow.Failed(BillingError.ManagedElsewhere), h.coordinator.state.value.flow)
    }

    @Test
    fun `pending is not registered until it is purchased`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.coordinator.subscribe(BillingProducts.PRO) { host }
        advanceUntilIdle()
        h.gateway.updatesFlow.emit(
            PurchaseUpdate.Purchases(listOf(purchase("tok-p", BillingProducts.PRO, PlayPurchase.State.Pending))),
        )
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Pending(BillingProducts.PRO), h.coordinator.state.value.flow)
        assertTrue(h.backend.sentTokens.isEmpty())

        h.backend.defaultRegister = BillingApiResult.Ok("pro")
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-p", BillingProducts.PRO))))
        advanceUntilIdle()
        assertEquals(listOf("tok-p"), h.backend.sentTokens)
        assertEquals(PurchaseFlow.Done("pro"), h.coordinator.state.value.flow)
    }

    @Test
    fun `pending found at restore shows pending, then the purchase made while the app was closed is registered`() = runTest {
        val h = harness()
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-c", state = PlayPurchase.State.Pending)))
        h.loaded()
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Pending(BillingProducts.PLUS), h.coordinator.state.value.flow)
        assertTrue(h.backend.sentTokens.isEmpty())

        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-c")))
        h.coordinator.refresh(force = true)
        advanceUntilIdle()
        assertEquals(listOf("tok-c"), h.backend.sentTokens)
        assertEquals(PurchaseFlow.Done("plus"), h.coordinator.state.value.flow)
    }

    @Test
    fun `403 409 and 503 map to their errors without retries`() = runTest {
        for ((result, error) in listOf(
            BillingApiResult.NotOwned to BillingError.NotOwned,
            BillingApiResult.AlreadySubscribed to BillingError.AlreadySubscribed,
            BillingApiResult.BillingDisabled to BillingError.BillingDisabled,
        )) {
            val h = harness()
            h.loaded()
            advanceUntilIdle()
            h.backend.defaultRegister = result
            h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-x"))))
            advanceUntilIdle()
            assertEquals(PurchaseFlow.Failed(error), h.coordinator.state.value.flow)
            assertEquals(1, h.backend.sentTokens.size)
            assertFalse(RegisteredPurchases(h.store).contains("tok-x"))
        }
    }

    @Test
    fun `a refused token is not sent again by restore in the same process`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.backend.defaultRegister = BillingApiResult.NotOwned
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-n"))))
        advanceUntilIdle()
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-n")))
        h.coordinator.refresh(force = true)
        advanceUntilIdle()
        assertEquals(listOf("tok-n"), h.backend.sentTokens)
    }

    @Test
    fun `5xx is retried with backoff until it succeeds`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.backend.registerResults += BillingApiResult.RetryLater("Server error (HTTP 500)")
        h.backend.registerResults += BillingApiResult.RetryLater("Server error (HTTP 502)")
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-r"))))
        runCurrent()
        assertEquals(1, h.backend.sentTokens.size)
        assertEquals(PurchaseFlow.Registering(BillingProducts.PLUS), h.coordinator.state.value.flow)
        advanceTimeBy(2_001)
        assertEquals(2, h.backend.sentTokens.size)
        advanceTimeBy(4_001)
        assertEquals(3, h.backend.sentTokens.size)
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Done("plus"), h.coordinator.state.value.flow)
    }

    @Test
    fun `retries run out into a network error and the next refresh sends the token again`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.backend.defaultRegister = BillingApiResult.RetryLater("Server error (HTTP 500)")
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-e"))))
        advanceUntilIdle()
        assertEquals(4, h.backend.sentTokens.size)
        assertEquals(BillingError.Network, (h.coordinator.state.value.flow as PurchaseFlow.Failed).error)

        h.backend.defaultRegister = BillingApiResult.Ok("plus")
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-e")))
        h.coordinator.retry()
        advanceUntilIdle()
        assertEquals(5, h.backend.sentTokens.size)
        assertEquals(PurchaseFlow.Done("plus"), h.coordinator.state.value.flow)
    }

    @Test
    fun `restore sends unacknowledged or unknown purchases once and skips other accounts`() = runTest {
        val h = harness()
        RegisteredPurchases(h.store).add("known-acked")
        h.gateway.purchasesResult = GatewayResult.Ok(
            listOf(
                purchase("unacked"),
                purchase("acked-unknown", product = BillingProducts.PRO, acknowledged = true),
                purchase("known-acked", acknowledged = true),
                purchase("other-account", account = "ef".repeat(32)),
                PlayPurchase(listOf("some_other_app_sku"), "foreign", PlayPurchase.State.Purchased, false, accountId),
            ),
        )
        h.loaded()
        advanceUntilIdle()
        assertEquals(listOf("unacked", "acked-unknown"), h.backend.sentTokens)
        // Background restore does not put a banner on the card.
        assertEquals(PurchaseFlow.Idle, h.coordinator.state.value.flow)

        h.gateway.purchasesResult = GatewayResult.Ok(
            listOf(
                purchase("unacked", acknowledged = true),
                purchase("acked-unknown", product = BillingProducts.PRO, acknowledged = true),
            ),
        )
        h.coordinator.refresh(force = true)
        advanceUntilIdle()
        assertEquals(listOf("unacked", "acked-unknown"), h.backend.sentTokens)
    }

    @Test
    fun `restore with nothing to send does not call prepare`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        assertEquals(0, h.backend.prepareCalls)
    }

    @Test
    fun `the same token arriving from the listener and restore at once is sent once`() = runTest {
        val h = harness()
        h.backend.registerDelayMs = 1_000
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-dup")))
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-dup"))))
        h.loaded()
        advanceUntilIdle()
        assertEquals(listOf("tok-dup"), h.backend.sentTokens)
    }

    @Test
    fun `user cancel returns to idle`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.coordinator.subscribe(BillingProducts.PLUS) { host }
        advanceUntilIdle()
        h.gateway.updatesFlow.emit(PurchaseUpdate.Canceled)
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Idle, h.coordinator.state.value.flow)
    }

    @Test
    fun `already owned runs a restore`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.gateway.launchResult = GatewayResult.Error(GatewayError.AlreadyOwned, "owned")
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-o")))
        h.coordinator.subscribe(BillingProducts.PLUS) { host }
        advanceUntilIdle()
        assertEquals(listOf("tok-o"), h.backend.sentTokens)
        assertEquals(PurchaseFlow.Done("plus"), h.coordinator.state.value.flow)
    }

    @Test
    fun `Play unavailable shows on the catalog and blocks nothing else`() = runTest {
        val h = harness()
        h.gateway.productsResult = GatewayResult.Error(GatewayError.Unavailable, "no Play")
        h.loaded()
        advanceUntilIdle()
        assertEquals(Catalog.Unavailable(GatewayError.Unavailable), h.coordinator.state.value.catalog)
        h.coordinator.subscribe(BillingProducts.PLUS) { host }
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Failed(BillingError.ProductUnavailable), h.coordinator.state.value.flow)
    }

    @Test
    fun `a failed status keeps the last loaded one`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        val loaded = h.coordinator.state.value.plan
        h.status.result = BillingApiResult.RetryLater("Server error (HTTP 500)")
        h.coordinator.refresh(force = true)
        advanceUntilIdle()
        assertEquals(loaded, h.coordinator.state.value.plan)
    }

    @Test
    fun `first status failure shows failed`() = runTest {
        val h = harness()
        h.status.result = BillingApiResult.RetryLater("Server error (HTTP 500)")
        h.loaded()
        advanceUntilIdle()
        assertEquals(PlanLoad.Failed, h.coordinator.state.value.plan)
    }

    @Test
    fun `refresh is throttled unless forced`() = runTest {
        val h = harness()
        h.coordinator.refresh()
        advanceUntilIdle()
        h.coordinator.refresh()
        advanceUntilIdle()
        assertEquals(1, h.status.calls)
        advanceTimeBy(BillingCoordinator.REFRESH_INTERVAL_MS + 1)
        h.coordinator.refresh()
        advanceUntilIdle()
        assertEquals(2, h.status.calls)
        h.coordinator.refresh(force = true)
        advanceUntilIdle()
        assertEquals(3, h.status.calls)
    }

    @Test
    fun `sign-out resets the state and forgets registered tokens`() = runTest {
        val h = harness()
        RegisteredPurchases(h.store).add("tok-s")
        h.loaded()
        advanceUntilIdle()
        h.coordinator.onSignedOut()
        assertEquals(BillingState(), h.coordinator.state.value)
        assertFalse(RegisteredPurchases(h.store).contains("tok-s"))
    }

    @Test
    fun `a registration finishing after sign-out changes nothing`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.backend.registerDelayMs = 1_000
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-late"))))
        runCurrent()
        h.coordinator.onSignedOut()
        advanceUntilIdle()
        assertEquals(BillingState(), h.coordinator.state.value)
        assertFalse(RegisteredPurchases(h.store).contains("tok-late"))
    }

    @Test
    fun `only products, purchases and launch are ever called on Play, never an acknowledge`() = runTest {
        val h = harness()
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-a")))
        h.loaded()
        advanceUntilIdle()
        h.coordinator.subscribe(BillingProducts.PRO) { host }
        advanceUntilIdle()
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-b", BillingProducts.PRO))))
        advanceUntilIdle()
        assertEquals(setOf("products", "purchases", "launch"), h.gateway.calls.toSet())
        // BillingGateway has no acknowledge method at all; the server acknowledges.
        assertTrue(BillingGateway::class.java.methods.none { it.name.contains("acknowledge", ignoreCase = true) })
    }

    @Test
    fun `the log never carries purchase tokens`() = runTest {
        val h = harness()
        h.backend.defaultRegister = BillingApiResult.RetryLater("x")
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("secret-token-123"))))
        advanceUntilIdle()
        assertFalse(h.log.entries.value.any { it.message.contains("secret-token-123") })
        assertFalse(purchase("secret-token-123").toString().contains("secret-token-123"))
    }

    // Review fixes (D62).

    @Test
    fun `OK without purchases (deferred downgrade) leaves launching through a restore`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.backend.prepareResult = BillingApiResult.Ok(PrepareResponse(accountId, PrepareMode.Replace, BillingProducts.PRO))
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("old", product = BillingProducts.PRO, acknowledged = true)))
        RegisteredPurchases(h.store).add("old")
        h.coordinator.subscribe(BillingProducts.PLUS) { host }
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Launching(BillingProducts.PLUS), h.coordinator.state.value.flow)

        // Play lists the new (deferred) purchase, but the listener got only a status.
        h.gateway.purchasesResult = GatewayResult.Ok(
            listOf(purchase("old", product = BillingProducts.PRO, acknowledged = true), purchase("deferred-new")),
        )
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(emptyList()))
        advanceUntilIdle()
        assertEquals(listOf("deferred-new"), h.backend.sentTokens)
        assertEquals(PurchaseFlow.Done("plus"), h.coordinator.state.value.flow)
    }

    @Test
    fun `OK without purchases and nothing new in Play goes back to idle`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.coordinator.subscribe(BillingProducts.PLUS) { host }
        advanceUntilIdle()
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(emptyList()))
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Idle, h.coordinator.state.value.flow)
    }

    @Test
    fun `account A ends, B signs in - nothing of A reaches B`() = runTest {
        val h = harness()
        val statusA = PlanStatus("pro", PlanStatusKind.Active, PlanProvider.GooglePlay, null, false, BillingProducts.PRO)
        val statusB = PlanStatus("free", PlanStatusKind.Free, PlanProvider.None, null, false, null)
        h.coordinator.onAccountChanged("user-a")
        RegisteredPurchases(h.store).add("tok-of-a")
        h.status.delayMs = 1_000
        h.status.queued += BillingApiResult.Ok(statusA)
        h.status.result = BillingApiResult.Ok(statusB)
        h.coordinator.refresh(force = true)
        runCurrent()
        // A's status is still in flight when the session ends (no Sign out tap) and B signs in.
        h.coordinator.onAccountChanged(null)
        assertEquals(BillingState(), h.coordinator.state.value)
        assertFalse(RegisteredPurchases(h.store).contains("tok-of-a"))
        h.coordinator.onAccountChanged("user-b")
        h.coordinator.onSignedIn()
        advanceUntilIdle()
        assertFalse("A's tier was written for B", "pro" in h.tiers)
        assertEquals(PlanLoad.Loaded(statusB), h.coordinator.state.value.plan)
    }

    @Test
    fun `a direct switch from A to B resets and refreshes for B`() = runTest {
        val h = harness()
        h.coordinator.onAccountChanged("user-a")
        h.loaded()
        advanceUntilIdle()
        val calls = h.status.calls
        h.coordinator.onAccountChanged("user-b")
        advanceUntilIdle()
        assertEquals(calls + 1, h.status.calls)
        assertTrue(h.coordinator.state.value.plan is PlanLoad.Loaded)
    }

    @Test
    fun `the first account seen after start keeps the registered tokens`() = runTest {
        val h = harness()
        RegisteredPurchases(h.store).add("tok-kept")
        h.coordinator.onAccountChanged(null)
        h.coordinator.onAccountChanged("user-a")
        assertTrue(RegisteredPurchases(h.store).contains("tok-kept"))
    }

    @Test
    fun `Subscribe tapped during background restore is refused`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.backend.registerDelayMs = 5_000
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-bg")))
        h.coordinator.refresh(force = true)
        advanceTimeBy(100)
        assertEquals(PurchaseFlow.Registering(BillingProducts.PLUS), h.coordinator.state.value.flow)
        h.coordinator.subscribe(BillingProducts.PRO) { host }
        advanceUntilIdle()
        assertTrue(h.gateway.launches.isEmpty())
        assertEquals("only the restore's prepare", 1, h.backend.prepareCalls)
        // A background restore leaves no banner behind.
        assertEquals(PurchaseFlow.Idle, h.coordinator.state.value.flow)
    }

    @Test
    fun `Subscribe while a payment is pending is refused`() = runTest {
        val h = harness()
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-p", state = PlayPurchase.State.Pending)))
        h.loaded()
        advanceUntilIdle()
        h.coordinator.subscribe(BillingProducts.PRO) { host }
        advanceUntilIdle()
        assertEquals(0, h.backend.prepareCalls)
        assertEquals(PurchaseFlow.Pending(BillingProducts.PLUS), h.coordinator.state.value.flow)
    }

    @Test
    fun `a pending payment that vanished clears on a background refresh`() = runTest {
        val h = harness()
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-v", state = PlayPurchase.State.Pending)))
        h.loaded()
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Pending(BillingProducts.PLUS), h.coordinator.state.value.flow)
        h.gateway.purchasesResult = GatewayResult.Ok(emptyList())
        h.coordinator.refresh(force = true)
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Idle, h.coordinator.state.value.flow)
        assertTrue(h.coordinator.state.value.plan is PlanLoad.Loaded)
    }

    @Test
    fun `Try again on a pending payment that still waits keeps pending`() = runTest {
        val h = harness()
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-w", state = PlayPurchase.State.Pending)))
        h.loaded()
        advanceUntilIdle()
        h.coordinator.retry()
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Pending(BillingProducts.PLUS), h.coordinator.state.value.flow)
    }

    @Test
    fun `already owned by another account shows not owned, and is not prepared again`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.gateway.launchResult = GatewayResult.Error(GatewayError.AlreadyOwned, "owned")
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-other", account = "ef".repeat(32))))
        h.coordinator.subscribe(BillingProducts.PLUS) { host }
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Failed(BillingError.NotOwned), h.coordinator.state.value.flow)
        assertTrue(h.backend.sentTokens.isEmpty())
        val prepares = h.backend.prepareCalls

        h.coordinator.refresh(force = true)
        advanceUntilIdle()
        h.coordinator.refresh(force = true)
        advanceUntilIdle()
        assertEquals(prepares, h.backend.prepareCalls)
        assertTrue(h.backend.sentTokens.isEmpty())
    }

    @Test
    fun `interactive restore of a purchase bought outside the app shows not owned`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-outside", account = null)))
        h.coordinator.retry()
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Failed(BillingError.NotOwned), h.coordinator.state.value.flow)
        assertTrue(h.backend.sentTokens.isEmpty())
    }

    @Test
    fun `the listener checks the obfuscated id like restore`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-l", account = "ef".repeat(32)))))
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Failed(BillingError.NotOwned), h.coordinator.state.value.flow)
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-n", account = null))))
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Failed(BillingError.NotOwned), h.coordinator.state.value.flow)
        assertTrue(h.backend.sentTokens.isEmpty())
    }

    @Test
    fun `the screen is asked for after prepare, and a gone screen fails with Try again`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        var preparesWhenAsked = -1
        h.coordinator.subscribe(BillingProducts.PLUS) {
            preparesWhenAsked = h.backend.prepareCalls
            null
        }
        advanceUntilIdle()
        assertEquals(1, preparesWhenAsked)
        assertTrue(h.gateway.launches.isEmpty())
        assertEquals(PurchaseFlow.Failed(BillingError.NoScreen), h.coordinator.state.value.flow)
    }

    @Test
    fun `Try again while a refresh runs restarts it instead of doing nothing`() = runTest {
        val h = harness()
        h.status.delayMs = 10_000
        h.coordinator.refresh(force = true)
        runCurrent()
        h.coordinator.retry()
        advanceUntilIdle()
        assertEquals(2, h.status.calls)
        assertTrue(h.coordinator.state.value.plan is PlanLoad.Loaded)
    }

    @Test
    fun `Subscribe tapped while Play is being asked for purchases is refused`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.gateway.purchasesDelayMs = 2_000
        h.coordinator.refresh(force = true)
        advanceTimeBy(100)
        assertEquals(PurchaseFlow.Checking, h.coordinator.state.value.flow)
        h.coordinator.subscribe(BillingProducts.PRO) { host }
        advanceUntilIdle()
        assertEquals(0, h.backend.prepareCalls)
        assertTrue(h.gateway.launches.isEmpty())
        assertEquals(PurchaseFlow.Idle, h.coordinator.state.value.flow)
    }

    @Test
    fun `a background restore with nothing to send keeps the card as it was`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-d"))))
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Done("plus"), h.coordinator.state.value.flow)
        h.gateway.purchasesResult = GatewayResult.Ok(listOf(purchase("tok-d", acknowledged = true)))
        h.coordinator.refresh(force = true)
        advanceUntilIdle()
        assertEquals(PurchaseFlow.Done("plus"), h.coordinator.state.value.flow)
    }

    @Test
    fun `a listener purchase waits for the purchase the user is preparing`() = runTest {
        val h = harness()
        h.loaded()
        advanceUntilIdle()
        h.backend.prepareDelayMs = 1_000
        h.coordinator.subscribe(BillingProducts.PRO) { host }
        runCurrent()
        assertEquals(PurchaseFlow.Preparing(BillingProducts.PRO), h.coordinator.state.value.flow)
        // An earlier pending Plus payment goes through while Pro is being prepared.
        h.gateway.updatesFlow.emit(PurchaseUpdate.Purchases(listOf(purchase("tok-earlier"))))
        advanceTimeBy(500)
        assertEquals(PurchaseFlow.Preparing(BillingProducts.PRO), h.coordinator.state.value.flow)
        assertTrue(h.backend.sentTokens.isEmpty())
        advanceTimeBy(600)
        // Play's sheet for Pro is up: still not overwritten.
        assertEquals(PurchaseFlow.Launching(BillingProducts.PRO), h.coordinator.state.value.flow)
        assertTrue(h.backend.sentTokens.isEmpty())

        h.gateway.updatesFlow.emit(PurchaseUpdate.Canceled)
        advanceUntilIdle()
        assertEquals(listOf("tok-earlier"), h.backend.sentTokens)
        assertEquals(PurchaseFlow.Done("plus"), h.coordinator.state.value.flow)
    }

    @Test
    fun `an empty user id is the same account, with a warning`() = runTest {
        val h = harness()
        h.coordinator.onAccountChanged("user-a")
        RegisteredPurchases(h.store).add("tok-a")
        h.loaded()
        advanceUntilIdle()
        val plan = h.coordinator.state.value.plan
        h.coordinator.onAccountChanged("")
        assertTrue(RegisteredPurchases(h.store).contains("tok-a"))
        assertEquals(plan, h.coordinator.state.value.plan)
        assertTrue(h.log.entries.value.any { it.message.contains("no user id") })
        // Back to the real id: still the same account.
        h.coordinator.onAccountChanged("user-a")
        assertTrue(RegisteredPurchases(h.store).contains("tok-a"))
    }

    @Test
    fun `products Play does not return are listed as missing`() = runTest {
        val h = harness()
        h.gateway.productsResult = GatewayResult.Ok(listOf(pro))
        h.loaded()
        advanceUntilIdle()
        assertEquals(Catalog.Loaded(listOf(pro), missing = listOf(BillingProducts.PLUS)), h.coordinator.state.value.catalog)
    }
}
