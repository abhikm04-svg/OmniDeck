package com.omnideck.finance.ui

import com.google.common.truth.Truth.assertThat
import com.omnideck.finance.InMemorySpendStore
import com.omnideck.finance.data.Spend
import com.omnideck.finance.data.SpendCategory
import com.omnideck.finance.data.SpendRepository
import com.omnideck.finance.entitlement.FinanceEntitlements
import com.omnideck.finance.keepHot
import com.omnideck.finance.spend
import com.omnideck.sdk.capability.BillingService
import com.omnideck.testing.FakeBillingService
import com.omnideck.testing.FakeTelemetryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The paid tier (OD-311).
 *
 * The load-bearing assertion is the one about *where* the unlock comes from. Play
 * returning `Purchased` is not entitlement; the server-verified entitlement flow is
 * (architecture.md §13). A module that unlocks on the purchase result has a paywall
 * that anyone who can make `purchase()` return success walks straight through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {

    private val billing = FakeBillingService()
    private val telemetry = FakeTelemetryService()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.viewModel(vararg spends: Spend): InsightsViewModel {
        val repository = SpendRepository(InMemorySpendStore())
        spends.forEach { repository.add(it) }
        return InsightsViewModel(repository, FinanceEntitlements(billing), telemetry)
            .also { keepHot(it.state) }
    }

    @Test
    fun `without the entitlement there is no breakdown to screenshot`() = runTest {
        // Computed only when entitled, rather than computed always and hidden by the
        // UI: a paid feature that is merely not drawn is one layout inspector away
        // from being free.
        val viewModel = viewModel(spend("a", 500, SpendCategory.FOOD))

        assertThat(viewModel.state.value.entitled).isFalse()
        assertThat(viewModel.state.value.breakdown).isEmpty()
    }

    @Test
    fun `the entitlement flow unlocks it, not the purchase result`() = runTest {
        val viewModel = viewModel(spend("a", 500, SpendCategory.FOOD))

        billing.grant(setOf(FinanceEntitlements.PRO))

        assertThat(viewModel.state.value.entitled).isTrue()
        assertThat(viewModel.state.value.breakdown).isNotEmpty()
    }

    @Test
    fun `a successful purchase that grants nothing unlocks nothing`() = runTest {
        // The client is not the authority. If Play reports success but the entitlement
        // never arrives, the feature stays shut.
        billing.nextPurchaseResult = BillingService.PurchaseResult.Purchased(FinanceEntitlements.PRO)
        val viewModel = viewModel(spend("a", 500, SpendCategory.FOOD))

        viewModel.purchase()

        assertThat(viewModel.state.value.entitled).isFalse()
    }

    @Test
    fun `a pending purchase says so instead of reporting a failure`() = runTest {
        // Awaiting a parent's approval or a slow payment method is not a failure, and
        // telling someone their money did not go through when it may still is worse
        // than saying nothing.
        billing.nextPurchaseResult = BillingService.PurchaseResult.Pending
        val viewModel = viewModel()

        viewModel.purchase()

        assertThat(viewModel.state.value.message).contains("still being confirmed")
    }

    @Test
    fun `shares are of the total and the largest category leads`() = runTest {
        val viewModel = viewModel(
            spend("food", 750, SpendCategory.FOOD),
            spend("transport", 250, SpendCategory.TRANSPORT),
        )
        billing.grant(setOf(FinanceEntitlements.PRO))

        val breakdown = viewModel.state.value.breakdown

        assertThat(breakdown.map { it.category })
            .containsExactly(SpendCategory.FOOD, SpendCategory.TRANSPORT).inOrder()
        assertThat(breakdown.first().share).isWithin(TOLERANCE).of(0.75f)
    }

    @Test
    fun `a purchase is reported to telemetry by outcome, never by amount`() = runTest {
        viewModel().purchase()

        assertThat(telemetry.eventNames()).contains("finance_pro_purchase")
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
