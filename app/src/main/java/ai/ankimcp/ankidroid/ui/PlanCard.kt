package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.billing.BillingError
import ai.ankimcp.ankidroid.billing.BillingPeriod
import ai.ankimcp.ankidroid.billing.BillingProducts
import ai.ankimcp.ankidroid.billing.BillingState
import ai.ankimcp.ankidroid.billing.Catalog
import ai.ankimcp.ankidroid.billing.PlanLoad
import ai.ankimcp.ankidroid.billing.PlanProvider
import ai.ankimcp.ankidroid.billing.PlanStatusKind
import ai.ankimcp.ankidroid.billing.PromoOffer
import ai.ankimcp.ankidroid.billing.PurchaseFlow
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.time.Instant

/** What the account's plan is, as far as the Plan card is concerned. */
enum class PlanKind {
    Loading,
    LoadFailed,

    /** The relay has no billing yet (status 404/405/501): the quiet "not available yet" note only. */
    NotAvailable,

    /** tier free, provider none: both products can be bought. */
    Free,

    /** Billed through Google Play: tier, renewal line, Manage subscription, plan change. */
    GooglePlay,

    /** Granted by an admin: no buttons. */
    Granted,

    /** Billed on the web (Polar, Paddle, or a provider this version does not know): no buttons. */
    ManagedOnWeb,
}

enum class RenewalNote { Renews, Ends, PaymentProblem }

enum class PlanAction {
    Subscribe,
    Current,
    SwitchTo,

    /** Play did not return the product or the row's base plan offer: no price, no button. */
    Unavailable,
}

/** The recurring period a price is labelled with; null shows the bare price. */
enum class PricePeriod { Month, Year }

enum class PeriodUnit { Day, Week, Month, Year }

data class PeriodPart(val count: Int, val unit: PeriodUnit)

/** How long a promo's discounted price lasts, in the words the card uses. */
sealed interface PromoLength {
    /** One stretch: "for the first year", "for the first 3 months". */
    data class First(val count: Int, val unit: PeriodUnit) : PromoLength

    /** Charged every unit: "<price> / month for 3 months". */
    data class PerUnit(val count: Int, val unit: PeriodUnit) : PromoLength

    /**
     * Anything else: "<price> for 1 year 6 months", or with [cycles] above 1 "<price> / 3 months
     * for 2 periods". [parts] is null when [billingPeriod] does not parse; the card then shows it raw.
     */
    data class Other(val parts: List<PeriodPart>?, val billingPeriod: String, val cycles: Int) : PromoLength
}

data class PromoUi(
    /** Play's formatted discounted price. */
    val price: String,
    val length: PromoLength,
)

data class PlanProductUi(
    val productId: String,
    val basePlanId: String,
    val tier: String,
    /** Play's formatted price; the only price text the app shows. Null when unavailable. */
    val price: String?,
    val period: PricePeriod?,
    val action: PlanAction,
    val enabled: Boolean,
    /** A promotional offer: its price is shown first, the regular [price] after it. */
    val promo: PromoUi? = null,
) {
    /** Only an Unavailable row says "Not available in Google Play"; a Current row without a price has no price line. */
    val notInPlay: Boolean get() = action == PlanAction.Unavailable
}

internal fun periodParts(period: BillingPeriod): List<PeriodPart> = listOf(
    PeriodPart(period.years, PeriodUnit.Year),
    PeriodPart(period.months, PeriodUnit.Month),
    PeriodPart(period.weeks, PeriodUnit.Week),
    PeriodPart(period.days, PeriodUnit.Day),
).filter { it.count > 0 }

