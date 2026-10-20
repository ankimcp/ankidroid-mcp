package ai.ankimcp.ankidroid.billing

import ai.ankimcp.ankidroid.log.TunnelLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Play's products as loaded for the Plan card. */
sealed interface Catalog {
    data object Loading : Catalog

    data class Loaded(
        val products: List<SubscriptionProduct>,
        /** Our products Play did not return, or returned without the `monthly` base plan offer. */
        val missing: List<String> = emptyList(),
    ) : Catalog

    data class Unavailable(val error: GatewayError) : Catalog
}

/** `GET /billing/status` as loaded for the Plan card. */
sealed interface PlanLoad {
    data object Loading : PlanLoad

    data class Loaded(val status: PlanStatus) : PlanLoad

    data object Failed : PlanLoad

    /** The relay has no billing routes (404/405/501): a quiet note, not an error. */
    data object NotAvailable : PlanLoad
}

enum class BillingError {
    SignedOut,

    /** `/prepare` said blocked: the plan is billed on the web or granted by an admin. */
    ManagedElsewhere,

    /**
     * 403, or a Play purchase whose obfuscated account id is missing or another AnkiMCP
     * account's: it cannot be activated for this account.
     */
    NotOwned,

    /** 409. */
    AlreadySubscribed,

    /** 503: Play billing is switched off on the server. */
    BillingDisabled,

    /** The server refused the call (400, other 4xx, an unreadable response). */
    Server,

    /** Network or 5xx, after the retries. */
    Network,
    PlayUnavailable,
    ProductUnavailable,

    /** A plan change, but the live subscription is not on the Google account in this Play Store. */
    ReplaceOtherAccount,

    /** The screen was gone (finishing or destroyed) when Play's sheet was due to open. */
    NoScreen,
    Play,
}

/** idle -> preparing -> launching -> (pending ->) registering -> done | failed. */
sealed interface PurchaseFlow {
    data object Idle : PurchaseFlow

    data class Preparing(val productId: String) : PurchaseFlow

    /** Play's purchase sheet is up. */
    data class Launching(val productId: String) : PurchaseFlow

    /** Play reported the purchase pending (e.g. cash); nothing is applied until it is purchased. */
    data class Pending(val productId: String) : PurchaseFlow

    /** A restore is asking Play for purchases; no line on the card, but no purchase may start. */
    data object Checking : PurchaseFlow

    /** A purchase token is being sent, from the purchase flow or a restore. */
    data class Registering(val productId: String) : PurchaseFlow

    data class Done(val tier: String) : PurchaseFlow

    data class Failed(val error: BillingError, val detail: String? = null) : PurchaseFlow

    val busy: Boolean get() = this is Preparing || this is Launching || this is Checking || this is Registering

    /** No new purchase may start: something is running, or a payment is still pending. */
    val blocksPurchase: Boolean get() = busy || this is Pending
}

data class BillingState(
    val catalog: Catalog = Catalog.Loading,
    val plan: PlanLoad = PlanLoad.Loading,
    val flow: PurchaseFlow = PurchaseFlow.Idle,
)

/**
 * Plain-Kotlin purchase state machine (D62). Runs the purchase (prepare, launch with the
 * obfuscated account id, register the token), restores purchases the server has not seen,
 * and loads the plan status. Never acknowledges (the server does), never registers the same
 * token twice at once or again once accepted, retries `/purchase` on network errors and 5xx
 * with backoff, and logs without tokens. Everything is per account: a change of account drops
 * all state and late results ([onAccountChanged]).
 */
