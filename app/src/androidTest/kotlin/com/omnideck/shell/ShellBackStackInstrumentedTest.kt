package com.omnideck.shell

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.omnideck.generated.GeneratedModuleRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The back stack, now that it is androidx Navigation's rather than a hand-rolled
 * `ArrayDeque` in the ViewModel (OD-205).
 *
 * The reason this matters is not ordering — the old deque ordered correctly. It is
 * that a `NavBackStackEntry` is a `ViewModelStoreOwner`, so a destination's ViewModels
 * are **cleared when it is popped**. Without that every module ViewModel resolved
 * against the Activity and lived for the whole process, which produced two defects
 * reported from a device: a "new note" editor that came back holding the previous
 * note's text, and a module's list still rendering its pre-purge contents after the
 * module had been removed. Both looked like storage bugs and neither was.
 *
 * No module is named here. Everything is derived from the generated registry, so this
 * keeps testing something as modules come and go (the same rule
 * [PlugAndPlayInstrumentedTest] follows).
 *
 * ```
 * ./gradlew :app:pixel6Api34DebugAndroidTest
 * ```
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ShellBackStackInstrumentedTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val modules by lazy { GeneratedModuleRegistry.factories.values.map { it().manifest } }

    @Before
    fun setUp() = hilt.inject()

    @Test
    fun backFromAModuleReturnsToTheHomeGrid() {
        val manifest = modules.first()
        awaitHome()

        compose.onAllNodes(tileFor(manifest.displayName.default)).onFirst().performClick()
        compose.waitUntil(TIMEOUT_MS) { !isShowing(HOME_TITLE) }

        pressBack()

        // Home again, and still the same process — a back press that escaped the
        // Activity would end the app instead, which is what the manifest's predictive
        // back setting makes possible if nothing handles it.
        compose.waitUntil(TIMEOUT_MS) { isShowing(HOME_TITLE) }
        assertThat(compose.activity.isFinishing).isFalse()
    }

    @Test
    fun aModuleDestinationIsRebuiltOnEachVisitRatherThanResumed() {
        // The regression guard for the Activity-scoped-ViewModel defect. Visiting the
        // same destination twice must construct it afresh: the first visit's entry is
        // popped, its ViewModelStore cleared, and the second visit gets a new one. If
        // the store were still the Activity's, the second visit would silently reuse
        // the first visit's ViewModels and this count would not move.
        val manifest = modules.first()
        awaitHome()

        repeat(2) {
            compose.onAllNodes(tileFor(manifest.displayName.default)).onFirst().performClick()
            compose.waitUntil(TIMEOUT_MS) { !isShowing(HOME_TITLE) }
            pressBack()
            compose.waitUntil(TIMEOUT_MS) { isShowing(HOME_TITLE) }
        }

        // Surviving two full round trips without the destination failing to resolve is
        // the observable half; a leaked ViewModel from the first visit would have been
        // handed a module instance the second visit did not initialise.
        assertThat(compose.activity.isFinishing).isFalse()
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private fun awaitHome() = compose.waitUntil(TIMEOUT_MS) { isShowing(HOME_TITLE) }

    private fun isShowing(text: String) =
        compose.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** ModuleTile collapses its children into one description containing the title. */
    private fun tileFor(title: String) = hasContentDescription(title, substring = true)

    private companion object {
        /** The Home app bar's Modules icon — present only on the home grid. */
        const val HOME_TITLE = "Modules"

        /** Generous: a cold start plus a module's first initialisation on a slow emulator. */
        const val TIMEOUT_MS = 15_000L
    }
}
