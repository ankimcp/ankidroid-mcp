package ai.ankimcp.ankidroid.billing

/** Plain-Kotlin copy of one Play subscription offer, so offer selection is tested on the JVM (D62). */
data class PlayOffer(
    val basePlanId: String,
    /** Null for the base plan's own offer. */
    val offerId: String?,
    val tags: List<String>,
    val offerToken: String,
    val phases: List<PlayPhase>,
)

data class PlayPhase(
    val priceMicros: Long,
    /** ISO 4217, e.g. "USD". */
    val priceCurrencyCode: String,
    val formattedPrice: String,
    val billingPeriod: String,
    val billingCycleCount: Int,
    val recurrence: Recurrence,
) {
    enum class Recurrence { Infinite, Finite, NonRecurring, Unknown }
}

/** An ISO 8601 billing period such as P1M, P3M, P1Y, P1W, P10D or P1Y6M. */
data class BillingPeriod(val years: Int, val months: Int, val weeks: Int, val days: Int) {
    /**
     * Only for comparing prices, in twelfths of a day so a month is exactly a twelfth of a year:
     * a year is 365 days (4380), a month 365/12 days (365), a week 7 (84), a day 12.
     */
    val twelfthsOfDay: Long get() = years * 4380L + months * 365L + weeks * 84L + days * 12L

    companion object {
        private val ISO = Regex("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?")

        /** Null when [iso] is not such a period or is zero long. */
        fun parse(iso: String): BillingPeriod? {
            val match = ISO.matchEntire(iso) ?: return null
            val parts = match.groupValues.drop(1).map { if (it.isEmpty()) 0 else it.toIntOrNull() ?: return null }
            return BillingPeriod(parts[0], parts[1], parts[2], parts[3]).takeIf { it.twelfthsOfDay > 0 }
        }
    }
}

/**
 * Which offers of a product's base plan the app sells (D62): always the base plan's own offer,
 * plus a promotional offer tagged [PROMO_TAG] when it has exactly one paid discounted phase
 * followed by the base plan's recurring price and period. Play returns developer-determined
 * offers to everyone, so a `promo` offer is shown to everyone who sees the plan; the owner
 * exposes a campaign by activating it in Play Console.
 */
object OfferSelection {
    const val PROMO_TAG = "promo"

    /**
     * The item for ([productId], [basePlanId]), or null without the base offer. [ignored] gets
     * the id of every `promo` offer whose shape is not supported.
     */
    fun select(
        productId: String,
        basePlanId: String,
        offers: List<PlayOffer>,
        ignored: (offerId: String) -> Unit,
    ): SubscriptionProduct? {
        val base = offers.firstOrNull { it.basePlanId == basePlanId && it.offerId == null } ?: return null
        val recurring = base.phases.lastOrNull() ?: return null
        // minByOrNull keeps the first of equal prices: Play's order on a tie.
        val promo = offers
            .filter { it.basePlanId == basePlanId && it.offerId != null && PROMO_TAG in it.tags }
            .mapNotNull { offer ->
                val promo = promoOf(offer, recurring)
                if (promo == null) ignored(offer.offerId!!)
                promo
            }
            .minByOrNull { (_, perDay) -> perDay }
            ?.first
        return SubscriptionProduct(
            productId = productId,
            basePlanId = basePlanId,
            formattedPrice = recurring.formattedPrice,
            billingPeriod = recurring.billingPeriod,
            offerToken = base.offerToken,
            promo = promo,
        )
    }

    /** The promo and its discounted price per day, or null when the shape is not supported. */
    private fun promoOf(offer: PlayOffer, recurring: PlayPhase): Pair<PromoOffer, Double>? {
        val (discount, then) = offer.phases.takeIf { it.size == 2 } ?: return null
        // Play Console cannot make a recurring discount of one period; that is a single payment.
        val cycles = when (discount.recurrence) {
            PlayPhase.Recurrence.Finite -> discount.billingCycleCount.takeIf { it >= 2 } ?: return null
            PlayPhase.Recurrence.NonRecurring -> 1
            PlayPhase.Recurrence.Infinite, PlayPhase.Recurrence.Unknown -> return null
        }
        if (discount.priceMicros <= 0 || discount.priceCurrencyCode != recurring.priceCurrencyCode) return null
        if (then.recurrence != PlayPhase.Recurrence.Infinite ||
            then.priceMicros != recurring.priceMicros ||
            then.priceCurrencyCode != recurring.priceCurrencyCode ||
            then.billingPeriod != recurring.billingPeriod
        ) {
            return null
        }
        val discountPerDay = perDay(discount) ?: return null
        val regularPerDay = perDay(recurring) ?: return null
        if (discountPerDay >= regularPerDay) return null
        val promo = PromoOffer(
            offerId = offer.offerId ?: return null,
            offerToken = offer.offerToken,
            formattedPrice = discount.formattedPrice,
            billingPeriod = discount.billingPeriod,
            billingCycleCount = cycles,
        )
        return promo to discountPerDay
    }

    /** Price per twelfth of a day of one billing period; the same over all cycles of a phase. */
    private fun perDay(phase: PlayPhase): Double? =
        BillingPeriod.parse(phase.billingPeriod)?.let { phase.priceMicros.toDouble() / it.twelfthsOfDay }
}
