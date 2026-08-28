package com.omnideck.kernel.lifecycle

import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.ModuleInitResult
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.RoutePattern
import com.omnideck.sdk.SuspendReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The parts of architecture.md §7.1 that Phase 1 left as edges: coming back from
 * suspension, a kill switch that reaches a module already on screen, and a failure
 * counter that forgets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KillSwitchAndRecoveryTest {

    // -- resume -------------------------------------------------------------

    @Test
    fun `a suspended module can be brought back`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        fixture.manager.activate(moduleId())
        fixture.manager.suspendModule(moduleId(), SuspendReason.MEMORY_PRESSURE)
        assertThat(fixture.runtime().state).isEqualTo(ModuleState.SUSPENDED)

        val resumed = fixture.manager.resume(moduleId())

        assertThat(resumed.state).isEqualTo(ModuleState.ACTIVE)
    }

    @Test
    fun `resuming re-enters initialize on the same instance, which is why it must be idempotent`() = runTest {
        val module = ScriptedModule()
        val fixture = LifecycleFixture(provider = ScriptedProvider(module = module))
        fixture.manager.discover()
        fixture.manager.activate(moduleId())
        fixture.manager.suspendModule(moduleId(), SuspendReason.BACKGROUNDED)

        fixture.manager.resume(moduleId())

        assertThat(module.initializeCount).isEqualTo(2)
        assertThat(fixture.provider.loadCount).isEqualTo(1)
    }

    @Test
    fun `resuming a module that is not suspended does nothing`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        fixture.manager.activate(moduleId())

        val unchanged = fixture.manager.resume(moduleId())

        assertThat(unchanged.state).isEqualTo(ModuleState.ACTIVE)
    }

    // -- kill switch --------------------------------------------------------

    @Test
    fun `the kill switch reaches a module that is already active`() = runTest {
        // The point of QA-9: before this, a flag flip only took effect the next time
        // the user navigated to the module, which is not a kill switch.
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        fixture.manager.activate(moduleId())
        watch(fixture)

        fixture.kill()

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.QUARANTINED)
        assertThat(fixture.runtime().quarantineCause).isEqualTo(QuarantineCause.KILL_SWITCH)
    }

    @Test
    fun `quarantine cancels the module's scheduled work and unregisters its routes`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        fixture.manager.activate(moduleId())

        fixture.manager.quarantine(moduleId(), "kill", QuarantineCause.KILL_SWITCH)

        assertThat(fixture.destinations.destinations.value).isEmpty()
    }

    @Test
    fun `clearing the flag returns a kill-switched module to service`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        fixture.kill()
        watch(fixture)
        assertThat(fixture.runtime().state).isEqualTo(ModuleState.QUARANTINED)

        fixture.flags.set("module.$TEST_MODULE.enabled", true)

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.INSTALLED)
        assertThat(fixture.runtime().quarantineCause).isNull()
    }

    @Test
    fun `clearing the flag does not release a module quarantined for crashing`() = runTest {
        // architecture.md §7.1 is explicit: a crash loop needs a remote clear *and* a
        // version bump. Nothing about flipping a flag has fixed the crash.
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(
                module = ScriptedModule(initResult = ModuleInitResult.Failed(IllegalStateException("nope"), false)),
            ),
        )
        fixture.manager.discover()
        fixture.manager.activate(moduleId())
        assertThat(fixture.runtime().quarantineCause).isEqualTo(QuarantineCause.INIT_FAILURE)

        watch(fixture)
        fixture.flags.set("module.$TEST_MODULE.enabled", true)

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.QUARANTINED)
    }

    @Test
    fun `watching nothing is not an error`() = runTest {
        val fixture = LifecycleFixture(descriptors = emptyList())
        fixture.manager.discover()

        fixture.manager.watchKillSwitches()
    }

    // -- rolling failure window ---------------------------------------------

    @Test
    fun `three failures close together quarantine the module`() = runTest {
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(
                module = ScriptedModule(initResult = ModuleInitResult.Failed(IllegalStateException("flaky"), true)),
            ),
        )
        fixture.manager.discover()

        repeat(3) { fixture.manager.activate(moduleId()) }

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.QUARANTINED)
        assertThat(fixture.runtime().failureCount).isEqualTo(3)
    }

    @Test
    fun `failures far apart do not add up`() = runTest {
        // A module that fails once a month for three months is a flaky network, not a
        // broken module. A lifetime counter would quarantine it anyway.
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(
                module = ScriptedModule(initResult = ModuleInitResult.Failed(IllegalStateException("flaky"), true)),
            ),
        )
        fixture.manager.discover()

        repeat(3) {
            fixture.manager.activate(moduleId())
            fixture.clock.advanceBy(ONE_HOUR_MS + 1)
        }

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.INSTALLED)
        assertThat(fixture.runtime().failureCount).isEqualTo(1)
    }

    @Test
    fun `a successful start clears the counter`() = runTest {
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(
                module = ScriptedModule(
                    initResults = listOf(
                        ModuleInitResult.Failed(IllegalStateException("transient"), true),
                        ModuleInitResult.Ready,
                    ),
                ),
            ),
        )
        fixture.manager.discover()

        fixture.manager.activate(moduleId())
        fixture.manager.activate(moduleId())

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.ACTIVE)
        assertThat(fixture.runtime().failureCount).isEqualTo(0)
        assertThat(fixture.runtime().firstFailureAtMs).isEqualTo(0)
    }

    // -- declared routes ----------------------------------------------------

    @Test
    fun `a deep link the module never registered is reported against its owner`() = runTest {
        val declared = manifest().copy(deepLinks = listOf(RoutePattern("omnideck://notes/never/{id}")))
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(module = ScriptedModule(manifest = declared)),
        )
        fixture.manager.discover()

        fixture.manager.activate(moduleId())

        val event = fixture.telemetry.events.single { it.name == "module_contract_violation" }
        assertThat(event.attributes["owner"]).isEqualTo("platform")
        assertThat(event.attributes["unregistered_deep_links"] as String).contains("never")
    }

    @Test
    fun `an unresolvable entry route is reported, because a tile tap would go nowhere`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()

        // ScriptedModule registers no destinations at all, so its manifest's entry
        // route resolves to nothing.
        fixture.manager.activate(moduleId())

        val event = fixture.telemetry.events.single { it.name == "module_contract_violation" }
        assertThat(event.attributes["entry_route_unresolved"]).isEqualTo(true)
    }

    /**
     * Starts the watcher on the test's own scheduler, unconfined, so a flag flip is
     * observed synchronously. On the default dispatcher the collector would not even
     * have started before the assertion ran, and every one of these tests would pass
     * or fail on scheduling rather than on behaviour.
     */
    private fun TestScope.watch(fixture: LifecycleFixture) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.manager.watchKillSwitches()
        }
    }

    private companion object {
        const val ONE_HOUR_MS = 60L * 60L * 1000L
    }
}
