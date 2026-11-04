package ai.ankimcp.ankidroid.billing

import ai.ankimcp.ankidroid.billing.BillingProducts.MONTHLY
import ai.ankimcp.ankidroid.billing.BillingProducts.YEARLY
import ai.ankimcp.ankidroid.billing.PlayPhase.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayOffersTest {
    private fun phase(micros: Long, price: String, period: String, cycles: Int, recurrence: Recurrence, currency: String = "USD") =
        PlayPhase(micros, currency, price, period, cycles, recurrence)

    private val yearlyPrice = phase(50_000_000, "$50.00", "P1Y", 0, Recurrence.Infinite)
    private val monthlyPrice = phase(5_000_000, "$5.00", "P1M", 0, Recurrence.Infinite)
    private val baseYearly = PlayOffer(YEARLY, null, emptyList(), "base-y", listOf(yearlyPrice))
    private val baseMonthly = PlayOffer(MONTHLY, null, emptyList(), "base-m", listOf(monthlyPrice))
    private val yearOnce = phase(35_000_000, "$35.00", "P1Y", 1, Recurrence.NonRecurring)

    private fun promo(id: String, vararg phases: PlayPhase, basePlanId: String = YEARLY, tags: List<String> = listOf("promo")) =
        PlayOffer(basePlanId, id, tags, "tok-$id", phases.toList())

    private fun select(vararg offers: PlayOffer, basePlanId: String = YEARLY): Pair<SubscriptionProduct?, List<String>> {
        val ignored = mutableListOf<String>()
        return OfferSelection.select(BillingProducts.PLUS, basePlanId, offers.toList()) { ignored += it } to ignored
    }

    @Test
    fun `base offer only`() {
        val (item, ignored) = select(baseMonthly, baseYearly)
        assertEquals(SubscriptionProduct(BillingProducts.PLUS, YEARLY, "$50.00", "P1Y", "base-y"), item)
        assertEquals("base-y", item!!.launchOfferToken)
        assertEquals(emptyList<String>(), ignored)
    }

    @Test
    fun `no base offer means no item, even with a promo`() {
        assertNull(select(baseMonthly, promo("cyber", yearOnce, yearlyPrice)).first)
    }

    @Test
    fun `a single payment discount then the base price is the promo, and purchases use its token`() {
        val (item, ignored) = select(baseYearly, promo("cyber", yearOnce, yearlyPrice))
        assertEquals(PromoOffer("cyber", "tok-cyber", "$35.00", "P1Y", 1), item!!.promo)
        assertEquals("$50.00", item.formattedPrice)
        assertEquals("tok-cyber", item.launchOfferToken)
        assertEquals(emptyList<String>(), ignored)
    }

    @Test
    fun `three discounted months on monthly`() {
        val discount = phase(3_000_000, "$3.00", "P1M", 3, Recurrence.Finite)
        val item = select(baseMonthly, promo("m3", discount, monthlyPrice, basePlanId = MONTHLY), basePlanId = MONTHLY).first
        assertEquals(PromoOffer("m3", "tok-m3", "$3.00", "P1M", 3), item!!.promo)
    }

    @Test
    fun `a single payment (non-recurring) discount counts as one period, whether Play reports 0 or 1 cycles`() {
        for (cycles in listOf(0, 1)) {
            val discount = phase(35_000_000, "$35.00", "P1Y", cycles, Recurrence.NonRecurring)
            assertEquals(
                PromoOffer("once", "tok-once", "$35.00", "P1Y", 1),
                select(baseYearly, promo("once", discount, yearlyPrice)).first!!.promo,
            )
        }
    }

    @Test
    fun `Finite with 1 cycle is ignored`() {
        val discount = phase(35_000_000, "$35.00", "P1Y", 1, Recurrence.Finite)
        val (item, ignored) = select(baseYearly, promo("one", discount, yearlyPrice))
        assertNull(item!!.promo)
        assertEquals(listOf("one"), ignored)
    }

    @Test
    fun `free trial is ignored and logged`() {
        val trial = phase(0, "Free", "P1W", 1, Recurrence.NonRecurring)
        val (item, ignored) = select(baseYearly, promo("trial", trial, yearlyPrice))
        assertNull(item!!.promo)
        assertEquals(listOf("trial"), ignored)
    }

    @Test
    fun `two discount phases are ignored`() {
        val first = phase(20_000_000, "$20.00", "P1Y", 1, Recurrence.NonRecurring)
        val second = phase(35_000_000, "$35.00", "P1Y", 2, Recurrence.Finite)
        val (item, ignored) = select(baseYearly, promo("two", first, second, yearlyPrice))
        assertNull(item!!.promo)
        assertEquals(listOf("two"), ignored)
    }

    @Test
    fun `a recurring phase that differs from the base offer is ignored`() {
        val otherPrice = phase(60_000_000, "$60.00", "P1Y", 0, Recurrence.Infinite)
        val otherPeriod = phase(50_000_000, "$50.00", "P6M", 0, Recurrence.Infinite)
        val (item, ignored) = select(baseYearly, promo("price", yearOnce, otherPrice), promo("period", yearOnce, otherPeriod))
        assertNull(item!!.promo)
        assertEquals(listOf("price", "period"), ignored)
    }

    @Test
    fun `a discount not below the regular price, or infinite, is ignored`() {
        val same = phase(50_000_000, "$50.00", "P1Y", 1, Recurrence.NonRecurring)
        val forever = phase(35_000_000, "$35.00", "P1Y", 0, Recurrence.Infinite)
        val (item, ignored) = select(baseYearly, promo("same", same, yearlyPrice), promo("forever", forever, yearlyPrice))
        assertNull(item!!.promo)
        assertEquals(listOf("same", "forever"), ignored)
    }

    @Test
    fun `validity compares the price per day - a week at 4,99 on 5 a month is no discount`() {
        val week = phase(4_990_000, "$4.99", "P1W", 1, Recurrence.NonRecurring)
        val (item, ignored) = select(baseMonthly, promo("week", week, monthlyPrice, basePlanId = MONTHLY), basePlanId = MONTHLY)
        assertNull(item!!.promo)
        assertEquals(listOf("week"), ignored)
    }

    @Test
    fun `validity compares the price per day - 12 for three months on 5 a month is a discount`() {
        val quarter = phase(12_000_000, "$12.00", "P3M", 1, Recurrence.NonRecurring)
        val item = select(baseMonthly, promo("q", quarter, monthlyPrice, basePlanId = MONTHLY), basePlanId = MONTHLY).first
        assertEquals(PromoOffer("q", "tok-q", "$12.00", "P3M", 1), item!!.promo)
    }

    @Test
    fun `validity compares the price per day - six months is exactly half a year on a yearly plan`() {
        val half = phase(25_000_000, "$25.00", "P6M", 1, Recurrence.NonRecurring)
        val cheaper = phase(24_990_000, "$24.99", "P6M", 1, Recurrence.NonRecurring)
        val (item, ignored) = select(baseYearly, promo("half", half, yearlyPrice))
        assertNull(item!!.promo)
        assertEquals(listOf("half"), ignored)
        assertEquals(
            PromoOffer("cheaper", "tok-cheaper", "$24.99", "P6M", 1),
            select(baseYearly, promo("cheaper", cheaper, yearlyPrice)).first!!.promo,
        )
    }

    @Test
    fun `an unparseable period is ignored`() {
        val odd = phase(30_000_000, "$30.00", "P1Q", 1, Recurrence.NonRecurring)
        val (item, ignored) = select(baseYearly, promo("odd", odd, yearlyPrice))
        assertNull(item!!.promo)
        assertEquals(listOf("odd"), ignored)
    }

    @Test
    fun `a discount in another currency than the recurring price is ignored`() {
        val euros = phase(35_000_000, "€35.00", "P1Y", 1, Recurrence.NonRecurring, currency = "EUR")
        val (item, ignored) = select(baseYearly, promo("eur", euros, yearlyPrice))
        assertNull(item!!.promo)
        assertEquals(listOf("eur"), ignored)
    }

    @Test
    fun `offers without the promo tag or on another base plan are not considered`() {
        val (item, ignored) = select(
            baseYearly,
            promo("untagged", yearOnce, yearlyPrice, tags = listOf("winback")),
            promo("monthly", phase(3_000_000, "$3.00", "P1M", 1, Recurrence.NonRecurring), monthlyPrice, basePlanId = MONTHLY),
        )
        assertNull(item!!.promo)
        assertEquals(emptyList<String>(), ignored)
    }

    @Test
    fun `the cheapest valid promo wins, Play order on a tie`() {
        val d40 = phase(40_000_000, "$40.00", "P1Y", 1, Recurrence.NonRecurring)
        val d30 = phase(30_000_000, "$30.00", "P1Y", 1, Recurrence.NonRecurring)
        assertEquals(
            "b",
            select(baseYearly, promo("a", d40, yearlyPrice), promo("b", d30, yearlyPrice)).first!!.promo!!.offerId,
        )
        assertEquals(
            "a",
            select(baseYearly, promo("a", d30, yearlyPrice), promo("b", d30, yearlyPrice)).first!!.promo!!.offerId,
        )
    }

    @Test
    fun `cheapest is by price per day, not the raw price`() {
        val week = phase(1_000_000, "$1.00", "P1W", 1, Recurrence.NonRecurring)
        val quarter = phase(12_000_000, "$12.00", "P3M", 1, Recurrence.NonRecurring)
        val months = phase(3_000_000, "$3.00", "P1M", 2, Recurrence.Finite)
        val item = select(
            baseMonthly,
            promo("week", week, monthlyPrice, basePlanId = MONTHLY),
            promo("months", months, monthlyPrice, basePlanId = MONTHLY),
            promo("quarter", quarter, monthlyPrice, basePlanId = MONTHLY),
            basePlanId = MONTHLY,
        ).first
        // $1 a week is ~$0.143 a day, $3 a month ~$0.099, $12 a quarter ~$0.132: the lowest raw price loses.
        assertEquals("months", item!!.promo!!.offerId)
    }

    @Test
    fun `billing periods parse into their parts, zero or other text is null`() {
        assertEquals(BillingPeriod(0, 1, 0, 0), BillingPeriod.parse("P1M"))
        assertEquals(BillingPeriod(1, 6, 0, 0), BillingPeriod.parse("P1Y6M"))
        assertEquals(120L, BillingPeriod.parse("P10D")!!.twelfthsOfDay)
        assertEquals(4380L, BillingPeriod.parse("P1Y")!!.twelfthsOfDay)
        assertEquals(1095L, BillingPeriod.parse("P3M")!!.twelfthsOfDay)
        assertEquals(84L, BillingPeriod.parse("P1W")!!.twelfthsOfDay)
        assertEquals(BillingPeriod.parse("P1Y")!!.twelfthsOfDay, BillingPeriod.parse("P12M")!!.twelfthsOfDay)
        assertNull(BillingPeriod.parse("P"))
        assertNull(BillingPeriod.parse("P0M"))
        assertNull(BillingPeriod.parse("P1Q"))
        assertNull(BillingPeriod.parse("PT1H"))
    }
}
