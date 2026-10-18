package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.billing.BillingError
import ai.ankimcp.ankidroid.billing.BillingProducts
import ai.ankimcp.ankidroid.billing.BillingState
import ai.ankimcp.ankidroid.billing.Catalog
import ai.ankimcp.ankidroid.billing.PlanLoad
import ai.ankimcp.ankidroid.billing.PlanProvider
import ai.ankimcp.ankidroid.billing.PlanStatusKind
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.time.Instant

/** What the account's plan is, as far as the Plan card is concerned. */
enum class PlanKind {
    Loading,
    LoadFailed,

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

    /** Play did not return the product or its `monthly` base plan offer: no price, no button. */
    Unavailable,
}

data class PlanProductUi(
    val productId: String,
    val tier: String,
    /** Play's formatted price; the only price text the app shows. Null when unavailable. */
    val price: String?,
    val monthly: Boolean,
    val action: PlanAction,
    val enabled: Boolean,
)

enum class CatalogNote { Loading, Unavailable, Empty }

data class PlanCardUi(
    val kind: PlanKind,
    val tier: String,
    val renewal: RenewalNote?,
    val periodEndsAt: Instant?,
    val products: List<PlanProductUi>,
    /** Why there are no products to show although the plan could be bought or changed. */
    val catalogNote: CatalogNote?,
    val manageProductId: String?,
    val flow: PurchaseFlow,
)

/** Plain-Kotlin derivation of the Plan card from the coordinator's state (D62). */
internal fun planCardUi(state: BillingState, userTier: String): PlanCardUi {
    val status = (state.plan as? PlanLoad.Loaded)?.status
    val kind = when {
        state.plan is PlanLoad.Loading -> PlanKind.Loading
        status == null -> PlanKind.LoadFailed
        status.provider == PlanProvider.None -> PlanKind.Free
        status.provider == PlanProvider.GooglePlay -> PlanKind.GooglePlay
        status.provider == PlanProvider.Admin -> PlanKind.Granted
        else -> PlanKind.ManagedOnWeb
    }
    val currentProductId = status?.productId?.takeIf { kind == PlanKind.GooglePlay }
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
    val products = if (!sellable || loaded.isEmpty()) {
        emptyList()
    } else {
        val available = loaded.map { product ->
            PlanProductUi(
                productId = product.productId,
                tier = BillingProducts.tierFor(product.productId) ?: product.productId,
                price = product.formattedPrice,
                monthly = product.billingPeriod == "P1M",
                action = when {
                    currentProductId == null -> PlanAction.Subscribe
                    product.productId == currentProductId -> PlanAction.Current
                    else -> PlanAction.SwitchTo
                },
                enabled = canStart && product.productId != currentProductId,
            )
        }
        val unavailable = catalog?.missing.orEmpty().map { productId ->
            PlanProductUi(
                productId = productId,
                tier = BillingProducts.tierFor(productId) ?: productId,
                price = null,
                monthly = false,
                action = if (productId == currentProductId) PlanAction.Current else PlanAction.Unavailable,
                enabled = false,
            )
        }
        (available + unavailable).sortedBy { BillingProducts.rank(it.productId) }
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
    onSubscribe: (productId: String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !ui.flow.busy) { Text(stringResource(R.string.plan_refresh)) }
            }
            when (ui.kind) {
                PlanKind.Loading -> SupportingText(stringResource(R.string.plan_loading))
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

            ui.products.forEach { product ->
                ProductRow(product, onClick = { onSubscribe(product.productId) })
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
private fun ProductRow(product: PlanProductUi, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tierLabel(product.tier), style = MaterialTheme.typography.titleSmall)
            SupportingText(
                when {
                    product.price == null -> stringResource(R.string.plan_product_unavailable)
                    product.monthly -> stringResource(R.string.plan_price_monthly, product.price)
                    else -> product.price
                },
            )
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
                Text(stringResource(R.string.plan_switch_to, tierLabel(product.tier)))
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
