package com.omnideck.kernel.router

import com.google.common.truth.Truth.assertThat
import com.omnideck.kernel.lifecycle.LifecycleFixture
import com.omnideck.kernel.lifecycle.moduleId
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.Route
import com.omnideck.sdk.capability.NavResult
import com.omnideck.sdk.capability.NavResultValue
import com.omnideck.testing.FakeTelemetryService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * OD-205's missing half, and OD-207's route ownership.
 *
 * The abandonment case is the one that matters: without it a caller of
 * `navigateForResult` waits for a result from a screen the user has already
 * dismissed, and whatever that flow was gating never happens — for the life of the
 * process, with nothing in a log to say why.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationResultTest {

    private val telemetry = FakeTelemetryService()
    private val destinations = MutableDestinationRegistry()
    private val sink = RecordingSink()

    @Test
    fun `abandoning a pending navigation completes the caller as cancelled`() = runTest {
        val router = router(activated = true)
        val results = mutableListOf<NavResultValue<String>>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            router.navigateForResult(Route("omnideck://notes/pick"), String::class.java)
                .collect { results += it }
        }

        val correlationId = requireNotNull(sink.routes.single().correlationId)
        router.abandon(correlationId)

        assertThat(results).containsExactly(NavResultValue.Cancelled)
    }

    @Test
    fun `a result still wins when one is actually produced`() = runTest {
        val router = router(activated = true)
        val results = mutableListOf<NavResultValue<String>>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            router.navigateForResult(Route("omnideck://notes/pick"), String::class.java)
                .collect { results += it }
        }

        val correlationId = requireNotNull(sink.routes.single().correlationId)
        router.setResult(correlationId, "chosen")

        assertThat(results).containsExactly(NavResultValue.Success("chosen"))
    }

    @Test
    fun `abandoning an id nobody is waiting on does nothing`() = runTest {
        val router = router(activated = true)

        // A restored back stack replays route strings, correlation ids included. If a
        // stale one could complete a flow, a returning user would cancel a navigation
        // that had already finished.
        router.abandon(com.omnideck.sdk.CorrelationId("never-issued"))

        assertThat(telemetry.eventNames()).doesNotContain("nav_result_abandoned")
    }

    @Test
    fun `abandonment is telemetered, because a silent cancel is unexplainable`() = runTest {
        val router = router(activated = true)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            router.navigateForResult(Route("omnideck://notes/pick"), String::class.java).first()
        }
        router.abandon(requireNotNull(sink.routes.single().correlationId))

        assertThat(telemetry.eventNames()).contains("nav_result_abandoned")
    }

    @Test
    fun `a Shell-owned route navigates directly, with no lifecycle behind it`() = runTest {
        // Settings and the Privacy Centre live in the same route table as modules but
        // have nothing to install, gate or quarantine. Before this they fell through
        // to the acquisition flow and failed on an unknown module id.
        val router = router(activated = false)
        val shell = ModuleId("com.omnideck.shell")
        destinations.scopedTo(shell).destination("omnideck://shell/settings") { }

        val result = router.navigate(Route("omnideck://shell/settings"))

        assertThat(result).isInstanceOf(NavResult.Navigated::class.java)
        assertThat(sink.routes.map { it.uri }).containsExactly("omnideck://shell/settings")
    }

    @Test
    fun `a route belonging to nothing at all is still unhandled`() = runTest {
        val router = router(activated = false)

        assertThat(router.navigate(Route("omnideck://nobody/home")))
            .isInstanceOf(NavResult.Unhandled::class.java)
    }

    private suspend fun router(activated: Boolean): RouterImpl {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        if (activated) {
            fixture.manager.activate(moduleId())
            destinations.scopedTo(moduleId()).destination("omnideck://notes/pick") { }
        }
        return RouterImpl(
            destinations = destinations,
            lifecycle = fixture.manager,
            telemetry = telemetry,
            sink = sink,
        )
    }

    private class RecordingSink : NavigationCommandSink {
        val routes = mutableListOf<Route>()
        override fun navigate(route: Route) {
            routes += route
        }
        override fun back(): Boolean = true
    }
}

/**
 * Degraded banners (OD-208). The registry stores them per module because a module's
 * own wording beats a generic one, and drops them on removal for the same reason it
 * drops destinations: a quarantined module must leave nothing renderable behind.
 */
class DegradedBannerTest {

    private val registry = MutableDestinationRegistry()
    private val notes = ModuleId("com.omnideck.notes")

    @Test
    fun `a module can register its own advisory banner`() {
        registry.scopedTo(notes).degradedFallback { }

        assertThat(registry.degradedBanners).isNotNull()
        assertThat(registry.degradedBanners.value.keys).containsExactly(notes)
    }

    @Test
    fun `a module that registers none simply has none, and the Shell supplies the default`() {
        registry.scopedTo(notes).destination("omnideck://notes/home") { }

        assertThat(registry.degradedBanners.value).isEmpty()
    }

    @Test
    fun `removing a module removes its banner along with its routes`() {
        val scoped = registry.scopedTo(notes)
        scoped.destination("omnideck://notes/home") { }
        scoped.degradedFallback { }

        registry.removeAll(notes)

        assertThat(registry.destinations.value).isEmpty()
        assertThat(registry.degradedBanners.value).isEmpty()
    }
}
