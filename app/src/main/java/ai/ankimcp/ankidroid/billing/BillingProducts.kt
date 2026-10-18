package ai.ankimcp.ankidroid.billing

/** The Play subscriptions the app sells (D62). Prices come only from Play's ProductDetails. */
object BillingProducts {
    const val PLUS = "ankimcp_plus"
    const val PRO = "ankimcp_pro"

    /** The one base plan of both products; no free trial or other offers. */
    const val BASE_PLAN = "monthly"

    const val PACKAGE_NAME = "ai.ankimcp.ankidroid"

    val ALL: List<String> = listOf(PLUS, PRO)

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
