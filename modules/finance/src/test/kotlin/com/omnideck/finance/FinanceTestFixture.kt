package com.omnideck.finance

import com.omnideck.finance.data.Spend
import com.omnideck.finance.data.SpendCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Keeps a `WhileSubscribed` state flow hot for the duration of a test.
 *
 * The production sharing policy is deliberately lazy, so without a subscriber
 * `state.value` never advances past its initial value and every assertion reads the
 * empty seed. Subscribing here rather than loosening the ViewModel keeps the test
 * honest about what the screen actually does.
 *
 * Unconfined on purpose: on the default test dispatcher the collector would not run
 * until the test next suspended, so an assertion made straight after an action would
 * read the state from before it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.keepHot(state: StateFlow<*>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect { } }
}

fun spend(id: String, minorUnits: Long, category: SpendCategory = SpendCategory.OTHER, recordedAtMs: Long = 1L) = Spend(
    id = id,
    description = "Entry $id",
    minorUnits = minorUnits,
    category = category,
    recordedAtMs = recordedAtMs,
)
