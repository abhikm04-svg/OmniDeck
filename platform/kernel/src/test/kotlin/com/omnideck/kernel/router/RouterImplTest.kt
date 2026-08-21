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
import com.omnideck.sdk.capability.NavResultValue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Architecture §10.1. The behaviour worth protecting is that a caller writes one line
 * regardless of whether the target module is active, installed or not present at all —
 * the Router runs acquisition and continues to the destination. Every test here is
 * really asking "does the caller still get a sensible NavResult?".
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
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
                    installFlow = flowOf(
                        InstallProgress.Failed(code = -6, message = "network unreachable", retryable = true),
                    ),
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

    @Test
    fun `a result set by the destination reaches the waiter`() = runTest {
        val h = Harness()
        h.fixture.manager.discover()
        h.fixture.manager.activate(moduleId())

        val results = mutableListOf<NavResultValue<String>>()
        val waiter = launch {
            h.router.navigateForResult(notesRoute, String::class.java).collect { results += it }
        }
        runCurrent()

        // The destination learns its correlation id from the route it was given.
        val delivered = h.sink.navigated.single().correlationId!!
        h.router.setResult(delivered, "picked")
        runCurrent()
        waiter.join()

        assertThat(results).containsExactly(NavResultValue.Success("picked"))
    }

    @Test
    fun `the correlation id travels in the route so it survives process death`() = runTest {
        // In the query string, not in memory: the back stack is what Android saves
        // and restores, so an in-memory-only id would not survive.
        val h = Harness()
        h.fixture.manager.discover()
        h.fixture.manager.activate(moduleId())

        val waiter = launch { h.router.navigateForResult(notesRoute, String::class.java).collect { } }
        runCurrent()

        val navigated = h.sink.navigated.single()
        assertThat(navigated.correlationId).isNotNull()
        assertThat(navigated.query[Route.CORRELATION_KEY]).isEqualTo(navigated.correlationId!!.value)
        waiter.cancel()
    }

    @Test
    fun `nothing navigates until the flow is collected`() = runTest {
        // Cold by design: building a flow and discarding it must not move the user.
        val h = Harness()
        h.fixture.manager.discover()

        h.router.navigateForResult(notesRoute, String::class.java)
        runCurrent()

        assertThat(h.sink.navigated).isEmpty()
    }

    @Test
    fun `a result of the wrong type fails rather than crashing the collector`() = runTest {
        val h = Harness()
        h.fixture.manager.discover()
        h.fixture.manager.activate(moduleId())

        val results = mutableListOf<NavResultValue<String>>()
        val waiter = launch {
            h.router.navigateForResult(notesRoute, String::class.java).collect { results += it }
        }
        runCurrent()

        h.router.setResult(h.sink.navigated.single().correlationId!!, 42)
        runCurrent()
        waiter.join()

        assertThat(results.single()).isInstanceOf(NavResultValue.Failed::class.java)
        assertThat((results.single() as NavResultValue.Failed).reason).contains("type mismatch")
    }

    @Test
    fun `an unreachable destination fails instead of suspending forever`() = runTest {
        // No result is coming, so leaving the caller awaiting one would hang the flow
        // it is driving — a spinner that never resolves.
        val h = Harness()
        h.fixture.manager.discover()
        h.fixture.manager.quarantine(moduleId(), "Disabled by the OmniDeck team.")

        val results = mutableListOf<NavResultValue<String>>()
        val waiter = launch {
            h.router.navigateForResult(notesRoute, String::class.java).collect { results += it }
        }
        runCurrent()
        waiter.join()

        assertThat((results.single() as NavResultValue.Failed).reason)
            .isEqualTo("Disabled by the OmniDeck team.")
    }

    @Test
    fun `an unhandled route fails the waiter`() = runTest {
        val h = Harness()
        h.fixture.manager.discover()

        val results = mutableListOf<NavResultValue<String>>()
        val waiter = launch {
            h.router.navigateForResult(Route("omnideck://ghost/home"), String::class.java)
                .collect { results += it }
        }
        runCurrent()
        waiter.join()

        assertThat(results.single()).isInstanceOf(NavResultValue.Failed::class.java)
    }

    @Test
    fun `a result for one waiter is not delivered to another`() = runTest {
        // Two concurrent round trips must not cross-talk.
        val h = Harness()
        h.fixture.manager.discover()
        h.fixture.manager.activate(moduleId())

        val first = mutableListOf<NavResultValue<String>>()
        val second = mutableListOf<NavResultValue<String>>()
        val a = launch { h.router.navigateForResult(notesRoute, String::class.java).collect { first += it } }
        runCurrent()
        val b = launch { h.router.navigateForResult(notesRoute, String::class.java).collect { second += it } }
        runCurrent()

        val secondId = h.sink.navigated[1].correlationId!!
        h.router.setResult(secondId, "for-second")
        runCurrent()

        assertThat(second).containsExactly(NavResultValue.Success("for-second"))
        assertThat(first).isEmpty()
        a.cancel()
        b.join()
    }

    @Test
    fun `setResult for an unknown correlation id is a no-op rather than a crash`() {
        // A late result after its waiter has gone — expected around process death.
        val h = Harness()

        h.router.setResult(CorrelationId("never-issued"), "value")
    }

    @Test
    fun `only the first result is delivered`() = runTest {
        val h = Harness()
        h.fixture.manager.discover()
        h.fixture.manager.activate(moduleId())

        val results = mutableListOf<NavResultValue<String>>()
        val waiter = launch {
            h.router.navigateForResult(notesRoute, String::class.java).collect { results += it }
        }
        runCurrent()

        val id = h.sink.navigated.single().correlationId!!
        h.router.setResult(id, "first")
        runCurrent()
        h.router.setResult(id, "second")
        runCurrent()
        waiter.join()

        assertThat(results).containsExactly(NavResultValue.Success("first"))
    }
}
