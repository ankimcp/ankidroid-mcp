package ai.ankimcp.ankidroid.billing

/** The Play subscriptions the app sells (D62). Prices come only from Play's ProductDetails. */
object BillingProducts {
    const val PLUS = "ankimcp_plus"
    const val PRO = "ankimcp_pro"

    /** The base plans of both products (P1M and P1Y, auto-renewing); no free trial or other offers. */
    const val MONTHLY = "monthly"
    const val YEARLY = "yearly"

    const val PACKAGE_NAME = "ai.ankimcp.ankidroid"

    val ALL: List<String> = listOf(PLUS, PRO)

    val BASE_PLANS: List<String> = listOf(MONTHLY, YEARLY)

    /** The base plan of the server's `interval`; null (purchases from before yearly existed) is monthly. */
    fun basePlanFor(interval: BillingInterval?): String = when (interval) {
        BillingInterval.Year -> YEARLY
        BillingInterval.Month, null -> MONTHLY
    }

    /**
     * How Play replaces (oldProductId, oldBasePlanId) with (productId, basePlanId), or null when
     * they are the same plan and there is nothing to change (D62). Within one product Play allows
     * only CHARGE_FULL_PRICE and WITHOUT_PRORATION; across products the tier order decides.
     * [withPromo]: the launch uses a promo offer, whose discounted price may not raise the price
     * per time that CHARGE_PRORATED_PRICE needs, so an upgrade charges the full price instead.
     */
    fun replacementMode(
        oldProductId: String,
        oldBasePlanId: String,
        productId: String,
        basePlanId: String,
        withPromo: Boolean = false,
    ): ReplacementMode? =
        when {
            oldProductId == productId && oldBasePlanId == basePlanId -> null
            oldProductId == productId ->
                if (basePlanId == YEARLY) ReplacementMode.ChargeFullPrice else ReplacementMode.WithoutProration
            rank(productId) > rank(oldProductId) ->
                if (withPromo) ReplacementMode.ChargeFullPrice else ReplacementMode.ChargeProratedPrice
            else -> ReplacementMode.Deferred
        }

    fun tierFor(productId: String): String? = when (productId) {
        PLUS -> "plus"
        PRO -> "pro"
        else -> null
    }

    /** Higher is more expensive; decides upgrade vs downgrade for a plan change. */
    fun rank(productId: String): Int = when (productId) {
        PLUS -> 1
        PRO -> 2
        else -> 0
    }

    fun manageUrl(productId: String): String =
        "https://play.google.com/store/account/subscriptions?sku=$productId&package=$PACKAGE_NAME"
}
