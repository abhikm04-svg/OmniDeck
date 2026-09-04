package com.omnideck.kernel.lifecycle

import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.ModuleInitResult
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.SemVerRange
import com.omnideck.sdk.SuspendReason
import com.omnideck.sdk.capability.PlatformEvent
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The state machine of architecture.md §7.1.
 *
 * This class is the reason a bad module cannot take the Shell down, so the tests are
 * organised around the three safety properties it claims — compatibility gating,
 * capability gating and quarantine — rather than around its method list.
 */
class ModuleLifecycleManagerTest {

    // -- discovery ----------------------------------------------------------

    @Test
    fun `discovery seeds installed modules without running module code`() = runTest {
        val module = ScriptedModule()
        val fixture = LifecycleFixture(provider = ScriptedProvider(module = module))

        fixture.manager.discover()

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.INSTALLED)
        // The promise that discovery is cheap: nothing is loaded or initialised.
        assertThat(fixture.provider.loadCount).isEqualTo(0)
        assertThat(module.initializeCount).isEqualTo(0)
    }

    @Test
    fun `an uninstalled module is advertised rather than installed`() = runTest {
        val fixture = LifecycleFixture(provider = ScriptedProvider(installed = false))

        fixture.manager.discover()

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.ADVERTISED)
    }

    @Test
    fun `a module with no matching provider fails with a reason`() = runTest {
        // Descriptor says SATELLITE, the only provider handles BUNDLED.
        val fixture = LifecycleFixture(
            descriptors = listOf(descriptor(delivery = DeliveryKind.SATELLITE)),
            provider = ScriptedProvider(handles = DeliveryKind.BUNDLED),
        )

        fixture.manager.discover()

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.FAILED)
        assertThat(fixture.runtime().reason).contains("No provider")
    }

    @Test
    fun `a killed module is quarantined at discovery, before it can load`() = runTest {
        val fixture = LifecycleFixture()
        fixture.kill()

        fixture.manager.discover()

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.QUARANTINED)
        assertThat(fixture.provider.loadCount).isEqualTo(0)
    }

    @Test
    fun `discovery is telemetered with the module count`() = runTest {
        val fixture = LifecycleFixture()

        fixture.manager.discover()

        assertThat(fixture.telemetry.eventNames()).contains("module_discovery")
    }

    // -- activation ---------------------------------------------------------

    @Test
    fun `activation loads, initialises and registers a healthy module`() = runTest {
        val module = ScriptedModule()
        val fixture = LifecycleFixture(provider = ScriptedProvider(module = module))
        fixture.manager.discover()

        val runtime = fixture.manager.activate(moduleId())

        assertThat(runtime.state).isEqualTo(ModuleState.ACTIVE)
        assertThat(module.initializeCount).isEqualTo(1)
        assertThat(module.destinationsRegistered).isEqualTo(1)
        assertThat(module.capabilitiesRegistered).isEqualTo(1)
    }

    @Test
    fun `activation is idempotent and does not re-initialise an active module`() = runTest {
        // The UI calls this on every navigation, so a second call must be free.
        val module = ScriptedModule()
        val fixture = LifecycleFixture(provider = ScriptedProvider(module = module))
        fixture.manager.discover()

        fixture.manager.activate(moduleId())
        fixture.manager.activate(moduleId())

        assertThat(module.initializeCount).isEqualTo(1)
    }

    @Test
    fun `a degraded module is usable and carries its reason`() = runTest {
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(
                module = ScriptedModule(initResult = ModuleInitResult.Degraded("offline")),
            ),
        )
        fixture.manager.discover()

        val runtime = fixture.manager.activate(moduleId())

        assertThat(runtime.state).isEqualTo(ModuleState.DEGRADED)
        assertThat(runtime.state.isUsable).isTrue()
        assertThat(runtime.reason).isEqualTo("offline")
    }

    @Test
    fun `activating an uninstalled module reports it as advertised`() = runTest {
        val fixture = LifecycleFixture(provider = ScriptedProvider(installed = false))
        fixture.manager.discover()

        val runtime = fixture.manager.activate(moduleId())

        assertThat(runtime.state).isEqualTo(ModuleState.ADVERTISED)
    }

    @Test
    fun `activation publishes a state change so the Shell can react`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()

        fixture.manager.activate(moduleId())

        val published = fixture.events.published.filterIsInstance<PlatformEvent.ModuleStateChanged>()
        assertThat(published.map { it.state }).contains(ModuleState.ACTIVE)
    }

    // -- compatibility gating ----------------------------------------------

    @Test
    fun `a module needing a newer SDK is gated, not initialised`() = runTest {
        // The point of the gate: version skew must surface as a clear message,
        // not a NoSuchMethodError deep inside a user journey.
        val module = ScriptedModule(
            manifest = manifest(sdkRange = SemVerRange(SemVer(2, 0, 0), null)),
        )
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(module = module),
            hostSdk = SemVer(1, 0, 0),
        )
        fixture.manager.discover()

        val runtime = fixture.manager.activate(moduleId())

        assertThat(runtime.state).isEqualTo(ModuleState.GATED)
        assertThat(runtime.reason).contains("newer version")
        assertThat(module.initializeCount).isEqualTo(0)
    }

    @Test
    fun `a module needing a newer host version code is gated`() = runTest {
        val module = ScriptedModule(manifest = manifest(minHostVersionCode = 99))
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(module = module),
            hostVersionCode = 1,
        )
        fixture.manager.discover()

        assertThat(fixture.manager.activate(moduleId()).state).isEqualTo(ModuleState.GATED)
        assertThat(module.initializeCount).isEqualTo(0)
    }

    // -- capability gating --------------------------------------------------

    @Test
    fun `a module requiring an unavailable capability is gated before initialising`() = runTest {
        val module = ScriptedModule(
            manifest = manifest(required = setOf(CapabilityId("omnideck.nonexistent"))),
        )
        val fixture = LifecycleFixture(provider = ScriptedProvider(module = module))
        fixture.manager.discover()

        val runtime = fixture.manager.activate(moduleId())

        assertThat(runtime.state).isEqualTo(ModuleState.GATED)
        assertThat(runtime.reason).contains("Unavailable capabilities")
        // Fast and loud at load, never lazily at first use.
        assertThat(module.initializeCount).isEqualTo(0)
    }

    @Test
    fun `kernel-provided capabilities satisfy a manifest`() = runTest {
        val module = ScriptedModule(
            manifest = manifest(required = setOf(CapabilityId.STORAGE, CapabilityId.NETWORK)),
        )
        val fixture = LifecycleFixture(provider = ScriptedProvider(module = module))
        fixture.manager.discover()

        assertThat(fixture.manager.activate(moduleId()).state).isEqualTo(ModuleState.ACTIVE)
    }

    // -- quarantine ---------------------------------------------------------

    @Test
    fun `a non-retryable init failure quarantines immediately`() = runTest {
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(
                module = ScriptedModule(
                    initResult = ModuleInitResult.Failed(IllegalStateException("bad config"), retryable = false),
                ),
            ),
        )
        fixture.manager.discover()

        val runtime = fixture.manager.activate(moduleId())

        assertThat(runtime.state).isEqualTo(ModuleState.QUARANTINED)
        assertThat(runtime.reason).isEqualTo("bad config")
    }

    @Test
    fun `a retryable failure stays installed until the third attempt`() = runTest {
        // Three strikes, per architecture.md §7.1 — a transient fault must not
        // permanently disable a module on its first bad day.
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(
                module = ScriptedModule(
                    initResult = ModuleInitResult.Failed(IllegalStateException("flaky"), retryable = true),
                ),
            ),
        )
        fixture.manager.discover()

        assertThat(fixture.manager.activate(moduleId()).state).isEqualTo(ModuleState.INSTALLED)
        assertThat(fixture.manager.activate(moduleId()).state).isEqualTo(ModuleState.INSTALLED)
        assertThat(fixture.manager.activate(moduleId()).state).isEqualTo(ModuleState.QUARANTINED)
        assertThat(fixture.runtime().failureCount).isEqualTo(3)
    }

    @Test
    fun `a module throwing from initialize is contained rather than propagating`() = runTest {
        // Throwing is a contract violation; it must never reach the Shell's composition.
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(
                module = ScriptedModule(throwOnInit = RuntimeException("boom")),
            ),
        )
        fixture.manager.discover()

        val runtime = fixture.manager.activate(moduleId())

        assertThat(runtime.state).isEqualTo(ModuleState.QUARANTINED)
        assertThat(fixture.telemetry.errors.map { it.message }).contains("boom")
    }

    @Test
    fun `a successful activation resets the failure count`() = runTest {
        // Without the reset, a module that fails once early in a session would carry
        // that strike for the rest of it and quarantine on two later, unrelated blips.
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(
                module = ScriptedModule(
                    initResults = listOf(
                        ModuleInitResult.Failed(IllegalStateException("flaky"), retryable = true),
                        ModuleInitResult.Ready,
                    ),
                ),
            ),
        )
        fixture.manager.discover()
        fixture.manager.activate(moduleId())
        assertThat(fixture.runtime().failureCount).isEqualTo(1)

        fixture.manager.activate(moduleId())

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.ACTIVE)
        assertThat(fixture.runtime().failureCount).isEqualTo(0)
    }

    @Test
    fun `a cached module instance is reused across retries`() = runTest {
        // The manager caches instances, so a retry re-enters initialize() on the same
        // object. That is what makes the contract's idempotency requirement load-bearing.
        val module = ScriptedModule(
            initResults = listOf(
                ModuleInitResult.Failed(IllegalStateException("flaky"), retryable = true),
                ModuleInitResult.Ready,
            ),
        )
        val fixture = LifecycleFixture(provider = ScriptedProvider(module = module))
        fixture.manager.discover()

        fixture.manager.activate(moduleId())
        fixture.manager.activate(moduleId())

        assertThat(module.initializeCount).isEqualTo(2)
        assertThat(fixture.provider.loadCount).isEqualTo(1)
    }

    @Test
    fun `quarantine cancels work and strips registrations`() = runTest {
        val module = ScriptedModule()
        val fixture = LifecycleFixture(provider = ScriptedProvider(module = module))
        fixture.manager.discover()
        fixture.manager.activate(moduleId())

        fixture.manager.quarantine(moduleId(), "Disabled by the OmniDeck team.")

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.QUARANTINED)
        assertThat(module.suspensions).contains(SuspendReason.KILL_SWITCH)
        verify { fixture.servicesFactory.cancelWork(moduleId()) }
        assertThat(fixture.destinations.destinations.value).isEmpty()
        assertThat(fixture.capabilities.available()).isEmpty()
    }

    @Test
    fun `the kill switch quarantines on the next activation`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        fixture.manager.activate(moduleId())
        assertThat(fixture.runtime().state).isEqualTo(ModuleState.ACTIVE)

        fixture.kill()
        // isUsable short-circuits activate(), so the switch takes effect once the
        // module leaves the active state — here, via an explicit suspend.
        fixture.manager.suspendModule(moduleId(), SuspendReason.MEMORY_PRESSURE)
        val runtime = fixture.manager.activate(moduleId())

        assertThat(runtime.state).isEqualTo(ModuleState.QUARANTINED)
    }

    @Test
    fun `a quarantined module stays quarantined on activation`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        fixture.manager.quarantine(moduleId(), "kill switch")

        assertThat(fixture.manager.activate(moduleId()).state).isEqualTo(ModuleState.QUARANTINED)
    }

    // -- suspend ------------------------------------------------------------

    @Test
    fun `suspending an active module notifies it and marks it suspended`() = runTest {
        val module = ScriptedModule()
        val fixture = LifecycleFixture(provider = ScriptedProvider(module = module))
        fixture.manager.discover()
        fixture.manager.activate(moduleId())

        fixture.manager.suspendModule(moduleId(), SuspendReason.MEMORY_PRESSURE)

        assertThat(module.suspensions).containsExactly(SuspendReason.MEMORY_PRESSURE)
        assertThat(fixture.runtime().state).isEqualTo(ModuleState.SUSPENDED)
    }

    @Test
    fun `a module throwing from suspend does not break the platform`() = runTest {
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(
                module = ScriptedModule(throwOnSuspend = RuntimeException("suspend boom")),
            ),
        )
        fixture.manager.discover()
        fixture.manager.activate(moduleId())

        fixture.manager.suspendModule(moduleId(), SuspendReason.MEMORY_PRESSURE)

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.SUSPENDED)
        assertThat(fixture.telemetry.errors.map { it.message }).contains("suspend boom")
    }

    @Test
    fun `suspending a non-usable module leaves its state alone`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        fixture.manager.quarantine(moduleId(), "kill switch")

        fixture.manager.suspendModule(moduleId(), SuspendReason.MEMORY_PRESSURE)

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.QUARANTINED)
    }

    // -- purge --------------------------------------------------------------

    @Test
    fun `a full purge erases data, uninstalls and returns the module to advertised`() = runTest {
        // The module's half of the GDPR/DPDP erasure guarantee.
        val module = ScriptedModule()
        val fixture = LifecycleFixture(provider = ScriptedProvider(module = module))
        fixture.manager.discover()
        fixture.manager.activate(moduleId())

        fixture.manager.purge(moduleId(), PurgeScope.ALL)

        assertThat(module.purges).containsExactly(PurgeScope.ALL)
        coVerify { fixture.servicesFactory.purge(moduleId(), PurgeScope.ALL) }
        assertThat(fixture.provider.uninstalled).containsExactly(moduleId())
        assertThat(fixture.runtime().state).isEqualTo(ModuleState.ADVERTISED)
        assertThat(fixture.runtime().manifest).isNull()
        assertThat(fixture.destinations.destinations.value).isEmpty()
    }

    @Test
    fun `a purged split Play has not reclaimed yet is flagged as awaiting cleanup`() = runTest {
        // OD-307. `deferredUninstall` is a request, not an action: the split is still
        // on the device afterwards, usually for hours. Recording that is what stops the
        // Catalog offering a download of a stated size that will never happen — tapping
        // install short-circuits on `isInstalled` and reopens the module at once, which
        // on a device read as the removal having done nothing.
        val fixture = LifecycleFixture(provider = ScriptedProvider())
        fixture.manager.discover()
        fixture.manager.activate(moduleId())

        fixture.manager.purge(moduleId(), PurgeScope.ALL)

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.ADVERTISED)
        assertThat(fixture.runtime().awaitingPlayCleanup).isTrue()
    }

    @Test
    fun `a provider that really does uninstall leaves nothing awaiting cleanup`() = runTest {
        // The other half of the same fact, so the flag tracks reality rather than
        // being pinned true for every provider. A bundled module — and one day a
        // satellite — removes on request, and there is then nothing to explain.
        val fixture = LifecycleFixture(provider = ScriptedProvider(uninstallIsImmediate = true))
        fixture.manager.discover()
        fixture.manager.activate(moduleId())

        fixture.manager.purge(moduleId(), PurgeScope.ALL)

        assertThat(fixture.runtime().awaitingPlayCleanup).isFalse()
    }

    @Test
    fun `adding a module back before Play reclaims it clears the pending removal`() = runTest {
        // The instant-reinstall path, which is legitimate: the code never left, so the
        // module comes straight back with the data gone. What must not survive is the
        // flag, or the tile keeps apologising for a removal the user has undone.
        val fixture = LifecycleFixture(provider = ScriptedProvider())
        fixture.manager.discover()
        fixture.manager.activate(moduleId())
        fixture.manager.purge(moduleId(), PurgeScope.ALL)
        assertThat(fixture.runtime().awaitingPlayCleanup).isTrue()

        fixture.manager.activate(moduleId())

        assertThat(fixture.runtime().state).isEqualTo(ModuleState.ACTIVE)
        assertThat(fixture.runtime().awaitingPlayCleanup).isFalse()
    }

    @Test
    fun `a cache purge keeps the module installed and does not uninstall it`() = runTest {
        val module = ScriptedModule()
        val fixture = LifecycleFixture(provider = ScriptedProvider(module = module))
        fixture.manager.discover()
        fixture.manager.activate(moduleId())

        fixture.manager.purge(moduleId(), PurgeScope.CACHE)

        assertThat(module.purges).containsExactly(PurgeScope.CACHE)
        assertThat(fixture.provider.uninstalled).isEmpty()
        assertThat(fixture.runtime().state).isEqualTo(ModuleState.INSTALLED)
    }

    @Test
    fun `purge publishes DataPurged so other modules can drop caches`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        fixture.manager.activate(moduleId())

        fixture.manager.purge(moduleId(), PurgeScope.SESSION)

        val purged = fixture.events.published.filterIsInstance<PlatformEvent.DataPurged>()
        assertThat(purged.map { it.scope }).containsExactly(PurgeScope.SESSION)
    }

    @Test
    fun `a module throwing from purge still has its kernel-side data erased`() = runTest {
        // Erasure cannot depend on module code behaving.
        val fixture = LifecycleFixture(
            provider = ScriptedProvider(
                module = ScriptedModule(throwOnPurge = RuntimeException("purge boom")),
            ),
        )
        fixture.manager.discover()
        fixture.manager.activate(moduleId())

        fixture.manager.purge(moduleId(), PurgeScope.ALL)

        coVerify { fixture.servicesFactory.purge(moduleId(), PurgeScope.ALL) }
        assertThat(fixture.runtime().state).isEqualTo(ModuleState.ADVERTISED)
        assertThat(fixture.telemetry.errors.map { it.message }).contains("purge boom")
    }

    // -- queries ------------------------------------------------------------

    @Test
    fun `stateOf reports advertised for an unknown module rather than throwing`() {
        val fixture = LifecycleFixture()

        assertThat(fixture.manager.stateOf(moduleId("com.omnideck.unknown")))
            .isEqualTo(ModuleState.ADVERTISED)
    }

    @Test
    fun `manifestOf is null until the module has been activated`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        assertThat(fixture.manager.manifestOf(moduleId())).isNull()

        fixture.manager.activate(moduleId())

        assertThat(fixture.manager.manifestOf(moduleId())).isNotNull()
    }

    @Test
    fun `activating an unknown module fails loudly`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()

        val error = runCatching { fixture.manager.activate(moduleId("com.omnideck.ghost")) }
            .exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
    }
}