internal fun promoUi(promo: PromoOffer): PromoUi {
    val parts = BillingPeriod.parse(promo.billingPeriod)?.let(::periodParts)
    val single = parts?.singleOrNull()
    val cycles = promo.billingCycleCount
    val length = when {
        single != null && cycles == 1 -> PromoLength.First(single.count, single.unit)
        single != null && single.count == 1 && cycles > 1 -> PromoLength.PerUnit(cycles, single.unit)
        else -> PromoLength.Other(parts, promo.billingPeriod, cycles)
    }
    return PromoUi(promo.formattedPrice, length)
}

enum class CatalogNote { Loading, Unavailable, Empty }

data class PlanCardUi(
    val kind: PlanKind,
    val tier: String,
    val renewal: RenewalNote?,
    val periodEndsAt: Instant?,
    /** Rows of every base plan the card offers (monthly only without [intervalSwitch]). */
    val products: List<PlanProductUi>,
    /** The Monthly / Yearly switch: shown when Play has at least one yearly offer or the current Play plan is yearly. */
    val intervalSwitch: Boolean,
    /** The switch's selection until the user picks one: the current Play plan's, else monthly. */
    val defaultBasePlan: String,
    /** Why there are no products to show although the plan could be bought or changed. */
    val catalogNote: CatalogNote?,
    val manageProductId: String?,
    val flow: PurchaseFlow,
) {
    /** The base plan whose rows are shown, given the user's pick (null before any). */
    fun selectedBasePlan(choice: String?): String =
        if (!intervalSwitch) BillingProducts.MONTHLY else choice ?: defaultBasePlan

    fun productsFor(basePlanId: String): List<PlanProductUi> = products.filter { it.basePlanId == basePlanId }
}

/** Plain-Kotlin derivation of the Plan card from the coordinator's state (D62). */
internal fun planCardUi(state: BillingState, userTier: String): PlanCardUi {
    val status = (state.plan as? PlanLoad.Loaded)?.status
    val kind = when {
        state.plan is PlanLoad.Loading -> PlanKind.Loading
        state.plan is PlanLoad.NotAvailable -> PlanKind.NotAvailable
        status == null -> PlanKind.LoadFailed
        status.provider == PlanProvider.None -> PlanKind.Free
        status.provider == PlanProvider.GooglePlay -> PlanKind.GooglePlay
        status.provider == PlanProvider.Admin -> PlanKind.Granted
        else -> PlanKind.ManagedOnWeb
    }
    val currentProductId = status?.productId?.takeIf { kind == PlanKind.GooglePlay }
    // Without an interval (purchases from before yearly existed) the plan is monthly.
    val currentBasePlan = currentProductId?.let { BillingProducts.basePlanFor(status?.interval) }
    val renewal = if (kind != PlanKind.GooglePlay || status == null) {
        null
    } else {
        when {
            status.status == PlanStatusKind.Grace -> RenewalNote.PaymentProblem
            status.currentPeriodEndsAt == null -> null
            status.status == PlanStatusKind.Canceled || status.cancelAtPeriodEnd -> RenewalNote.Ends
            else -> RenewalNote.Renews
        }
    }
    val sellable = kind == PlanKind.Free || currentProductId != null
    val catalog = state.catalog as? Catalog.Loaded
    val loaded = catalog?.products.orEmpty()
    // Pending counts too: a second purchase must not start while Play still waits for a payment.
    val canStart = !state.flow.blocksPurchase
    // A yearly subscriber keeps the switch and the Current row even when no yearly offer is loaded.
    val intervalSwitch = sellable &&
        (catalog?.offers(BillingProducts.YEARLY) == true || currentBasePlan == BillingProducts.YEARLY)
    val basePlans = if (intervalSwitch) BillingProducts.BASE_PLANS else listOf(BillingProducts.MONTHLY)
    val products = if (!sellable || catalog == null || loaded.isEmpty()) {
        emptyList()
    } else {
        basePlans.flatMap { basePlanId ->
            val available = loaded.filter { it.basePlanId == basePlanId }.map { product ->
                val current = product.productId == currentProductId && basePlanId == currentBasePlan
                PlanProductUi(
                    productId = product.productId,
                    basePlanId = basePlanId,
                    tier = BillingProducts.tierFor(product.productId) ?: product.productId,
                    price = product.formattedPrice,
                    period = when (product.billingPeriod) {
                        "P1M" -> PricePeriod.Month
                        "P1Y" -> PricePeriod.Year
                        else -> null
                    },
                    action = when {
                        currentProductId == null -> PlanAction.Subscribe
                        current -> PlanAction.Current
                        else -> PlanAction.SwitchTo
                    },
                    enabled = canStart && !current,
                    // The current plan cannot be bought again, so its row keeps the regular price.
                    promo = product.promo?.takeUnless { current }?.let(::promoUi),
                )
            }
            val unavailable = catalog.missing(basePlanId).map { productId ->
                PlanProductUi(
                    productId = productId,
                    basePlanId = basePlanId,
                    tier = BillingProducts.tierFor(productId) ?: productId,
                    price = null,
                    period = null,
                    action = if (productId == currentProductId && basePlanId == currentBasePlan) {
                        PlanAction.Current
                    } else {
                        PlanAction.Unavailable
                    },
                    enabled = false,
                )
            }
            (available + unavailable).sortedBy { BillingProducts.rank(it.productId) }
        }
    }
    val catalogNote = when {
        !sellable || products.isNotEmpty() -> null
        state.catalog is Catalog.Loading -> CatalogNote.Loading
        state.catalog is Catalog.Unavailable -> CatalogNote.Unavailable
        else -> CatalogNote.Empty
    }
    return PlanCardUi(
        kind = kind,
        tier = status?.tier ?: userTier,
        renewal = renewal,
        periodEndsAt = status?.currentPeriodEndsAt,
        products = products,
        intervalSwitch = intervalSwitch,
        defaultBasePlan = currentBasePlan ?: BillingProducts.MONTHLY,
        catalogNote = catalogNote,
        manageProductId = currentProductId,
        flow = state.flow,
    )
}

