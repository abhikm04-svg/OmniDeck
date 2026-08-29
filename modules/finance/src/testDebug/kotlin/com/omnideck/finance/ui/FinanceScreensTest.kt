package com.omnideck.finance.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.omnideck.finance.data.CategoryTotal
import com.omnideck.finance.data.SpendCategory
import com.omnideck.finance.spend
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Finance's screens, rendered offline.
 *
 * These run under Robolectric rather than on a device on purpose: a module whose UI
 * can only be exercised with an emulator attached is a module whose UI stops being
 * exercised. The assertions are about what the screen *says* — the paywall naming
 * its feature, the empty state distinguishing "nothing recorded" from "not
 * entitled" — because those are the parts a user acts on and the parts a refactor
 * silently breaks.
 *
 * **In `src/testDebug` rather than `src/test`.** `createComposeRule` needs the host
 * activity from `ui-test-manifest`, which `omnideck.compose` supplies as a
 * `debugImplementation` — correctly, since a test activity has no business in a
 * release artifact. Left in the common test source set these fail the *release*
 * unit-test task with "Unable to resolve activity for Intent", which reads like a
 * Compose problem and is a source-set one.
 */
@RunWith(RobolectricTestRunner::class)
class FinanceScreensTest {

    @get:Rule
    val compose = createComposeRule()

    // -- home ---------------------------------------------------------------

    @Test
    fun `the empty state invites a first entry rather than showing a blank list`() {
        compose.setContent {
            FinanceHomeScreen(
                state = FinanceHomeState(loaded = true),
                onAdd = { _, _, _ -> },
                onRemove = {},
                onInsights = {},
            )
        }

        compose.onNodeWithText("Nothing recorded yet").assertExists()
    }

    @Test
    fun `a loading state is shown instead of an empty list that is not yet empty`() {
        // The difference matters: "you have recorded nothing" and "we have not looked
        // yet" are different statements, and showing the first while the second is
        // true tells the user their data is gone.
        compose.setContent {
            FinanceHomeScreen(
                state = FinanceHomeState(loaded = false),
                onAdd = { _, _, _ -> },
                onRemove = {},
                onInsights = {},
            )
        }

        compose.onNodeWithText("Nothing recorded yet").assertDoesNotExist()
    }

    @Test
    fun `recorded spending is listed with its running total`() {
        compose.setContent {
            FinanceHomeScreen(
                state = FinanceHomeState(
                    spends = listOf(spend("a", 1250, SpendCategory.FOOD)),
                    totalMinorUnits = 1250,
                    loaded = true,
                ),
                onAdd = { _, _, _ -> },
                onRemove = {},
                onInsights = {},
            )
        }

        compose.onNodeWithText("Entry a").assertExists()
        // Twice: once as the running total, once on the row it came from.
        compose.onAllNodesWithText("12.50").assertCountEquals(2)
    }

    @Test
    fun `insights are reachable from the home screen`() {
        var opened = false
        compose.setContent {
            FinanceHomeScreen(
                state = FinanceHomeState(loaded = true),
                onAdd = { _, _, _ -> },
                onRemove = {},
                onInsights = { opened = true },
            )
        }

        compose.onNodeWithText("Insights").performClick()

        assertThat(opened).isTrue()
    }

    @Test
    fun `removing a listed entry reports the entry that was removed`() {
        var removed: String? = null
        compose.setContent {
            FinanceHomeScreen(
                state = FinanceHomeState(
                    spends = listOf(spend("a", 100)),
                    totalMinorUnits = 100,
                    loaded = true,
                ),
                onAdd = { _, _, _ -> },
                onRemove = { removed = it },
                onInsights = {},
            )
        }

        compose.onNodeWithContentDescription("Remove Entry a").performClick()

        assertThat(removed).isEqualTo("a")
    }

    // -- insights -----------------------------------------------------------

    @Test
    fun `the paywall names the feature it is selling, and its price`() {
        compose.setContent {
            InsightsScreen(
                state = InsightsState(entitled = false, price = "£2.99"),
                onPurchase = {},
                onMessageShown = {},
            )
        }

        compose.onNodeWithText("See where it goes").assertExists()
        compose.onNodeWithText("Upgrade — £2.99").assertExists()
    }

    @Test
    fun `an upgrade already in flight cannot be tapped a second time`() {
        // Play's sheet takes a moment to appear, and a second purchase call in that
        // window is a second charge to explain.
        compose.setContent {
            InsightsScreen(
                state = InsightsState(entitled = false, price = "£2.99", purchaseInFlight = true),
                onPurchase = {},
                onMessageShown = {},
            )
        }

        compose.onNodeWithText("Upgrade — £2.99").assertIsNotEnabled()
    }

    @Test
    fun `an unavailable price still offers the upgrade, and says the price is missing`() {
        compose.setContent {
            InsightsScreen(
                state = InsightsState(entitled = false, price = null),
                onPurchase = {},
                onMessageShown = {},
            )
        }

        compose.onNodeWithText("Upgrade").assertExists()
        compose.onNodeWithText("Pricing is unavailable right now.").assertExists()
    }

    @Test
    fun `an entitled user sees the breakdown and no paywall`() {
        compose.setContent {
            InsightsScreen(
                state = InsightsState(
                    entitled = true,
                    breakdown = listOf(CategoryTotal(SpendCategory.FOOD, 750, 0.75f)),
                ),
                onPurchase = {},
                onMessageShown = {},
            )
        }

        compose.onNodeWithText("Food").assertExists()
        compose.onNodeWithText("See where it goes").assertDoesNotExist()
    }

    @Test
    fun `an entitled user with nothing recorded is told what to do, not shown an empty chart`() {
        compose.setContent {
            InsightsScreen(
                state = InsightsState(entitled = true, breakdown = emptyList()),
                onPurchase = {},
                onMessageShown = {},
            )
        }

        compose.onNodeWithText("Nothing to break down").assertExists()
    }
}
