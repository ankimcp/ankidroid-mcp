package ai.ankimcp.ankidroid.billing

import ai.ankimcp.ankidroid.billing.BillingProducts.MONTHLY
import ai.ankimcp.ankidroid.billing.BillingProducts.PLUS
import ai.ankimcp.ankidroid.billing.BillingProducts.PRO
import ai.ankimcp.ankidroid.billing.BillingProducts.YEARLY
import org.junit.Assert.assertEquals
import org.junit.Test

class BillingProductsTest {
    @Test
    fun `replacement mode for every plan change`() {
        val expected = mapOf(
            // Same product: only CHARGE_FULL_PRICE and WITHOUT_PRORATION are allowed by Play.
            Pair(PLUS to MONTHLY, PLUS to YEARLY) to ReplacementMode.ChargeFullPrice,
            Pair(PRO to MONTHLY, PRO to YEARLY) to ReplacementMode.ChargeFullPrice,
            Pair(PLUS to YEARLY, PLUS to MONTHLY) to ReplacementMode.WithoutProration,
            Pair(PRO to YEARLY, PRO to MONTHLY) to ReplacementMode.WithoutProration,
            // Plus to Pro, any intervals.
            Pair(PLUS to MONTHLY, PRO to MONTHLY) to ReplacementMode.ChargeProratedPrice,
            Pair(PLUS to MONTHLY, PRO to YEARLY) to ReplacementMode.ChargeProratedPrice,
            Pair(PLUS to YEARLY, PRO to MONTHLY) to ReplacementMode.ChargeProratedPrice,
            Pair(PLUS to YEARLY, PRO to YEARLY) to ReplacementMode.ChargeProratedPrice,
            // Pro to Plus, any intervals.
            Pair(PRO to MONTHLY, PLUS to MONTHLY) to ReplacementMode.Deferred,
            Pair(PRO to MONTHLY, PLUS to YEARLY) to ReplacementMode.Deferred,
            Pair(PRO to YEARLY, PLUS to MONTHLY) to ReplacementMode.Deferred,
            Pair(PRO to YEARLY, PLUS to YEARLY) to ReplacementMode.Deferred,
        )
        for ((change, mode) in expected) {
            val (old, new) = change
            assertEquals("$old -> $new", mode, BillingProducts.replacementMode(old.first, old.second, new.first, new.second))
        }
    }

    @Test
    fun `an upgrade with a promo charges the full price, other changes are as without`() {
        for (old in listOf(MONTHLY, YEARLY)) {
            for (new in listOf(MONTHLY, YEARLY)) {
                assertEquals(ReplacementMode.ChargeFullPrice, BillingProducts.replacementMode(PLUS, old, PRO, new, withPromo = true))
                assertEquals(ReplacementMode.Deferred, BillingProducts.replacementMode(PRO, old, PLUS, new, withPromo = true))
            }
        }
        assertEquals(ReplacementMode.ChargeFullPrice, BillingProducts.replacementMode(PLUS, MONTHLY, PLUS, YEARLY, withPromo = true))
        assertEquals(ReplacementMode.WithoutProration, BillingProducts.replacementMode(PRO, YEARLY, PRO, MONTHLY, withPromo = true))
        assertEquals(null, BillingProducts.replacementMode(PLUS, YEARLY, PLUS, YEARLY, withPromo = true))
    }

    @Test
    fun `the same plan is no change`() {
        for (product in BillingProducts.ALL) {
            for (plan in BillingProducts.BASE_PLANS) {
                assertEquals(null, BillingProducts.replacementMode(product, plan, product, plan))
            }
        }
    }

    @Test
    fun `interval to base plan, null is monthly`() {
        assertEquals(MONTHLY, BillingProducts.basePlanFor(BillingInterval.Month))
        assertEquals(YEARLY, BillingProducts.basePlanFor(BillingInterval.Year))
        assertEquals(MONTHLY, BillingProducts.basePlanFor(null))
    }
}