@Composable
internal fun tierLabel(tier: String): String = when (tier) {
    "free" -> stringResource(R.string.plan_tier_free)
    "plus" -> stringResource(R.string.plan_tier_plus)
    "pro" -> stringResource(R.string.plan_tier_pro)
    else -> tier.replaceFirstChar { it.uppercase() }
}

/** The Plan card on the Remote tab, below the account card (signed in only). */
@Composable
internal fun PlanCard(
    ui: PlanCardUi,
    formatDate: (Instant) -> String,
    onSubscribe: (productId: String, basePlanId: String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var basePlanChoice by rememberSaveable { mutableStateOf<String?>(null) }
    val basePlan = ui.selectedBasePlan(basePlanChoice)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !ui.flow.busy) { Text(stringResource(R.string.plan_refresh)) }
            }
            when (ui.kind) {
                PlanKind.Loading -> SupportingText(stringResource(R.string.plan_loading))
                PlanKind.NotAvailable -> SupportingText(stringResource(R.string.plan_products_empty))
                PlanKind.LoadFailed -> {
                    Text(stringResource(R.string.plan_load_failed), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.plan_try_again)) }
                }
                else -> {
                    Text(stringResource(R.string.plan_current, tierLabel(ui.tier)))
                    planNote(ui, formatDate)?.let { (text, error) ->
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (ui.intervalSwitch && ui.products.isNotEmpty()) {
                IntervalSwitch(selected = basePlan, onSelect = { basePlanChoice = it })
            }
            ui.productsFor(basePlan).forEach { product ->
                ProductRow(
                    product,
                    sameProduct = product.productId == ui.manageProductId,
                    onClick = { onSubscribe(product.productId, product.basePlanId) },
                )
            }
            when (ui.catalogNote) {
                CatalogNote.Loading -> SupportingText(stringResource(R.string.plan_products_loading))
                CatalogNote.Unavailable -> SupportingText(stringResource(R.string.plan_products_unavailable))
                CatalogNote.Empty -> SupportingText(stringResource(R.string.plan_products_empty))
                null -> Unit
            }

            FlowMessage(ui.flow, onRetry = onRetry, onDismiss = onDismiss)

            ui.manageProductId?.let { productId ->
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, BillingProducts.manageUrl(productId).toUri())
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        // No Play Store and no browser: nothing else to offer.
                    }
                }) { Text(stringResource(R.string.plan_manage)) }
            }
        }
    }
}

