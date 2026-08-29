package com.omnideck.shell

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.omnideck.generated.GeneratedModuleRegistry
import com.omnideck.kernel.services.InMemoryFeatureFlagService
import com.omnideck.sdk.capability.FeatureFlagService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * OD-319 — "killing the module process" demonstrated as what it actually is on this
 * delivery mix.
 *
 * Bundled and split modules share the Shell's process (architecture.md §7.2); there
 * is no process boundary to sever until `processIsolation = true` modules exist
 * (§12.6, Phase 6) or a satellite is out-of-process by construction (Phase 5). What
 * QA-6 ("a crashing module must not crash the Shell") and QA-9 ("< 5 min to disable a
 * misbehaving module") actually buy today is containment: a module the server kills
 * stops being reachable, without the Shell noticing anything happened to it.
 *
 * This drives that through the real path — [FeatureFlagService], the same interface
 * production code reads, not a debug-only backdoor — rather than crashing a module,
 * which would take the instrumentation process down with it and prove nothing about
 * the Shell (see [PlugAndPlayInstrumentedTest.aRouteNoModuleOwnsLeavesTheShellRunning],
 * the sibling case for a route no module owns at all).
 *
 * ```
 * ./gradlew :app:pixel6Api34DebugAndroidTest
 * ```
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class QuarantineContainmentInstrumentedTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var flags: FeatureFlagService

    private val modules by lazy { GeneratedModuleRegistry.factories.values.map { it().manifest } }

    @Before
    fun setUp() {
        // `WorkManagerInitializer` is deliberately removed from the manifest (a Hilt
        // worker factory installs its own), which an ordinary test never notices —
        // until quarantine tries to cancel the module's scheduled work and
        // `WorkManager.getInstance()` throws "not initialized properly" instead.
        // Not needed by the other instrumented tests in this module only because
        // none of them reach a code path that calls into WorkManager at all.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext(),
            Configuration.Builder().build(),
        )
        hilt.inject()
    }

    @Test
    fun aKillSwitchedModuleIsContainedWithoutTakingTheShellDown() {
        val manifest = modules.first()
        awaitHome()

        // `ModuleLifecycleManager.watchKillSwitches()` is a live collector started
        // right after discovery (ShellViewModel.init) — QA-9 exists precisely so a
        // module already on screen does not have to be revisited to be disabled.
        // Flipping the flag here, with nothing else in between, is the whole test.
        (flags as InMemoryFeatureFlagService).put("module.${manifest.id.value}.enabled", false)

        // ModuleTile.kt: `enabled = state !is TileState.Quarantined`, and the
        // content description carries the reason the same screen reader announces —
        // this is the accessibility surface a user actually gets, not an internal
        // state enum the test would otherwise have to reach past the UI to see.
        val quarantinedTile = hasContentDescription("Unavailable:", substring = true)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(quarantinedTile).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(quarantinedTile).assertIsNotEnabled()

        // Containment, not a lucky survival: the Shell process is still the one
        // running, still on Home, and still showing every module that was not
        // touched — which is the property "kill the module" is standing in for.
        assertThat(compose.activity.isFinishing).isFalse()
        modules.drop(1).forEach { other ->
            compose.onAllNodes(hasContentDescription(other.displayName.default, substring = true))
                .fetchSemanticsNodes()
                .also { assertThat(it).isNotEmpty() }
        }
    }

    private fun awaitHome() = compose.waitUntil(TIMEOUT_MS) {
        compose.onAllNodes(hasContentDescription("Modules")).fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        /** Generous: a cold start plus discovery on a slow emulator. */
        const val TIMEOUT_MS = 15_000L
    }
}
