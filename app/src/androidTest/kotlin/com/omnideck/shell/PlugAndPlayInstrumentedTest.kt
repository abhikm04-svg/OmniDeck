package com.omnideck.shell

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.omnideck.generated.GeneratedModuleRegistry
import com.omnideck.kernel.router.MutableDestinationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The plug-and-play fitness test (OD-212) — the half that needs a device.
 *
 * Scaffold, build, load, render, with zero Shell diffs. The unit-level
 * [PlugAndPlayFitnessTest] proves the build-time mechanisms agree with each other;
 * this proves the result is a screen a user can reach: launch the Shell, tap whatever
 * module is in the build, and see that module's own UI.
 *
 * Not one module is named. Everything is derived from the generated registry, so the
 * test keeps passing — and keeps testing something — as modules come and go. If it
 * ever needs a module-specific branch, the contract has a hole and that is the finding.
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest
 * ```
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlugAndPlayInstrumentedTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var destinations: MutableDestinationRegistry

    private val modules by lazy { GeneratedModuleRegistry.factories.values.map { it().manifest } }

    @Before
    fun setUp() = hilt.inject()

    @Test
    fun theShellStartsAndShowsEveryDiscoveredModule() {
        awaitHome()

        assertThat(modules).isNotEmpty()
        modules.forEach { manifest ->
            compose.onNode(tileFor(manifest.displayName.default)).assertIsDisplayed()
        }
    }

    @Test
    fun tappingAModuleRendersThatModulesOwnScreen() {
        val manifest = modules.first()
        awaitHome()

        compose.onAllNodes(tileFor(manifest.displayName.default)).onFirst().performClick()

        // The module's code has now run: it registered destinations, and the one its
        // manifest advertises as the entry point resolves. That is the whole contract
        // — discover, load, initialise, register — observed from outside the module.
        compose.waitUntil(TIMEOUT_MS) { destinations.resolve(manifest.entryRoute) != null }

        val resolved = requireNotNull(destinations.resolve(manifest.entryRoute))
        assertThat(resolved.first.owner).isEqualTo(manifest.id)

        // And the Shell has left the home grid, so what is on screen is the module's.
        compose.waitUntil(TIMEOUT_MS) { !isShowing(HOME_TITLE) }
    }

    @Test
    fun aRouteNoModuleOwnsLeavesTheShellRunning() {
        // The contained-failure property of QA-6, driven through the Router rather
        // than by crashing a module — an uncaught crash would take the
        // instrumentation process with it and prove nothing about the Shell.
        awaitHome()

        compose.activity.runOnUiThread {
            compose.activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("omnideck://nosuchmodule/home"))
                    .setPackage(compose.activity.packageName),
            )
        }

        compose.waitForIdle()
        assertThat(compose.activity.isFinishing).isFalse()
    }

    private fun awaitHome() = compose.waitUntil(TIMEOUT_MS) { isShowing(HOME_TITLE) }

    private fun isShowing(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** ModuleTile collapses its children into one description containing the title. */
    private fun tileFor(title: String) = hasContentDescription(title, substring = true)

    private companion object {
        const val HOME_TITLE = "OmniDeck"

        /** Generous: a cold start plus a module's first initialisation on a slow emulator. */
        const val TIMEOUT_MS = 15_000L
    }
}
