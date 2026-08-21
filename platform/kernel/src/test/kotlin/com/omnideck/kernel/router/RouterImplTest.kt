package com.omnideck.kernel.router

import com.google.common.truth.Truth.assertThat
import com.omnideck.kernel.lifecycle.LifecycleFixture
import com.omnideck.kernel.lifecycle.ScriptedModule
import com.omnideck.kernel.lifecycle.ScriptedProvider
import com.omnideck.kernel.lifecycle.moduleId
import com.omnideck.sdk.CorrelationId
import com.omnideck.sdk.InstallProgress
import com.omnideck.sdk.ModuleInitResult
import com.omnideck.sdk.Route
import com.omnideck.sdk.SuspendReason
import com.omnideck.sdk.capability.NavResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Architecture §10.1. The behaviour worth protecting is that a caller writes one line
 * regardless of whether the target module is active, installed or not present at all —
 * the Router runs acquisition and continues to the destination. Every test here is
 * really asking "does the caller still get a sensible NavResult?".
 */
class RouterImplTest {

    private class RecordingSink : NavigationCommandSink {
        val navigated = mutableListOf<Route>()
        var backCount = 0
        var backReturns = true

        override fun navigate(route: Route) {
            navigated += route
        }

        override fun back(): Boolean {
            backCount++
            return backReturns
        }
    }

    private class Harness(
        val fixture: LifecycleFixture = LifecycleFixture(),
        val sink: RecordingSink = RecordingSink(),
    ) {
        val router = RouterImpl(
            destinations = fixture.destinations,
            lifecycle = fixture.manager,
            telemetry = fixture.telemetry,
            sink = sink,
        )
    }

    private val notesRoute = Route("omnideck://notes/home")

    // -- resolution ---------------------------------------------------------

    @Test
    fun `an unowned route is unhandled rather than an error`() = runTest {
        val h = Harness()
        h.fixture.manager.discover()

        val result = h.router.navigate(Route("omnideck://nosuchmodule/home"))

        assertThat(result).isInstanceOf(NavResult.Unhandled::class.java)
        assertThat(h.sink.navigated).isEmpty()
        assertThat(h.fixture.telemetry.eventNames()).contains("nav_unhandled")
    }

    @Test
    fun `ownership resolves from the route host before the module is loaded`() = runTest {
        // Without this an uninstalled module's deep links would be dead, which would
        // break the acquisition flow the Router exists to provide.
        val h = Harness()
        h.fixture.manager.discover()

        assertThat(h.router.canHandle(notesRoute)).isTrue()
        assertThat(h.router.canHandle(Route("omnideck://ghost/home"))).isFalse()
    }

    @Test
    fun `navigation is breadcrumbed for crash context`() = runTest {
        val h = Harness()
        h.fixture.manager.discover()

        h.router.navigate(notesRoute)

        assertThat(h.fixture.telemetry.breadcrumbs).contains("navigate")
    }

    // -- states that navigate directly -------------------------------------

    @Test
    fun `an active module navigates immediately`() = runTest {
        val h = Harness()
        h.fixture.manager.discover()
        h.fixture.manager.activate(moduleId())

        val result = h.router.navigate(notesRoute)

        assertThat(result).isEqualTo(NavResult.Navigated(notesRoute))
        assertThat(h.sink.navigated).containsExactly(notesRoute)
    }

    @Test
    fun `an installed module is activated on the way through`() = runTest {
        val module = ScriptedModule()
        val h = Harness(LifecycleFixture(provider = ScriptedProvider(module = module)))
        h.fixture.manager.discover()

        val result = h.router.navigate(notesRoute)

        assertThat(result).isEqualTo(NavResult.Navigated(notesRoute))
        assertThat(module.initializeCount).isEqualTo(1)
    }

    @Test
    fun `a suspended module is reactivated`() = runTest {
        val h = Harness()
        h.fixture.manager.discover()
        h.fixture.manager.activate(moduleId())
        h.fixture.manager.suspendModule(moduleId(), SuspendReason.MEMORY_PRESSURE)

        val result = h.router.navigate(notesRoute)

        assertThat(result).isEqualTo(NavResult.Navigated(notesRoute))
    }

    @Test
    fun `a module that fails to activate reports unavailable with its reason`() = runTest {
        val h = Harness(
            LifecycleFixture(
                provider = ScriptedProvider(
                    module = ScriptedModule(
                        initResult = ModuleInitResult.Failed(IllegalStateException("no config"), retryable = false),
                    ),
                ),
            ),
        )
        h.fixture.manager.discover()

        val result = h.router.navigate(notesRoute)

        assertThat(result).isInstanceOf(NavResult.Unavailable::class.java)
        assertThat((result as NavResult.Unavailable).reason).isEqualTo("no config")
        assertThat(h.sink.navigated).isEmpty()
    }

    // -- acquisition --------------------------------------------------------