@Composable
private fun planNote(ui: PlanCardUi, formatDate: (Instant) -> String): Pair<String, Boolean>? = when (ui.kind) {
    PlanKind.Granted -> stringResource(R.string.plan_granted) to false
    PlanKind.ManagedOnWeb -> stringResource(R.string.plan_managed_on_web) to false
    PlanKind.GooglePlay -> when (ui.renewal) {
        RenewalNote.PaymentProblem -> stringResource(R.string.plan_payment_problem) to true
        RenewalNote.Ends -> ui.periodEndsAt?.let { stringResource(R.string.plan_ends_on, formatDate(it)) to false }
        RenewalNote.Renews -> ui.periodEndsAt?.let { stringResource(R.string.plan_renews_on, formatDate(it)) to false }
        null -> null
    }
    else -> null
}

@Composable
private fun IntervalSwitch(selected: String, onSelect: (String) -> Unit) {
    val plans = BillingProducts.BASE_PLANS
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        plans.forEachIndexed { index, basePlanId ->
            SegmentedButton(
                selected = basePlanId == selected,
                onClick = { onSelect(basePlanId) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = plans.size),
            ) {
                Text(
                    stringResource(
                        if (basePlanId == BillingProducts.YEARLY) R.string.plan_interval_yearly else R.string.plan_interval_monthly,
                    ),
                )
            }
        }
    }
}

/** "<promo> for the first year, then <regular>" and the like; [regular] is the row's usual price label. */
@Composable
private fun promoText(promo: PromoUi, regular: String): String = when (val length = promo.length) {
    is PromoLength.First -> pluralStringResource(
        when (length.unit) {
            PeriodUnit.Day -> R.plurals.plan_promo_first_days
            PeriodUnit.Week -> R.plurals.plan_promo_first_weeks
            PeriodUnit.Month -> R.plurals.plan_promo_first_months
            PeriodUnit.Year -> R.plurals.plan_promo_first_years
        },
        length.count,
        promo.price,
        regular,
        length.count,
    )
    is PromoLength.PerUnit -> pluralStringResource(
        when (length.unit) {
            PeriodUnit.Day -> R.plurals.plan_promo_per_day
            PeriodUnit.Week -> R.plurals.plan_promo_per_week
            PeriodUnit.Month -> R.plurals.plan_promo_per_month
            PeriodUnit.Year -> R.plurals.plan_promo_per_year
        },
        length.count,
        promo.price,
        regular,
        length.count,
    )
    is PromoLength.Other -> {
        val period = length.parts?.map { periodPartText(it) }?.joinToString(" ") ?: length.billingPeriod
        if (length.cycles > 1) {
            pluralStringResource(R.plurals.plan_promo_other_cycles, length.cycles, promo.price, period, regular, length.cycles)
        } else {
            stringResource(R.string.plan_promo_other, promo.price, period, regular)
        }
    }
}

/** "1 year", "3 months". */
@Composable
private fun periodPartText(part: PeriodPart): String = pluralStringResource(
    when (part.unit) {
        PeriodUnit.Day -> R.plurals.plan_period_days
        PeriodUnit.Week -> R.plurals.plan_period_weeks
        PeriodUnit.Month -> R.plurals.plan_period_months
        PeriodUnit.Year -> R.plurals.plan_period_years
    },
    part.count,
    part.count,
)

