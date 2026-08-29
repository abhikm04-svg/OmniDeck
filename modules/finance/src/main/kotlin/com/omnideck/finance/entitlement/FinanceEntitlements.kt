package com.omnideck.finance.entitlement

import com.omnideck.sdk.Sku
import com.omnideck.sdk.capability.BillingService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Finance's paid tier (OD-311).
 *
 * The module installs free and records spending for anyone; the category breakdown
 * is the part behind [PRO]. That split is the shape a real module needs from the
 * platform, and it is the reason this exists as the second module: Notes exercises
 * storage, sync and offline, and exercises billing not at all.
 *
 * **The client is not the authority.** [BillingService.entitlements] is a
 * server-verified snapshot (architecture.md §13), and nothing here records a local
 * purchase and treats it as ownership — a purchase that Play reports as
 * [BillingService.PurchaseResult.Purchased] still only unlocks the feature once it
 * comes back on the entitlement flow. That is slower, visibly so, and it is the
 * difference between a paywall and a suggestion.
 */
class FinanceEntitlements(private val billing: BillingService) {

    val hasPro: Flow<Boolean> = billing.entitlements.map { PRO in it }

    suspend fun price(): String? = billing.products(setOf(PRO)).firstOrNull()?.formattedPrice

    /**
     * Returns what Play said, not what the UI hopes.
     *
     * `Pending` is a real outcome — a purchase awaiting a parent's approval or a
     * slow payment method — and collapsing it into failure tells the user their
     * money did not go through when it may still.
     */
    suspend fun purchase(): BillingService.PurchaseResult = billing.purchase(PRO)

    /** Called when Finance comes to the foreground: entitlements can be revoked. */
    suspend fun refresh() = billing.refresh()

    companion object {
        val PRO = Sku("omnideck.finance.pro")
    }
}