class BillingCoordinator(
    private val scope: CoroutineScope,
    private val gateway: BillingGateway,
    private val backend: BillingBackend,
    private val statusSource: BillingStatusSource,
    private val registered: RegisteredPurchases,
    private val log: TunnelLog,
    /** The server's tier (status or purchase), for the account card's "Plan:" line until the next token refresh. */
    private val onTier: (String) -> Unit,
    private val elapsedMs: () -> Long,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {
    private val _state = MutableStateFlow(BillingState())
    val state: StateFlow<BillingState> = _state.asStateFlow()

    private val lock = Any()
    private val inFlight = mutableSetOf<String>()

    /** 403/409 tokens: not sent again in this process. */
    private val refused = mutableSetOf<String>()

    /** Tokens of another AnkiMCP account or without an obfuscated id: never sent, no `/prepare` for them again. */
    private val skipped = mutableSetOf<String>()

    /** This account's obfuscated id from the last `/prepare`. */
    @Volatile private var accountIdCache: String? = null
    private var lastAccount: String? = null
    private var lastRefreshAt: Long? = null
    private var refreshJob: Job? = null
    private var flowJob: Job? = null
    private val jobs = mutableListOf<Job>()

    /** Bumped at every account change so late results of the old account are dropped. */
    @Volatile private var generation = 0

    /** Bumped by every restore, so a cancelled one's cleanup never touches a newer one's flow. */
    @Volatile private var restoreRun = 0

    init {
        scope.launch { gateway.updates.collect(::onUpdate) }
    }

    /**
     * Status, products and restore. Without [force], at most once per [REFRESH_INTERVAL_MS]
     * (it runs whenever the app comes to the front).
     */
    fun refresh(force: Boolean = false) = refresh(force, interactive = false)

    private fun refresh(force: Boolean, interactive: Boolean) {
        synchronized(lock) {
            val now = elapsedMs()
            val last = lastRefreshAt
            if (!force && last != null && now - last < REFRESH_INTERVAL_MS) return
            if (refreshJob?.isActive == true) return
            lastRefreshAt = now
            refreshJob = track {
                loadStatus()
                loadCatalog()
                restore(interactive)
            }
        }
    }

    /** A fresh sign-in: nothing of a previous account survives, then a full refresh. */
    fun onSignedIn() {
        onSignedOut()
        refresh(force = true)
    }

    fun onSignedOut() {
        synchronized(lock) {
            generation++
            jobs.forEach { it.cancel() }
            jobs.clear()
            refreshJob = null
            flowJob = null
            inFlight.clear()
            refused.clear()
            skipped.clear()
            accountIdCache = null
            lastRefreshAt = null
            registered.clear()
            _state.value = BillingState()
        }
    }

    /**
     * The stored account's user id, from the credential store. Signing out, a session that
     * ended (refresh token rejected, revoked access) and a different account all reset; the
     * first value after start (null while decrypting, then the account) does not. An empty id
     * (a token response without `user.id`) is logged and treated as the same account: it cannot
     * tell accounts apart, and resetting on it would drop a running purchase.
     */
    fun onAccountChanged(userId: String?) {
        if (userId != null && userId.isEmpty()) {
            log.warn("Billing: the signed-in account has no user id; treated as the same account")
            return
        }
        synchronized(lock) {
            val last = lastAccount
            lastAccount = userId
            if (last != null && userId != last) {
                onSignedOut()
                // A to B seen without the null in between (the flow conflates): B still needs its refresh.
                if (userId != null) refresh(force = true)
            }
        }
    }

    /**
     * [host] is asked for the current screen only when Play's sheet is about to open, so no
     * Activity is held across `/prepare`; null means it is finishing or gone.
     */
    fun subscribe(productId: String, host: () -> PurchaseHost?) {
        synchronized(lock) {
            if (_state.value.flow.blocksPurchase || flowJob?.isActive == true) return
            setFlow(PurchaseFlow.Preparing(productId))
            flowJob = track { runPurchase(productId, host) }
        }
    }

    /**
     * "Try again" (errors and Pending): clears the error and reloads everything, re-sending
     * unregistered tokens with the outcome shown on the card. A refresh already running is
     * cancelled and started again, so the tap is never lost.
     */
    fun retry() {
        synchronized(lock) {
            _state.update { if (it.flow is PurchaseFlow.Failed) it.copy(flow = PurchaseFlow.Idle) else it }
            refreshJob?.cancel()
            refreshJob = null
            refresh(force = true, interactive = true)
        }
    }

    fun dismiss() {
        _state.update {
            if (it.flow is PurchaseFlow.Failed || it.flow is PurchaseFlow.Done) it.copy(flow = PurchaseFlow.Idle) else it
        }
    }

    private suspend fun runPurchase(productId: String, host: () -> PurchaseHost?) {
        val product = (_state.value.catalog as? Catalog.Loaded)?.products?.firstOrNull { it.productId == productId }
            ?: return fail(BillingError.ProductUnavailable)
        val gen = generation
        val prepare = when (val result = backend.prepare()) {
            is BillingApiResult.Ok -> result.value
            else -> return fail(apiError(result), apiDetail(result))
        }
        if (gen == generation) accountIdCache = prepare.obfuscatedAccountId
        val replace = when (prepare.mode) {
            PrepareMode.Blocked -> {
                log.info("Billing: plan is managed outside Google Play; no purchase")
                fail(BillingError.ManagedElsewhere)
                loadStatus()
                return
            }
            PrepareMode.New -> null
            PrepareMode.Replace -> {
                val current = prepare.currentProductId
                if (current == productId) {
                    setFlow(PurchaseFlow.Idle)
                    loadStatus()
                    return
                }
                if (current == null) null else replaceRequest(current, productId) ?: return
            }
        }
        val screen = host() ?: run {
            log.warn("Billing: the screen closed before Google Play could open")
            return fail(BillingError.NoScreen)
        }
        setFlow(PurchaseFlow.Launching(productId))
        log.info(
            "Billing: opening Google Play for $productId" +
                (replace?.let { " (replacing ${it.oldProductId}, ${it.mode})" } ?: ""),
        )
        when (val launched = gateway.launch(screen, LaunchRequest(product, prepare.obfuscatedAccountId, replace))) {
            is GatewayResult.Ok -> Unit
            is GatewayResult.Error -> when (launched.error) {
                GatewayError.Canceled -> setFlow(PurchaseFlow.Idle)
                GatewayError.AlreadyOwned -> restore(interactive = true)
                else -> fail(gatewayError(launched.error), launched.message)
            }
        }
    }

    /** The old purchase for a plan change, or null after setting the error. */
    private suspend fun replaceRequest(current: String, productId: String): ReplaceRequest? {
        val purchases = when (val result = gateway.purchases()) {
            is GatewayResult.Ok -> result.value
            is GatewayResult.Error -> {
                fail(gatewayError(result.error), result.message)
                return null
            }
        }
        val old = purchases.firstOrNull { it.state == PlayPurchase.State.Purchased && current in it.productIds }
        if (old == null) {
            log.warn("Billing: the $current subscription is not on this device's Google account")
            fail(BillingError.ReplaceOtherAccount)
            return null
        }
        val mode = if (BillingProducts.rank(productId) > BillingProducts.rank(current)) {
            ReplacementMode.ChargeProratedPrice
        } else {
            ReplacementMode.Deferred
        }
        return ReplaceRequest(old.purchaseToken, current, mode)
    }

    private fun onUpdate(update: PurchaseUpdate) {
        when (update) {
            is PurchaseUpdate.Purchases -> {
                val ours = update.purchases.filter { purchase -> purchase.productIds.any { it in BillingProducts.ALL } }
                val actionable = ours.filter { it.state != PlayPurchase.State.Unspecified }
                if (actionable.isEmpty()) {
                    // OK without a purchase (a deferred downgrade reports only a status): Play's
                    // own list says what happened; never leave the card on "Opening Google Play".
                    if (_state.value.flow is PurchaseFlow.Launching) {
                        log.info("Billing: Google Play finished without a purchase in the result; checking purchases")
                        track { restore(interactive = true) }
                    }
                    return
                }
                for (purchase in actionable) {
                    val productId = purchase.productIds.first { it in BillingProducts.ALL }
                    when (purchase.state) {
                        PlayPurchase.State.Pending -> {
                            log.info("Billing: $productId purchase is pending")
                            setFlow(PurchaseFlow.Pending(productId))
                        }
                        PlayPurchase.State.Purchased -> track { registerFromListener(purchase, productId) }
                        PlayPurchase.State.Unspecified -> Unit
                    }
                }
            }
            PurchaseUpdate.Canceled -> {
                log.info("Billing: purchase canceled")
                _state.update { if (it.flow is PurchaseFlow.Launching) it.copy(flow = PurchaseFlow.Idle) else it }
            }
            is PurchaseUpdate.Failed -> when (update.error) {
                GatewayError.AlreadyOwned -> track { restore(interactive = true) }
                else -> {
                    log.warn("Billing: purchase failed: ${update.message}")
                    _state.update {
                        if (it.flow is PurchaseFlow.Launching) {
                            it.copy(flow = PurchaseFlow.Failed(gatewayError(update.error), update.message))
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    /**
     * A PURCHASED update from Play: same account check as restore, then register. A purchase
     * the user is preparing, or has Play's sheet open for with another product, owns the card;
     * this waits until that flow ends instead of overwriting it.
     */
    private suspend fun registerFromListener(purchase: PlayPurchase, productId: String) {
        if (isSkipped(purchase.purchaseToken)) return fail(BillingError.NotOwned)
        _state.first { state ->
            val flow = state.flow
            !(flow is PurchaseFlow.Preparing || (flow is PurchaseFlow.Launching && flow.productId != productId))
        }
        setFlow(PurchaseFlow.Registering(productId))
        val accountId = when (val result = accountId()) {
            is BillingApiResult.Ok -> result.value
            else -> return fail(apiError(result), apiDetail(result))
        }
        if (purchase.obfuscatedAccountId != accountId) {
            skip(purchase, productId)
            return fail(BillingError.NotOwned)
        }
        when (val outcome = register(purchase, productId)) {
            is RegisterOutcome.Registered -> setFlow(PurchaseFlow.Done(outcome.tier))
            is RegisterOutcome.Failed -> setFlow(PurchaseFlow.Failed(outcome.error, outcome.detail))
            // Sent by another path right now, which reports it; or accepted earlier.
            RegisterOutcome.Skipped -> if (!isInFlight(purchase.purchaseToken)) settleRegistering()
        }
    }

    /**
     * Sends every purchased subscription of this account that the server may not have seen:
     * not acknowledged yet (the server acknowledges), or not registered from this install.
     * Covers purchases completed while the app was closed and pending ones that went through.
     * From before Play is asked until it is done the flow is Checking, then Registering while
     * tokens are sent, so no purchase can start in between.
     */
    private suspend fun restore(interactive: Boolean) {
        val gen = generation
        val run = synchronized(lock) { ++restoreRun }
        val previous = _state.value.flow
        // A purchase flow that is running owns the card; otherwise the restore does.
        val ownsFlow = !previous.busy || previous is PurchaseFlow.Launching
        // The user was waiting on a pending payment or Play's sheet: the outcome is shown even on
        // a background refresh.
        val waiting = interactive || previous is PurchaseFlow.Pending || previous is PurchaseFlow.Launching
        if (ownsFlow) setFlow(PurchaseFlow.Checking)
        var finished = false
        try {
            val purchases = when (val result = gateway.purchases()) {
                is GatewayResult.Ok -> result.value
                is GatewayResult.Error -> {
                    log.warn("Billing: could not read Google Play purchases: ${result.message}")
                    if (ownsFlow) {
                        finishFlow(
                            if (interactive) PurchaseFlow.Failed(gatewayError(result.error), result.message) else rest(previous),
                        )
                    }
                    finished = true
                    return
                }
            }
            val ours = purchases.filter { purchase -> purchase.productIds.any { it in BillingProducts.ALL } }
            val pending = ours.firstOrNull { it.state == PlayPurchase.State.Pending }
                ?.let { PurchaseFlow.Pending(it.productIds.first { id -> id in BillingProducts.ALL }) }
            // Where a background restore leaves the card: a pending payment found (or still
            // there) shows; one Play no longer lists (declined, cancelled) clears.
            val rest = when {
                pending != null && (previous is PurchaseFlow.Idle || previous is PurchaseFlow.Pending) -> pending
                else -> rest(previous)
            }
            val unsent = ours.filter {
                it.state == PlayPurchase.State.Purchased &&
                    synchronized(lock) { it.purchaseToken !in refused } &&
                    (!it.acknowledged || !registered.contains(it.purchaseToken))
            }
            val candidates = unsent.filterNot { isSkipped(it.purchaseToken) }
            if (candidates.isEmpty()) {
                if (ownsFlow) {
                    finishFlow(
                        when {
                            !interactive || rest is PurchaseFlow.Pending -> rest
                            unsent.isNotEmpty() -> PurchaseFlow.Failed(BillingError.NotOwned)
                            else -> PurchaseFlow.Idle
                        },
                    )
                }
                finished = true
                return
            }

            if (ownsFlow) finishFlow(PurchaseFlow.Registering(candidates.first().productIds.first { it in BillingProducts.ALL }))
            val accountId = when (val result = accountId()) {
                is BillingApiResult.Ok -> result.value
                else -> {
                    if (ownsFlow) finishFlow(if (waiting) PurchaseFlow.Failed(apiError(result), apiDetail(result)) else rest)
                    finished = true
                    return
                }
            }
            var done: RegisterOutcome.Registered? = null
            var failed: RegisterOutcome.Failed? = null
            var skippedAny = false
            for (purchase in candidates) {
                val productId = purchase.productIds.first { it in BillingProducts.ALL }
                if (purchase.obfuscatedAccountId != accountId) {
                    skip(purchase, productId)
                    skippedAny = true
                    continue
                }
                if (ownsFlow) finishFlow(PurchaseFlow.Registering(productId))
                when (val outcome = register(purchase, productId)) {
                    is RegisterOutcome.Registered -> done = outcome
                    is RegisterOutcome.Failed -> failed = outcome
                    RegisterOutcome.Skipped -> Unit
                }
            }
            if (ownsFlow && gen == generation) {
                finishFlow(
                    when {
                        !waiting -> rest
                        done != null -> PurchaseFlow.Done(done.tier)
                        failed != null -> PurchaseFlow.Failed(failed.error, failed.detail)
                        skippedAny -> PurchaseFlow.Failed(BillingError.NotOwned)
                        else -> rest
                    },
                )
            }
            finished = true
        } finally {
            // Cancelled (Try again restarts the refresh): do not leave the card on Checking or
            // Registering, unless a newer restore has taken over.
            if (!finished && ownsFlow && gen == generation && run == restoreRun) finishFlow(PurchaseFlow.Idle)
        }
    }

    /** What the card returns to when a restore had nothing to report. */
    private fun rest(previous: PurchaseFlow): PurchaseFlow = when {
        previous is PurchaseFlow.Pending || previous.busy -> PurchaseFlow.Idle
        else -> previous
    }

    /** Moves the restore's own Checking/Registering on; never overwrites what another path set meanwhile. */
    private fun finishFlow(next: PurchaseFlow) = _state.update {
        if (it.flow is PurchaseFlow.Checking || it.flow is PurchaseFlow.Registering) it.copy(flow = next) else it
    }

    private fun settleRegistering() =
        _state.update { if (it.flow is PurchaseFlow.Registering) it.copy(flow = PurchaseFlow.Idle) else it }

    private fun skip(purchase: PlayPurchase, productId: String) {
        val reason = if (purchase.obfuscatedAccountId == null) "was bought outside the app" else "belongs to another AnkiMCP account"
        log.info("Billing: the $productId subscription on this Google account $reason; skipped")
        synchronized(lock) { skipped.add(purchase.purchaseToken) }
    }

    private fun isSkipped(token: String) = synchronized(lock) { token in skipped }

    private fun isInFlight(token: String) = synchronized(lock) { token in inFlight }

    /** This account's obfuscated id, from `/prepare` once per account. */
    private suspend fun accountId(): BillingApiResult<String> {
        accountIdCache?.let { return BillingApiResult.Ok(it) }
        val gen = generation
        return when (val result = backend.prepare()) {
            is BillingApiResult.Ok -> {
                if (gen == generation) accountIdCache = result.value.obfuscatedAccountId
                BillingApiResult.Ok(result.value.obfuscatedAccountId)
            }
            BillingApiResult.SignedOut -> BillingApiResult.SignedOut
            BillingApiResult.NotOwned -> BillingApiResult.NotOwned
            BillingApiResult.AlreadySubscribed -> BillingApiResult.AlreadySubscribed
            BillingApiResult.BillingDisabled -> BillingApiResult.BillingDisabled
            BillingApiResult.NotAvailable -> BillingApiResult.NotAvailable
            is BillingApiResult.Rejected -> result
            is BillingApiResult.RetryLater -> result
        }
    }

    private sealed interface RegisterOutcome {
        data class Registered(val tier: String) : RegisterOutcome

        /** Already accepted, or being sent by another path right now. */
        data object Skipped : RegisterOutcome

        data class Failed(val error: BillingError, val detail: String?) : RegisterOutcome
    }

    /** POSTs the token once at a time, retrying network errors and 5xx with backoff. */
    private suspend fun register(purchase: PlayPurchase, productId: String): RegisterOutcome {
        val token = purchase.purchaseToken
        val gen = generation
        if (purchase.acknowledged && registered.contains(token)) return RegisterOutcome.Skipped
        if (!synchronized(lock) { inFlight.add(token) }) return RegisterOutcome.Skipped
        try {
            var attempt = 0
            while (true) {
                val result = backend.registerPurchase(token)
                if (gen != generation) return RegisterOutcome.Skipped
                when (result) {
                    is BillingApiResult.Ok -> {
                        registered.add(token)
                        log.info("Billing: $productId purchase registered; plan is ${result.value}")
                        onTier(result.value)
                        loadStatus()
                        return RegisterOutcome.Registered(result.value)
                    }
                    is BillingApiResult.RetryLater -> {
                        if (attempt >= retryDelaysMs.size) {
                            log.warn("Billing: $productId purchase not registered yet; will retry on the next refresh")
                            return RegisterOutcome.Failed(BillingError.Network, result.message)
                        }
                        delay(retryDelaysMs[attempt++])
                    }
                    BillingApiResult.NotOwned, BillingApiResult.AlreadySubscribed -> {
                        synchronized(lock) { refused.add(token) }
                        return RegisterOutcome.Failed(apiError(result), null)
                    }
                    else -> return RegisterOutcome.Failed(apiError(result), apiDetail(result))
                }
            }
        } finally {
            synchronized(lock) { if (gen == generation) inFlight.remove(token) }
        }
    }

    private suspend fun loadStatus() {
        val gen = generation
        val result = statusSource.status()
        if (gen != generation) return
        if (result is BillingApiResult.Ok) onTier(result.value.tier)
        _state.update {
            when (result) {
                is BillingApiResult.Ok -> it.copy(plan = PlanLoad.Loaded(result.value))
                BillingApiResult.NotAvailable -> it.copy(plan = PlanLoad.NotAvailable)
                // Keep a loaded status on a failed refresh; only a first load shows the error.
                else -> if (it.plan is PlanLoad.Loaded) it else it.copy(plan = PlanLoad.Failed)
            }
        }
    }

    private suspend fun loadCatalog() {
        if (_state.value.catalog is Catalog.Loaded) return
        val gen = generation
        _state.update { it.copy(catalog = Catalog.Loading) }
        val result = gateway.products(BillingProducts.ALL)
        if (gen != generation) return
        _state.update {
            it.copy(
                catalog = when (result) {
                    is GatewayResult.Ok -> {
                        val found = result.value.map { product -> product.productId }.toSet()
                        Catalog.Loaded(
                            products = result.value.sortedBy { product -> BillingProducts.rank(product.productId) },
                            missing = BillingProducts.ALL.filterNot { id -> id in found },
                        )
                    }
                    is GatewayResult.Error -> Catalog.Unavailable(result.error)
                },
            )
        }
    }

    private fun fail(error: BillingError, detail: String? = null) = setFlow(PurchaseFlow.Failed(error, detail))

    private fun setFlow(flow: PurchaseFlow) = _state.update { it.copy(flow = flow) }

    private fun track(block: suspend () -> Unit): Job {
        val gen = generation
        val job = scope.launch {
            try {
                if (gen == generation) block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Billing: ${e.javaClass.simpleName}: ${e.message}")
                if (gen == generation) _state.update { if (it.flow.busy) it.copy(flow = PurchaseFlow.Failed(BillingError.Play, e.message)) else it }
            }
        }
        synchronized(lock) {
            jobs.removeAll { !it.isActive }
            jobs += job
        }
        return job
    }

    private fun apiError(result: BillingApiResult<*>): BillingError = when (result) {
        is BillingApiResult.Ok -> BillingError.Server
        BillingApiResult.SignedOut -> BillingError.SignedOut
        BillingApiResult.NotOwned -> BillingError.NotOwned
        BillingApiResult.AlreadySubscribed -> BillingError.AlreadySubscribed
        BillingApiResult.BillingDisabled, BillingApiResult.NotAvailable -> BillingError.BillingDisabled
        is BillingApiResult.Rejected -> BillingError.Server
        is BillingApiResult.RetryLater -> BillingError.Network
    }

    private fun apiDetail(result: BillingApiResult<*>): String? = when (result) {
        is BillingApiResult.Rejected -> result.message
        is BillingApiResult.RetryLater -> result.message
        else -> null
    }

    private fun gatewayError(error: GatewayError): BillingError = when (error) {
        GatewayError.Unavailable -> BillingError.PlayUnavailable
        GatewayError.ItemUnavailable -> BillingError.ProductUnavailable
        else -> BillingError.Play
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 60_000L
        val DEFAULT_RETRY_DELAYS_MS = listOf(2_000L, 4_000L, 8_000L)
    }
}
