package com.omnideck.finance.ui

import com.google.common.truth.Truth.assertThat
import com.omnideck.core.MutableClock
import com.omnideck.finance.InMemorySpendStore
import com.omnideck.finance.data.SpendCategory
import com.omnideck.finance.data.SpendRepository
import com.omnideck.finance.keepHot
import com.omnideck.testing.FakeRouter
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

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceHomeViewModelTest {

    private val router = FakeRouter()
    private val telemetry = FakeTelemetryService()
    private val clock = MutableClock(startMillis = 1_700_000_000_000)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(repository: SpendRepository = SpendRepository(InMemorySpendStore())) =
        FinanceHomeViewModel(repository, router, telemetry, clock).also { keepHot(it.state) }

    @Test
    fun `a recorded amount reaches the running total`() = runTest {
        val viewModel = viewModel()

        viewModel.add("Coffee", 250, SpendCategory.FOOD)

        assertThat(viewModel.state.value.totalMinorUnits).isEqualTo(250)
    }

    @Test
    fun `a blank description or a zero amount records nothing`() = runTest {
        val viewModel = viewModel()

        viewModel.add("  ", 250, SpendCategory.FOOD)
        viewModel.add("Coffee", 0, SpendCategory.FOOD)

        assertThat(viewModel.state.value.spends).isEmpty()
    }

    @Test
    fun `telemetry records the category and never the amount or the description`() = runTest {
        // This module handles financial records. The event exists to count usage, not
        // to reconstruct someone's spending on a dashboard (architecture.md §12.5).
        viewModel().add("Rent", 120_000, SpendCategory.ESSENTIALS)

        val attributes = telemetry.events.last { it.name == "finance_spend_added" }.attributes
        assertThat(attributes).containsEntry("category", "ESSENTIALS")
        assertThat(attributes.values.map { it.toString() }).doesNotContain("Rent")
        assertThat(attributes.values.map { it.toString() }).doesNotContain("120000")
    }

    @Test
    fun `insights are opened through the Router, so one URI serves every caller`() = runTest {
        viewModel().openInsights()

        assertThat(router.navigations.map { it.uri }).contains("omnideck://finance/insights")
    }

    @Test
    fun `removing a record takes it out of the total`() = runTest {
        val viewModel = viewModel()
        viewModel.add("Coffee", 250, SpendCategory.FOOD)
        val id = viewModel.state.value.spends.single().id

        viewModel.remove(id)

        assertThat(viewModel.state.value.totalMinorUnits).isEqualTo(0)
    }
}

/** Amount parsing, which is where money bugs actually live. */
class MoneyFormattingTest {

    @Test
    fun `decimal input becomes minor units without going through a Double`() {
        assertThat("12.50".toMinorUnitsOrNull()).isEqualTo(1250)
        assertThat("19.99".toMinorUnitsOrNull()).isEqualTo(1999)
        assertThat("7".toMinorUnitsOrNull()).isEqualTo(700)
        assertThat("0.05".toMinorUnitsOrNull()).isEqualTo(5)
    }

    @Test
    fun `a comma is accepted, because most of the world writes amounts that way`() {
        assertThat("12,50".toMinorUnitsOrNull()).isEqualTo(1250)
    }

    @Test
    fun `anything that is not an amount is rejected rather than guessed at`() {
        assertThat("".toMinorUnitsOrNull()).isNull()
        assertThat("abc".toMinorUnitsOrNull()).isNull()
        assertThat("-5".toMinorUnitsOrNull()).isNull()
        assertThat("1.2.3".toMinorUnitsOrNull()).isNull()
    }

    @Test
    fun `minor units render with both decimal places`() {
        assertThat(5L.asMoney()).isEqualTo("0.05")
        assertThat(1250L.asMoney()).isEqualTo("12.50")
    }
}
