package ai.ankimcp.ankidroid.billing

import ai.ankimcp.ankidroid.log.TunnelLog
import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

class ActivityPurchaseHost(val activity: Activity) : PurchaseHost

/**
 * The resumed app screen, weakly held, so a purchase asks for the Activity only when Play's
 * sheet opens (never across `/prepare`). [MainActivity][ai.ankimcp.ankidroid.ui.MainActivity]
 * reports itself on resume and on destroy.
 */
object CurrentActivity {
    @Volatile private var ref: WeakReference<Activity>? = null

    fun resumed(activity: Activity) {
        ref = WeakReference(activity)
    }

    fun destroyed(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }

    /** The screen to open Play from, or null when it is finishing or gone. */
    fun host(): PurchaseHost? =
        ref?.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let(::ActivityPurchaseHost)
}

/**
 * [BillingGateway] over the Play Billing Library (billing-ktx 9.1.0, D62). The client is built
 * on first use, so processes started by a push wake-up never bind to Play. Auto service
 * reconnection is on; the first connection is retried twice for transient codes.
 */
class PlayBilling(
    context: Context,
    private val log: TunnelLog,
) : BillingGateway {
    private val appContext = context.applicationContext
    private val _updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = UPDATE_BUFFER)
    override val updates: Flow<PurchaseUpdate> = _updates.asSharedFlow()

    private val details = ConcurrentHashMap<String, ProductDetails>()
    private val connectLock = Mutex()

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(appContext)
            .setListener { result, purchases ->
                val update = when (result.responseCode) {
                    BillingResponseCode.OK -> PurchaseUpdate.Purchases(purchases.orEmpty().map(::toPlayPurchase))
                    BillingResponseCode.USER_CANCELED -> PurchaseUpdate.Canceled
                    else -> PurchaseUpdate.Failed(errorFor(result.responseCode), describe(result))
                }
                if (!_updates.tryEmit(update)) log.warn("Billing: purchase update dropped")
            }
            // Required by the library (one-time products only; subscriptions can be pending regardless).
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    override suspend fun products(productIds: List<String>): GatewayResult<List<SubscriptionProduct>> {
        connect()?.let { return it }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()
        val result = withContext(Dispatchers.IO) { client.queryProductDetails(params) }
        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            return GatewayResult.Error(errorFor(result.billingResult.responseCode), describe(result.billingResult))
        }
        val products = result.productDetailsList.orEmpty().mapNotNull { productDetails ->
            val product = toSubscriptionProduct(productDetails)
            if (product == null) {
                log.warn("Billing: ${productDetails.productId} has no '${BillingProducts.BASE_PLAN}' base plan offer")
            } else {
                details[product.productId] = productDetails
            }
            product
        }
        return GatewayResult.Ok(products)
    }

    override suspend fun purchases(): GatewayResult<List<PlayPurchase>> {
        connect()?.let { return it }
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        val result = withContext(Dispatchers.IO) { client.queryPurchasesAsync(params) }
        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            return GatewayResult.Error(errorFor(result.billingResult.responseCode), describe(result.billingResult))
        }
        return GatewayResult.Ok(result.purchasesList.map(::toPlayPurchase))
    }

    override suspend fun launch(host: PurchaseHost, request: LaunchRequest): GatewayResult<Unit> {
        val activity = (host as? ActivityPurchaseHost)?.activity
            ?: return GatewayResult.Error(GatewayError.Other, "No activity to show Google Play")
        connect()?.let { return it }
        val productDetails = details[request.product.productId]
            ?: return GatewayResult.Error(GatewayError.ItemUnavailable, "Product details not loaded")
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(request.product.offerToken)
        request.replace?.let { replace ->
            productParams.setSubscriptionProductReplacementParams(
                SubscriptionProductReplacementParams.newBuilder()
                    .setOldProductId(replace.oldProductId)
                    .setReplacementMode(
                        when (replace.mode) {
                            ReplacementMode.ChargeProratedPrice -> SubscriptionProductReplacementParams.ReplacementMode.CHARGE_PRORATED_PRICE
                            ReplacementMode.Deferred -> SubscriptionProductReplacementParams.ReplacementMode.DEFERRED
                        },
                    )
                    .build(),
            )
        }
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams.build()))
            .setObfuscatedAccountId(request.obfuscatedAccountId)
        request.replace?.let { replace ->
            flowParams.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(replace.oldPurchaseToken)
                    .build(),
            )
        }
        // launchBillingFlow must run on the main thread. The screen is checked there, right before
        // the launch: connect() may have waited for seconds.
        val result = withContext(Dispatchers.Main) {
            if (activity.isFinishing || activity.isDestroyed) null else client.launchBillingFlow(activity, flowParams.build())
        } ?: return GatewayResult.Error(GatewayError.Other, "The screen closed before Google Play could open")
        return if (result.responseCode == BillingResponseCode.OK) {
            GatewayResult.Ok(Unit)
        } else {
            GatewayResult.Error(errorFor(result.responseCode), describe(result))
        }
    }

    /** Null once connected, the error otherwise. */
    private suspend fun connect(): GatewayResult.Error? {
        connectLock.withLock {
            var attempt = 0
            while (true) {
                if (client.isReady) return null
                val result = startConnection()
                if (result.responseCode == BillingResponseCode.OK) return null
                val error = errorFor(result.responseCode)
                if (error != GatewayError.Transient || attempt >= CONNECT_RETRY_DELAYS_MS.size) {
                    log.warn("Billing: Google Play not reachable: ${describe(result)}")
                    return GatewayResult.Error(error, describe(result))
                }
                delay(CONNECT_RETRY_DELAYS_MS[attempt++])
            }
        }
    }

    private suspend fun startConnection(): BillingResult {
        val done = CompletableDeferred<BillingResult>()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                done.complete(billingResult)
            }

            override fun onBillingServiceDisconnected() {
                // Auto service reconnection handles later calls; this only ends a pending setup.
                done.complete(
                    BillingResult.newBuilder().setResponseCode(BillingResponseCode.SERVICE_DISCONNECTED).build(),
                )
            }
        })
        return done.await()
    }

    private fun toSubscriptionProduct(productDetails: ProductDetails): SubscriptionProduct? {
        // Only the `monthly` base plan's own offer (no offerId); no trial or intro offer is ever
        // picked instead (D62). Without it the product is shown as unavailable.
        val offer = productDetails.subscriptionOfferDetails.orEmpty()
            .firstOrNull { it.basePlanId == BillingProducts.BASE_PLAN && it.offerId == null }
            ?: return null
        val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return null
        return SubscriptionProduct(
            productId = productDetails.productId,
            formattedPrice = phase.formattedPrice,
            billingPeriod = phase.billingPeriod,
            offerToken = offer.offerToken,
        )
    }

    private fun toPlayPurchase(purchase: Purchase) = PlayPurchase(
        productIds = purchase.products,
        purchaseToken = purchase.purchaseToken,
        state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PlayPurchase.State.Purchased
            Purchase.PurchaseState.PENDING -> PlayPurchase.State.Pending
            else -> PlayPurchase.State.Unspecified
        },
        acknowledged = purchase.isAcknowledged,
        obfuscatedAccountId = purchase.accountIdentifiers?.obfuscatedAccountId,
    )

    private fun describe(result: BillingResult): String {
        val debug = result.debugMessage.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
        return "Google Play code ${result.responseCode}$debug"
    }

    private companion object {
        const val UPDATE_BUFFER = 16
        val CONNECT_RETRY_DELAYS_MS = longArrayOf(1_000, 3_000)

        fun errorFor(code: Int): GatewayError = when (code) {
            BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.FEATURE_NOT_SUPPORTED -> GatewayError.Unavailable
            BillingResponseCode.SERVICE_DISCONNECTED, BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.NETWORK_ERROR, BillingResponseCode.ERROR,
            -> GatewayError.Transient
            BillingResponseCode.USER_CANCELED -> GatewayError.Canceled
            BillingResponseCode.ITEM_ALREADY_OWNED -> GatewayError.AlreadyOwned
            BillingResponseCode.ITEM_UNAVAILABLE -> GatewayError.ItemUnavailable
            else -> GatewayError.Other
        }
    }
}