    @Test
    fun `an uninstalled module is downloaded then navigated to`() = runTest {
        // The whole "one-click shop": the caller wrote one navigate() line.
        val h = Harness(
            LifecycleFixture(
                provider = ScriptedProvider(installed = false, installFlow = flowOf(InstallProgress.Installed)),
            ),
        )
        h.fixture.manager.discover()
        // Installation makes it present for the activation that follows.
        h.fixture.provider.installed = true

        val result = h.router.navigate(notesRoute)

        assertThat(result).isInstanceOf(NavResult.NavigatedAfterInstall::class.java)
        assertThat(h.sink.navigated).containsExactly(notesRoute)
        assertThat(h.fixture.telemetry.eventNames())
            .containsAtLeast("module_acquisition_started", "module_acquisition_completed")
    }

    @Test
    fun `a failed download aborts acquisition with the failure message`() = runTest {
        val h = Harness(
            LifecycleFixture(
                provider = ScriptedProvider(
                    installed = false,
                    installFlow = flowOf(InstallProgress.Failed(code = -6, message = "network unreachable", retryable = true)),
                ),
            ),
        )
        h.fixture.manager.discover()

        val result = h.router.navigate(notesRoute)

        assertThat(result).isInstanceOf(NavResult.AcquisitionAborted::class.java)
        assertThat((result as NavResult.AcquisitionAborted).reason).isEqualTo("network unreachable")
        assertThat(h.sink.navigated).isEmpty()
    }

    @Test
    fun `a cancelled download aborts acquisition`() = runTest {
        val h = Harness(
            LifecycleFixture(
                provider = ScriptedProvider(installed = false, installFlow = flowOf(InstallProgress.Canceled)),
            ),
        )
        h.fixture.manager.discover()

        val result = h.router.navigate(notesRoute)

        assertThat(result).isInstanceOf(NavResult.AcquisitionAborted::class.java)
    }

    @Test
    fun `acquisition ignores intermediate progress and acts on the terminal event`() = runTest {
        val h = Harness(
            LifecycleFixture(
                provider = ScriptedProvider(
                    installed = false,
                    installFlow = flowOf(
                        InstallProgress.Downloading(bytesDownloaded = 250, totalBytes = 1_000),
                        InstallProgress.Downloading(bytesDownloaded = 900, totalBytes = 1_000),
                        InstallProgress.Installed,
                    ),
                ),
            ),
        )
        h.fixture.manager.discover()
        h.fixture.provider.installed = true

        assertThat(h.router.navigate(notesRoute)).isInstanceOf(NavResult.NavigatedAfterInstall::class.java)
    }

    // -- unavailable states -------------------------------------------------

    @Test
    fun `a quarantined module reports its quarantine reason`() = runTest {
        val h = Harness()
        h.fixture.manager.discover()
        h.fixture.manager.quarantine(moduleId(), "Disabled by the OmniDeck team.")

        val result = h.router.navigate(notesRoute)

        assertThat(result).isInstanceOf(NavResult.Unavailable::class.java)
        assertThat((result as NavResult.Unavailable).reason).isEqualTo("Disabled by the OmniDeck team.")
    }

    @Test
    fun `a module with no provider reports that it could not be loaded`() = runTest {
        val h = Harness(
            LifecycleFixture(
                descriptors = listOf(
                    com.omnideck.kernel.lifecycle.descriptor(delivery = com.omnideck.sdk.DeliveryKind.SATELLITE),
                ),
                provider = ScriptedProvider(handles = com.omnideck.sdk.DeliveryKind.BUNDLED),
            ),
        )
        h.fixture.manager.discover()

        val result = h.router.navigate(notesRoute)

        assertThat(result).isInstanceOf(NavResult.Unavailable::class.java)
        assertThat((result as NavResult.Unavailable).reason).contains("could not be loaded")
    }

    // -- back ---------------------------------------------------------------

    @Test
    fun `back delegates to the sink and passes its answer through`() {
        val h = Harness()

        assertThat(h.router.back()).isTrue()
        h.sink.backReturns = false
        assertThat(h.router.back()).isFalse()
        assertThat(h.sink.backCount).isEqualTo(2)
    }

    // -- navigateForResult --------------------------------------------------
    //
    // Deliberately thin, and the gap is the point.
    //
    // `navigateForResult` mints a CorrelationId internally, stores it in `pending`,
    // and returns a flow filtered on it — but it never calls `sink.navigate(route)`
    // and never surfaces the id to anyone. The destination therefore has no way to
    // learn which id to pass back to `setResult`, so no result can ever reach the
    // waiter. The round trip is unimplemented, not merely untested; OD-205 ("with
    // process-death-safe correlation ids") is the ticket that finishes it.
    //
    // Asserting a passing round trip here would need the test to reach past that gap,
    // and would then keep passing once it is closed for the wrong reason. These two
    // pin what genuinely holds today instead.

    @Test
    fun `setResult for an unknown correlation id is a no-op rather than a crash`() {
        // A late result after its waiter has gone — expected during process death.
        val h = Harness()

        h.router.setResult(CorrelationId("never-issued"), "value")
    }

    @Test
    fun `navigateForResult does not navigate yet`() {
        // Pins the current limitation so closing OD-205 has to update this test,
        // rather than the gap being rediscovered from a bug report.
        val h = Harness()

        h.router.navigateForResult(notesRoute, String::class.java)

        assertThat(h.sink.navigated).isEmpty()
    }
}
