package com.omnideck.finance.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnideck.finance.data.CategoryTotal
import com.omnideck.finance.data.Spend
import com.omnideck.finance.data.SpendRepository
import com.omnideck.finance.entitlement.FinanceEntitlements
import com.omnideck.sdk.capability.BillingService
import com.omnideck.sdk.capability.TelemetryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the Insights screen shows.
 *
 * [entitled] is the gate and [breakdown] is empty without it — computed only when
 * the entitlement flow says so, rather than computed always and hidden by the UI. A
 * paid feature that is calculated and merely not drawn is one screenshot away from
 * being free.
 */
data class InsightsState(
    val entitled: Boolean = false,
    val breakdown: List<CategoryTotal> = emptyList(),
    val price: String? = null,
    val purchaseInFlight: Boolean = false,
    val message: String? = null,
)

class InsightsViewModel(
    private val repository: SpendRepository,
    private val entitlements: FinanceEntitlements,
    private val telemetry: TelemetryService,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    val state: StateFlow<InsightsState> =
        combine(repository.spends, entitlements.hasPro, transient) { spends, entitled, extra ->
            InsightsState(
                entitled = entitled,
                breakdown = if (entitled) breakdown(spends) else emptyList(),
                price = extra.price,
                purchaseInFlight = extra.purchaseInFlight,
                message = extra.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), InsightsState())

    init {
        viewModelScope.launch {
            // Entitlements can be revoked server-side between sessions, so the screen
            // asks rather than trusting whatever was cached from last time.
            entitlements.refresh()
            transient.value = transient.value.copy(price = entitlements.price())
        }
    }

    /**
     * Buys the upgrade, and does *not* unlock anything itself.
     *
     * The feature turns on when [FinanceEntitlements.hasPro] says so — a
     * server-verified fact — not when Play returns success here. Unlocking locally
     * on a `Purchased` result is the shortcut that turns a paywall into an
     * advisory notice (architecture.md §13).
     */
    fun purchase() = viewModelScope.launch {
        transient.value = transient.value.copy(purchaseInFlight = true, message = null)
        val result = entitlements.purchase()
        telemetry.event("finance_pro_purchase", mapOf("result" to result::class.simpleName.orEmpty()))
        transient.value = transient.value.copy(
            purchaseInFlight = false,
            message = result.describe(),
        )
    }

    fun dismissMessage() {
        transient.value = transient.value.copy(message = null)
    }

    /**
     * Shares are of the total, computed once.
     *
     * Integer minor units throughout: a percentage derived from a running
     * floating-point sum is how a breakdown ends up totalling 99.9%.
     */
    private fun breakdown(spends: List<Spend>): List<CategoryTotal> {
        val total = spends.sumOf(Spend::minorUnits)
        if (total <= 0) return emptyList()
        return spends
            .groupBy(Spend::category)
            .map { (category, entries) ->
                val sum = entries.sumOf(Spend::minorUnits)
                CategoryTotal(category, sum, sum.toFloat() / total)
            }
            .sortedByDescending(CategoryTotal::minorUnits)
    }

    private data class TransientState(
        val price: String? = null,
        val purchaseInFlight: Boolean = false,
        val message: String? = null,
    )

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}

/**
 * Play's outcomes in the user's language.
 *
 * `Pending` is a real result — a purchase awaiting a parent's approval or a slow
 * payment method — and reporting it as a failure tells someone their money did not
 * go through when it may still.
 */
internal fun BillingService.PurchaseResult.describe(): String? = when (this) {
    is BillingService.PurchaseResult.Purchased -> null
    is BillingService.PurchaseResult.Cancelled -> null
    is BillingService.PurchaseResult.Pending ->
        "Your purchase is still being confirmed. Insights unlock as soon as it clears."
    is BillingService.PurchaseResult.Failed -> message
}
