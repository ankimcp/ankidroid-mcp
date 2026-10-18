package ai.ankimcp.ankidroid.billing

import kotlinx.coroutines.flow.Flow

/**
 * Plain-Kotlin view of Google Play Billing, so [BillingCoordinator] is tested with a fake.
 * [PlayBilling] is the only implementation that touches the Play Billing Library. There is
 * deliberately no acknowledge call: the server acknowledges every purchase (D62).
 */
interface BillingGateway {
    /** Results of `launchBillingFlow` and purchases that change while the app runs (pending -> purchased). */
    val updates: Flow<PurchaseUpdate>

    /** The subscriptions' base-plan offers, in Play's order; products Play does not know are left out. */
    suspend fun products(productIds: List<String>): GatewayResult<List<SubscriptionProduct>>

    /** Active subscription purchases of the Google account on this device. */
    suspend fun purchases(): GatewayResult<List<PlayPurchase>>

    /** Shows Play's purchase sheet; the outcome arrives on [updates]. */
    suspend fun launch(host: PurchaseHost, request: LaunchRequest): GatewayResult<Unit>
}

/** The screen a purchase is started from (the Activity in [PlayBilling]); never kept after the launch. */
interface PurchaseHost

data class SubscriptionProduct(
    val productId: String,
    /** Play's localised recurring price, e.g. "€4.99". */
    val formattedPrice: String,
    /** ISO 8601 period of the recurring phase, e.g. "P1M". */
    val billingPeriod: String,
    val offerToken: String,
)

data class PlayPurchase(
    val productIds: List<String>,
    val purchaseToken: String,
    val state: State,
    val acknowledged: Boolean,
    val obfuscatedAccountId: String?,
) {
    enum class State { Purchased, Pending, Unspecified }

    /** Redacted: a stray log line must never carry the purchase token. */
    override fun toString(): String = "PlayPurchase(products=$productIds, state=$state, acknowledged=$acknowledged)"
}

/** How Play moves an existing subscription to another product (D62). */
enum class ReplacementMode {
    /** Upgrade: immediate, same billing date, the difference for the rest of the period is charged now. */
    ChargeProratedPrice,

    /** Downgrade: the higher plan runs to the end of the period, the cheaper one starts at renewal. */
    Deferred,
}

data class ReplaceRequest(
    val oldPurchaseToken: String,
    val oldProductId: String,
    val mode: ReplacementMode,
) {
    override fun toString(): String = "ReplaceRequest(oldProductId=$oldProductId, mode=$mode)"
}

data class LaunchRequest(
    val product: SubscriptionProduct,
    /** From `/billing/google-play/prepare`; the server refuses the purchase (403) without it. */
    val obfuscatedAccountId: String,
    val replace: ReplaceRequest?,
)

enum class GatewayError {
    /** No Play Store, billing not supported, or the API version is too old. */
    Unavailable,

    /** Service disconnected or unavailable, timeout, network: worth trying again. */
    Transient,
    Canceled,
    AlreadyOwned,
    ItemUnavailable,
    Other,
}

sealed interface GatewayResult<out T> {
    data class Ok<T>(val value: T) : GatewayResult<T>

    data class Error(val error: GatewayError, val message: String) : GatewayResult<Nothing>
}

sealed interface PurchaseUpdate {
    data class Purchases(val purchases: List<PlayPurchase>) : PurchaseUpdate

    data object Canceled : PurchaseUpdate

    data class Failed(val error: GatewayError, val message: String) : PurchaseUpdate
}
