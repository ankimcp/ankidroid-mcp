package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.billing.BillingProducts
import ai.ankimcp.ankidroid.billing.BillingState
import ai.ankimcp.ankidroid.billing.Catalog
import ai.ankimcp.ankidroid.billing.GatewayError
import ai.ankimcp.ankidroid.billing.PlanLoad
import ai.ankimcp.ankidroid.billing.PlanProvider
import ai.ankimcp.ankidroid.billing.PlanStatus
import ai.ankimcp.ankidroid.billing.PlanStatusKind
import ai.ankimcp.ankidroid.billing.PurchaseFlow
import ai.ankimcp.ankidroid.billing.SubscriptionProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PlanCardTest {
    private val end = Instant.parse("2026-11-18T10:00:00Z")
    private val products = Catalog.Loaded(
        listOf(
            SubscriptionProduct(BillingProducts.PLUS, "€4.99", "P1M", "o1"),
            SubscriptionProduct(BillingProducts.PRO, "€9.99", "P1M", "o2"),
        ),
    )
    private val free = PlanStatus("free", PlanStatusKind.Free, PlanProvider.None, null, false, null)

    private fun state(status: PlanStatus?, catalog: Catalog = products, flow: PurchaseFlow = PurchaseFlow.Idle) = BillingState(
        catalog = catalog,
        plan = status?.let { PlanLoad.Loaded(it) } ?: PlanLoad.Failed,
        flow = flow,
    )

    private fun play(status: PlanStatusKind, cancel: Boolean = false, productId: String? = BillingProducts.PLUS, ends: Instant? = end) =
        PlanStatus("plus", status, PlanProvider.GooglePlay, ends, cancel, productId)

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
                PlanProductUi(BillingProducts.PLUS, "plus", "€4.99", true, PlanAction.Subscribe, true),
                PlanProductUi(BillingProducts.PRO, "pro", "€9.99", true, PlanAction.Subscribe, true),
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
        val ui = planCardUi(state(free, flow = PurchaseFlow.Launching(BillingProducts.PLUS)), "free")
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
        val catalog = Catalog.Loaded(
            listOf(SubscriptionProduct(BillingProducts.PRO, "€9.99", "P1M", "o2")),
            missing = listOf(BillingProducts.PLUS),
        )
        val ui = planCardUi(state(free, catalog = catalog), "free")
        assertEquals(
            PlanProductUi(BillingProducts.PLUS, "plus", null, false, PlanAction.Unavailable, false),
            ui.products.first(),
        )
        assertEquals(PlanAction.Subscribe, ui.products.last().action)
    }

    @Test
    fun `nothing available at all is the empty note, not two unavailable rows`() {
        val catalog = Catalog.Loaded(emptyList(), missing = BillingProducts.ALL)
        val ui = planCardUi(state(free, catalog = catalog), "free")
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
    fun `a non-monthly period shows the bare price`() {
        val yearly = Catalog.Loaded(listOf(SubscriptionProduct(BillingProducts.PRO, "€99", "P1Y", "o")))
        assertEquals(false, planCardUi(state(free, catalog = yearly), "free").products.single().monthly)
    }
}