/** [sameProduct]: the row is the current product on another base plan, so a change is "Switch to yearly/monthly". */
@Composable
private fun ProductRow(product: PlanProductUi, sameProduct: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tierLabel(product.tier), style = MaterialTheme.typography.titleSmall)
                if (product.promo != null && product.price != null) {
                    Text(
                        stringResource(R.string.plan_promo_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            val regular = when {
                product.price == null -> null
                product.period == PricePeriod.Month -> stringResource(R.string.plan_price_monthly, product.price)
                product.period == PricePeriod.Year -> stringResource(R.string.plan_price_yearly, product.price)
                else -> product.price
            }
            when {
                product.notInPlay -> SupportingText(stringResource(R.string.plan_product_unavailable))
                regular == null -> Unit
                product.promo != null -> SupportingText(promoText(product.promo, regular))
                else -> SupportingText(regular)
            }
        }
        when (product.action) {
            PlanAction.Unavailable -> Unit
            PlanAction.Current -> OutlinedButton(onClick = {}, enabled = false) {
                Text(stringResource(R.string.plan_current_button))
            }
            PlanAction.Subscribe -> Button(onClick = onClick, enabled = product.enabled) {
                Text(stringResource(R.string.plan_subscribe))
            }
            PlanAction.SwitchTo -> Button(onClick = onClick, enabled = product.enabled) {
                Text(
                    when {
                        !sameProduct -> stringResource(R.string.plan_switch_to, tierLabel(product.tier))
                        product.basePlanId == BillingProducts.YEARLY -> stringResource(R.string.plan_switch_to_yearly)
                        else -> stringResource(R.string.plan_switch_to_monthly)
                    },
                )
            }
        }
    }
}

@Composable
private fun FlowMessage(flow: PurchaseFlow, onRetry: () -> Unit, onDismiss: () -> Unit) {
    when (flow) {
        // Checking: a restore asking Play, usually well under a second; buttons are disabled, no line.
        PurchaseFlow.Idle, PurchaseFlow.Checking -> Unit
        is PurchaseFlow.Preparing, is PurchaseFlow.Launching -> SupportingText(stringResource(R.string.plan_flow_opening))
        is PurchaseFlow.Registering -> SupportingText(stringResource(R.string.plan_flow_registering))
        is PurchaseFlow.Pending -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SupportingText(stringResource(R.string.plan_flow_pending))
            // Checks Google Play again: activates a payment that went through, clears one that was declined.
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.plan_try_again)) }
        }
        is PurchaseFlow.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.plan_flow_done, tierLabel(flow.tier)), modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.plan_dismiss)) }
        }
        is PurchaseFlow.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(errorText(flow), color = MaterialTheme.colorScheme.error)
            Row {
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.plan_try_again)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.plan_dismiss)) }
            }
        }
    }
}

@Composable
private fun errorText(flow: PurchaseFlow.Failed): String {
    val base = stringResource(
        when (flow.error) {
            BillingError.SignedOut -> R.string.plan_error_signed_out
            BillingError.ManagedElsewhere -> R.string.plan_error_managed_elsewhere
            BillingError.NotOwned -> R.string.plan_error_not_owned
            BillingError.AlreadySubscribed -> R.string.plan_error_already_subscribed
            BillingError.BillingDisabled -> R.string.plan_error_billing_disabled
            BillingError.Server -> R.string.plan_error_server
            BillingError.Network -> R.string.plan_error_network
            BillingError.PlayUnavailable -> R.string.plan_error_play_unavailable
            BillingError.ProductUnavailable -> R.string.plan_error_product_unavailable
            BillingError.ReplaceOtherAccount -> R.string.plan_error_replace_other_account
            BillingError.NoScreen -> R.string.plan_error_no_screen
            BillingError.Play -> R.string.plan_error_play
        },
    )
    return flow.detail?.let { "$base ($it)" } ?: base
}
