package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.billing.BillingInterval
import ai.ankimcp.ankidroid.billing.BillingProducts
import ai.ankimcp.ankidroid.billing.BillingProducts.MONTHLY
import ai.ankimcp.ankidroid.billing.BillingProducts.YEARLY
import ai.ankimcp.ankidroid.billing.BillingState
import ai.ankimcp.ankidroid.billing.Catalog
import ai.ankimcp.ankidroid.billing.GatewayError
import ai.ankimcp.ankidroid.billing.PlanLoad
import ai.ankimcp.ankidroid.billing.PlanProvider
import ai.ankimcp.ankidroid.billing.PlanStatus
import ai.ankimcp.ankidroid.billing.PlanStatusKind
import ai.ankimcp.ankidroid.billing.PromoOffer
import ai.ankimcp.ankidroid.billing.PurchaseFlow
import ai.ankimcp.ankidroid.billing.SubscriptionProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PlanCardTest {
    private val end = Instant.parse("2026-11-18T10:00:00Z")
    private val plusMonthly = SubscriptionProduct(BillingProducts.PLUS, MONTHLY, "€4.99", "P1M", "o1")
    private val proMonthly = SubscriptionProduct(BillingProducts.PRO, MONTHLY, "€9.99", "P1M", "o2")
    private val plusYearly = SubscriptionProduct(BillingProducts.PLUS, YEARLY, "€49.99", "P1Y", "o3")
    private val proYearly = SubscriptionProduct(BillingProducts.PRO, YEARLY, "€99.99", "P1Y", "o4")
    private val products = Catalog.Loaded(listOf(plusMonthly, proMonthly))
    private val withYearly = Catalog.Loaded(listOf(plusMonthly, plusYearly, proMonthly, proYearly))
    private val free = PlanStatus("free", PlanStatusKind.Free, PlanProvider.None, null, false, null)

    private fun state(status: PlanStatus?, catalog: Catalog = products, flow: PurchaseFlow = PurchaseFlow.Idle) = BillingState(
        catalog = catalog,
        plan = status?.let { PlanLoad.Loaded(it) } ?: PlanLoad.Failed,
        flow = flow,
    )

    private fun play(
        status: PlanStatusKind,
        cancel: Boolean = false,
        productId: String? = BillingProducts.PLUS,
        ends: Instant? = end,
        interval: BillingInterval? = null,
    ) = PlanStatus("plus", status, PlanProvider.GooglePlay, ends, cancel, productId, interval)

    @Test
    fun `loading and failed status`() {
        assertEquals(PlanKind.Loading, planCardUi(BillingState(), "free").kind)
        val failed = planCardUi(state(null), "plus")
        assertEquals(PlanKind.LoadFailed, failed.kind)
        assertEquals("plus", failed.tier)
        assertTrue(failed.products.isEmpty())
    }

    @Test
    fun `a relay without billing shows only the quiet note`() {
        val ui = planCardUi(BillingState(catalog = products, plan = PlanLoad.NotAvailable), "free")
        assertEquals(PlanKind.NotAvailable, ui.kind)
        assertTrue(ui.products.isEmpty())
        assertNull(ui.catalogNote)
        assertNull(ui.manageProductId)
    }

    @Test
    fun `free offers both products with Play's prices`() {
        val ui = planCardUi(state(free), "free")
        assertEquals(PlanKind.Free, ui.kind)
        assertEquals(
            listOf(
                PlanProductUi(BillingProducts.PLUS, MONTHLY, "plus", "€4.99", PricePeriod.Month, PlanAction.Subscribe, true),
                PlanProductUi(BillingProducts.PRO, MONTHLY, "pro", "€9.99", PricePeriod.Month, PlanAction.Subscribe, true),
            ),
            ui.products,
        )
        assertNull(ui.manageProductId)
        assertNull(ui.renewal)
    }

    @Test
    fun `google play active renews, offers a switch and the manage link`() {
        val ui = planCardUi(state(play(PlanStatusKind.Active)), "free")
        assertEquals(PlanKind.GooglePlay, ui.kind)
        assertEquals("plus", ui.tier)
        assertEquals(RenewalNote.Renews, ui.renewal)
        assertEquals(end, ui.periodEndsAt)
        assertEquals(BillingProducts.PLUS, ui.manageProductId)
        assertEquals(listOf(PlanAction.Current, PlanAction.SwitchTo), ui.products.map { it.action })
        assertEquals(listOf(false, true), ui.products.map { it.enabled })
        assertEquals(
            "https://play.google.com/store/account/subscriptions?sku=ankimcp_plus&package=ai.ankimcp.ankidroid",
            BillingProducts.manageUrl(ui.manageProductId!!),
        )
    }

    @Test
    fun `google play canceled or cancel at period end ends`() {
        assertEquals(RenewalNote.Ends, planCardUi(state(play(PlanStatusKind.Canceled, cancel = true)), "plus").renewal)
        assertEquals(RenewalNote.Ends, planCardUi(state(play(PlanStatusKind.Active, cancel = true)), "plus").renewal)
    }

    @Test
    fun `grace wins over canceled and needs no date`() {
        assertEquals(RenewalNote.PaymentProblem, planCardUi(state(play(PlanStatusKind.Grace, cancel = true, ends = null)), "plus").renewal)
    }

    @Test
    fun `no date means no renewal line`() {
        assertNull(planCardUi(state(play(PlanStatusKind.Active, ends = null)), "plus").renewal)
    }

    @Test
    fun `google play without a product id has no buttons and no manage link`() {
        val ui = planCardUi(state(play(PlanStatusKind.Active, productId = null)), "plus")
        assertTrue(ui.products.isEmpty())
        assertNull(ui.manageProductId)
        assertNull(ui.catalogNote)
    }

    @Test
    fun `admin grant has no buttons`() {
        val ui = planCardUi(state(PlanStatus("pro", PlanStatusKind.Active, PlanProvider.Admin, null, false, null)), "free")
        assertEquals(PlanKind.Granted, ui.kind)
        assertEquals("pro", ui.tier)
        assertTrue(ui.products.isEmpty())
        assertNull(ui.manageProductId)
    }

    @Test
    fun `polar paddle and unknown providers are managed on the web`() {
        for (provider in listOf(PlanProvider.Polar, PlanProvider.Paddle, PlanProvider.Unknown)) {
            val ui = planCardUi(state(PlanStatus("plus", PlanStatusKind.Active, provider, end, false, null)), "free")
            assertEquals(PlanKind.ManagedOnWeb, ui.kind)
            assertTrue(ui.products.isEmpty())
            assertNull(ui.manageProductId)
            assertNull(ui.renewal)
        }
    }

    @Test
    fun `a running purchase disables the buttons`() {
        val ui = planCardUi(state(free, catalog = withYearly, flow = PurchaseFlow.Launching(BillingProducts.PLUS)), "free")
        assertTrue(ui.products.none { it.enabled })
    }

    @Test
    fun `pending disables the buttons`() {
        val ui = planCardUi(state(free, flow = PurchaseFlow.Pending(BillingProducts.PLUS)), "free")
        assertTrue(ui.products.none { it.enabled })
    }

    @Test
    fun `a background restore (registering) disables the buttons`() {
        val ui = planCardUi(state(free, flow = PurchaseFlow.Registering(BillingProducts.PLUS)), "free")
        assertTrue(ui.products.none { it.enabled })
    }

    @Test
    fun `a product without the monthly base plan is shown unavailable, without price or button`() {
        val catalog = Catalog.Loaded(listOf(proMonthly))
        val ui = planCardUi(state(free, catalog = catalog), "free")
        assertEquals(
            PlanProductUi(BillingProducts.PLUS, MONTHLY, "plus", null, null, PlanAction.Unavailable, false),
            ui.products.first(),
        )
        assertTrue(ui.products.first().notInPlay)
        assertEquals(PlanAction.Subscribe, ui.products.last().action)
        assertFalse(ui.products.last().notInPlay)
    }

    @Test
    fun `nothing available at all is the empty note, not two unavailable rows`() {
        val ui = planCardUi(state(free, catalog = Catalog.Loaded(emptyList())), "free")
        assertTrue(ui.products.isEmpty())
        assertEquals(CatalogNote.Empty, ui.catalogNote)
    }

    @Test
    fun `catalog notes when products cannot be shown`() {
        assertEquals(CatalogNote.Loading, planCardUi(state(free, catalog = Catalog.Loading), "free").catalogNote)
        assertEquals(
            CatalogNote.Unavailable,
            planCardUi(state(free, catalog = Catalog.Unavailable(GatewayError.Unavailable)), "free").catalogNote,
        )
        assertEquals(CatalogNote.Empty, planCardUi(state(free, catalog = Catalog.Loaded(emptyList())), "free").catalogNote)
        // Nothing to sell: no note either.
        val admin = PlanStatus("pro", PlanStatusKind.Active, PlanProvider.Admin, null, false, null)
        assertNull(planCardUi(state(admin, catalog = Catalog.Loading), "free").catalogNote)
    }

    @Test
    fun `price periods - month, year, anything else bare`() {
        val odd = SubscriptionProduct(BillingProducts.PRO, MONTHLY, "€25", "P3M", "o")
        val ui = planCardUi(state(free, catalog = Catalog.Loaded(listOf(plusMonthly, odd, plusYearly))), "free")
        assertEquals(listOf(PricePeriod.Month, null), ui.productsFor(MONTHLY).map { it.period })
        assertEquals(PricePeriod.Year, ui.productsFor(YEARLY).first().period)
    }

    @Test
    fun `without any yearly offer there is no switch and only monthly rows`() {
        val ui = planCardUi(state(free), "free")
        assertFalse(ui.intervalSwitch)
        assertTrue(ui.products.all { it.basePlanId == MONTHLY })
        assertEquals(MONTHLY, ui.selectedBasePlan(YEARLY))
    }

    @Test
    fun `yearly offers show the switch, default monthly when free`() {
        val ui = planCardUi(state(free, catalog = withYearly), "free")
        assertTrue(ui.intervalSwitch)
        assertEquals(MONTHLY, ui.defaultBasePlan)
        assertEquals(MONTHLY, ui.selectedBasePlan(null))
        assertEquals(YEARLY, ui.selectedBasePlan(YEARLY))
        assertEquals(
            listOf(
                PlanProductUi(BillingProducts.PLUS, YEARLY, "plus", "€49.99", PricePeriod.Year, PlanAction.Subscribe, true),
                PlanProductUi(BillingProducts.PRO, YEARLY, "pro", "€99.99", PricePeriod.Year, PlanAction.Subscribe, true),
            ),
            ui.productsFor(YEARLY),
        )
    }

    @Test
    fun `the switch defaults to the current play plan's interval`() {
        val yearly = planCardUi(state(play(PlanStatusKind.Active, interval = BillingInterval.Year), catalog = withYearly), "plus")
        assertEquals(YEARLY, yearly.defaultBasePlan)
        val monthly = planCardUi(state(play(PlanStatusKind.Active, interval = BillingInterval.Month), catalog = withYearly), "plus")
        assertEquals(MONTHLY, monthly.defaultBasePlan)
    }

    @Test
    fun `a play plan without an interval is monthly`() {
        val ui = planCardUi(state(play(PlanStatusKind.Active, interval = null), catalog = withYearly), "plus")
        assertEquals(MONTHLY, ui.defaultBasePlan)
        assertEquals(listOf(PlanAction.Current, PlanAction.SwitchTo), ui.productsFor(MONTHLY).map { it.action })
        assertEquals(listOf(PlanAction.SwitchTo, PlanAction.SwitchTo), ui.productsFor(YEARLY).map { it.action })
    }

    @Test
    fun `current is product and base plan - the same product on the other base plan is a change`() {
        val ui = planCardUi(state(play(PlanStatusKind.Active, interval = BillingInterval.Year), catalog = withYearly), "plus")
        assertEquals(listOf(PlanAction.SwitchTo, PlanAction.SwitchTo), ui.productsFor(MONTHLY).map { it.action })
        assertEquals(listOf(true, true), ui.productsFor(MONTHLY).map { it.enabled })
        assertEquals(listOf(PlanAction.Current, PlanAction.SwitchTo), ui.productsFor(YEARLY).map { it.action })
        assertEquals(listOf(false, true), ui.productsFor(YEARLY).map { it.enabled })
    }

    @Test
    fun `a product without a yearly offer is unavailable only on yearly`() {
        val catalog = Catalog.Loaded(listOf(plusMonthly, proMonthly, proYearly))
        val ui = planCardUi(state(free, catalog = catalog), "free")
        assertTrue(ui.intervalSwitch)
        assertEquals(listOf(PlanAction.Subscribe, PlanAction.Subscribe), ui.productsFor(MONTHLY).map { it.action })
        assertEquals(
            PlanProductUi(BillingProducts.PLUS, YEARLY, "plus", null, null, PlanAction.Unavailable, false),
            ui.productsFor(YEARLY).first(),
        )
        assertEquals(PlanAction.Subscribe, ui.productsFor(YEARLY).last().action)
    }

    @Test
    fun `promo rows carry the discounted price`() {
        val promo = PromoOffer("cyber", "promo-token", "€34.99", "P1Y", 1)
        val catalog = Catalog.Loaded(listOf(plusMonthly, plusYearly.copy(promo = promo), proMonthly, proYearly))
        val ui = planCardUi(state(free, catalog = catalog), "free")
        assertEquals(PromoUi("€34.99", PromoLength.First(1, PeriodUnit.Year)), ui.productsFor(YEARLY).first().promo)
        assertEquals("€49.99", ui.productsFor(YEARLY).first().price)
        assertNull(ui.productsFor(YEARLY).last().promo)
    }

    @Test
    fun `promo length wording - first year, months per month, fallback`() {
        fun length(period: String, cycles: Int) = promoUi(PromoOffer("o", "t", "€1", period, cycles)).length
        assertEquals(PromoLength.First(1, PeriodUnit.Year), length("P1Y", 1))
        assertEquals(PromoLength.First(1, PeriodUnit.Month), length("P1M", 1))
        assertEquals(PromoLength.First(3, PeriodUnit.Month), length("P3M", 1))
        assertEquals(PromoLength.First(10, PeriodUnit.Day), length("P10D", 1))
        assertEquals(PromoLength.PerUnit(3, PeriodUnit.Month), length("P1M", 3))
        assertEquals(PromoLength.PerUnit(2, PeriodUnit.Week), length("P1W", 2))
    }

    @Test
    fun `promo fallback keeps the cycle count and reads the period when it parses`() {
        fun length(period: String, cycles: Int) = promoUi(PromoOffer("o", "t", "€1", period, cycles)).length
        assertEquals(PromoLength.Other(listOf(PeriodPart(3, PeriodUnit.Month)), "P3M", 2), length("P3M", 2))
        assertEquals(
            PromoLength.Other(listOf(PeriodPart(1, PeriodUnit.Year), PeriodPart(6, PeriodUnit.Month)), "P1Y6M", 1),
            length("P1Y6M", 1),
        )
        assertEquals(PromoLength.Other(null, "P1Q", 1), length("P1Q", 1))
        assertEquals(PromoLength.Other(null, "P1Q", 3), length("P1Q", 3))
    }

    @Test
    fun `the current plan's row shows the regular price, never the promo`() {
        val promo = PromoOffer("cyber", "promo-token", "€34.99", "P1Y", 1)
        val catalog = Catalog.Loaded(listOf(plusMonthly, plusYearly.copy(promo = promo), proMonthly, proYearly.copy(promo = promo)))
        val ui = planCardUi(state(play(PlanStatusKind.Active, interval = BillingInterval.Year), catalog = catalog), "plus")
        val (current, other) = ui.productsFor(YEARLY)
        assertEquals(PlanAction.Current, current.action)
        assertEquals("€49.99", current.price)
        assertNull(current.promo)
        assertEquals(PlanAction.SwitchTo, other.action)
        assertEquals(PromoUi("€34.99", PromoLength.First(1, PeriodUnit.Year)), other.promo)
    }

    @Test
    fun `a yearly subscriber without any yearly offer keeps the switch and the Current row`() {
        val ui = planCardUi(state(play(PlanStatusKind.Active, interval = BillingInterval.Year), catalog = products), "plus")
        assertTrue(ui.intervalSwitch)
        assertEquals(YEARLY, ui.defaultBasePlan)
        assertEquals(
            listOf(
                PlanProductUi(BillingProducts.PLUS, YEARLY, "plus", null, null, PlanAction.Current, false),
                PlanProductUi(BillingProducts.PRO, YEARLY, "pro", null, null, PlanAction.Unavailable, false),
            ),
            ui.productsFor(YEARLY),
        )
        assertEquals(listOf(false, true), ui.productsFor(YEARLY).map { it.notInPlay })
        assertEquals(listOf(PlanAction.SwitchTo, PlanAction.SwitchTo), ui.productsFor(MONTHLY).map { it.action })
    }

    @Test
    fun `a monthly subscriber without any yearly offer has no switch`() {
        val ui = planCardUi(state(play(PlanStatusKind.Active, interval = BillingInterval.Month), catalog = products), "plus")
        assertFalse(ui.intervalSwitch)
        assertEquals(listOf(PlanAction.Current, PlanAction.SwitchTo), ui.products.map { it.action })
    }
}
