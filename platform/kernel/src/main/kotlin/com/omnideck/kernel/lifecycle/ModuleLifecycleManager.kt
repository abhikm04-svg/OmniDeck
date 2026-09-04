package com.omnideck.kernel.lifecycle

import com.omnideck.core.Clock
import com.omnideck.core.DispatcherProvider
import com.omnideck.kernel.loader.ModuleDescriptor
import com.omnideck.kernel.loader.ModuleDescriptorSource
import com.omnideck.kernel.loader.ModuleProvider
import com.omnideck.kernel.registry.CapabilityRegistryImpl
import com.omnideck.kernel.router.MutableDestinationRegistry
import com.omnideck.kernel.services.ModuleScopedServicesFactory
import com.omnideck.sdk.InstallProgress
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleInitResult
import com.omnideck.sdk.ModuleManifest
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.OmniModule
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.SuspendReason
import com.omnideck.sdk.capability.EventBus
import com.omnideck.sdk.capability.FeatureFlagService
import com.omnideck.sdk.capability.PlatformEvent
import com.omnideck.sdk.capability.TelemetryService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the Shell knows about one module at runtime. */
data class ModuleRuntime(
    val descriptor: ModuleDescriptor,
    val state: ModuleState,
    val manifest: ModuleManifest? = null,
    val reason: String? = null,
    val failureCount: Int = 0,
    /** Wall-clock time of the oldest failure still inside the quarantine window. */
    val firstFailureAtMs: Long = 0,
    /** Download progress while [ModuleState.INSTALLING], when the provider reports it. */
    val installProgress: Float? = null,
    /**
     * Why the module is quarantined. A kill switch is reversible from the server; a
     * crash loop is not, and must not be cleared by the flag flipping back on
     * (architecture.md §7.1 — "remote clear **and** version bump").
     */
    val quarantineCause: QuarantineCause? = null,
    /**
     * True when the module is [ModuleState.GATED] only because this host is older
     * than it requires (OD-308).
     *
     * The difference between a solvable problem and a dead end: an app update fixes
     * this one, and nothing the user can do fixes a capability the installed host
     * does not implement. The status screen offers the update on this flag alone,
     * rather than on every gated module.
     */
    val hostUpdateWouldHelp: Boolean = false,
    /**
     * True after the user removed this module while its code is still on the device
     * (OD-307).
     *
     * Play's only uninstall for a feature split is `deferredUninstall`: it takes the
     * request and reclaims the space on its own schedule — typically when the device
     * is idle and charging, sometimes hours later, never synchronously and with no
     * callback when it happens. So immediately after a removal the truth is split in
     * two: the module's *data* is gone for certain, and its *code* is still installed.
     *
     * Recording that is what stops the two lies this used to tell. The state went to
     * [ModuleState.ADVERTISED] unconditionally, which claims "not installed" about a
     * split that demonstrably is — and the tile then offered a download of a stated
     * size that would never happen, because `install()` short-circuits on
     * `isInstalled()` and returns immediately. A user who removed a module and tapped
     * install again saw it reappear instantly, which reads as the removal having done
     * nothing at all.
     */
    val awaitingPlayCleanup: Boolean = false,
)

/** What put a module into [ModuleState.QUARANTINED]. */
enum class QuarantineCause {
    /** Server-pushed `module.<id>.enabled = false` (ADR-009). Reversible remotely. */
    KILL_SWITCH,

    /** Repeated initialisation failures inside the rolling window. Needs a new version. */
    INIT_FAILURE,
}

/** Host identity, needed for the compatibility gate. */
data class HostInfo(val sdkVersion: SemVer, val versionCode: Int)

/**
 * The module state machine of architecture.md §7.1.
 *
 * This class is the reason a misbehaving module cannot take the platform down. It
 * owns three safety properties:
 *
 *  - **Compatibility gating** — a module outside its declared `sdkRange` is never
 *    initialised, so version skew produces a clear message instead of a
 *    `NoSuchMethodError` deep inside a user journey.
 *  - **Capability gating** — a module whose required capabilities are unavailable
 *    fails fast and loud at load, not lazily at first use.
 *  - **Quarantine** — repeated initialisation failures, or a server kill switch,
 *    move a module to a contained, non-interactive state with its scheduled work
 *    cancelled and an incident raised against its owning team (QA-6, QA-9).
 */
@Singleton
class ModuleLifecycleManager @Inject constructor(
    private val descriptorSource: ModuleDescriptorSource,
    private val providers: Set<@JvmSuppressWildcards ModuleProvider>,
    private val servicesFactory: ModuleScopedServicesFactory,
    private val destinations: MutableDestinationRegistry,
    private val capabilities: CapabilityRegistryImpl,
    private val telemetry: TelemetryService,
    private val flags: FeatureFlagService,
    private val events: EventBus,
    private val hostInfo: HostInfo,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
) {

    private val _modules = MutableStateFlow<Map<ModuleId, ModuleRuntime>>(emptyMap())
    val modules: StateFlow<Map<ModuleId, ModuleRuntime>> = _modules.asStateFlow()

    private val instances = ConcurrentHashMap<ModuleId, OmniModule>()
    private val locks = ConcurrentHashMap<ModuleId, Mutex>()

    /** The §7.1 compatibility gate, given a name and a test of its own (OD-308). */
    private val compatibility = CompatibilityGate(hostInfo, capabilities)

    /** Reads descriptors and seeds the state map. Cheap: no module code runs here. */
    suspend fun discover() {
        val discovered = descriptorSource.descriptors()
        telemetry.event("module_discovery", mapOf("count" to discovered.size))

        _modules.value = discovered.associate { descriptor ->
            val provider = providerFor(descriptor)
            val installed = provider?.isInstalled(descriptor.id) == true
            descriptor.id to ModuleRuntime(
                descriptor = descriptor,
                state = when {
                    provider == null -> ModuleState.FAILED
                    isKilled(descriptor.id) -> ModuleState.QUARANTINED
                    installed -> ModuleState.INSTALLED
                    else -> ModuleState.ADVERTISED
                },
                reason = if (provider == null) "No provider for ${descriptor.delivery}" else null,
            )
        }
    }

    /** Downloads a module that is not present yet. Progress drives the Catalog UI. */
    fun install(id: ModuleId): Flow<InstallProgress> {
        val runtime = requireNotNull(_modules.value[id]) { "Unknown module $id" }
        val provider = requireNotNull(providerFor(runtime.descriptor)) { "No provider for $id" }
        update(id) { it.copy(state = ModuleState.INSTALLING) }

        return provider.install(id).onEach { progress ->
            when (progress) {
                is InstallProgress.Installed -> update(id) {
                    it.copy(state = ModuleState.INSTALLED, installProgress = null)
                }
                is InstallProgress.Canceled -> update(id) {
                    it.copy(state = ModuleState.ADVERTISED, installProgress = null)
                }
                is InstallProgress.Failed -> update(id) {
                    it.copy(state = ModuleState.ADVERTISED, reason = progress.message, installProgress = null)
                }
                // Surfaced so the tile shows a real bar rather than an indeterminate
                // spinner: a stalled 43% is diagnosable, a spinner is not.
                is InstallProgress.Downloading -> update(id) { it.copy(installProgress = progress.fraction) }
                else -> Unit
            }
        }
    }

    /**
     * Asks the provider to abandon an install the user no longer wants (OD-302).
     *
     * The state is *not* moved here. Play answers a cancellation by emitting
     * [InstallProgress.Canceled] on the session, which the `onEach` above already
     * turns back into [ModuleState.ADVERTISED] — and a download Play declines to
     * cancel, because it had already finished, must not leave the Catalog claiming
     * the module is not installed when it is. Letting the one stream own the state
     * keeps the two from disagreeing.
     */
    fun cancelInstall(id: ModuleId) {
        val runtime = _modules.value[id] ?: return
        providerFor(runtime.descriptor)?.cancelInstall(id)
    }

    /**
     * Brings a module to [ModuleState.ACTIVE], loading and initialising it if needed.
     * Idempotent and safe to call from the UI on every navigation.
     */
    @Suppress("ReturnCount")
    suspend fun activate(id: ModuleId): ModuleRuntime = lockFor(id).withLock {
        val current = _modules.value[id] ?: error("Unknown module $id")

        if (current.state.isUsable) return current
        if (current.state == ModuleState.QUARANTINED) return current
        if (isKilled(id)) return quarantine(id, "Disabled by the OmniDeck team.", QuarantineCause.KILL_SWITCH)

        val provider = providerFor(current.descriptor)
            ?: return update(id) { it.copy(state = ModuleState.FAILED, reason = "No provider") }

        if (!provider.isInstalled(id)) {
            return update(id) { it.copy(state = ModuleState.ADVERTISED) }
        }

        update(id) { it.copy(state = ModuleState.INITIALIZING) }

        return telemetry.startSpan("module.activate", mapOf("module.id" to id.value)).use { span ->
            try {
                val module = instances.getOrPut(id) { provider.load(current.descriptor) }
                val manifest = module.manifest

                compatibility.evaluate(manifest)?.let { failure ->
                    span.setStatus(ok = false, description = failure.message)
                    return@use update(id) { it.gatedBy(failure, manifest) }
                }

                val services = servicesFactory.create(manifest)
                val result = withContext(dispatchers.default) { module.initialize(services) }

                when (result) {
                    is ModuleInitResult.Ready, is ModuleInitResult.Degraded -> {
                        module.registerDestinations(destinations.scopedTo(id))
                        module.registerCapabilities(capabilities.scopedTo(id))
                        verifyDeclaredRoutes(destinations, telemetry, manifest)
                        val state = if (result is ModuleInitResult.Ready) {
                            ModuleState.ACTIVE
                        } else {
                            ModuleState.DEGRADED
                        }
                        span.setStatus(ok = true)
                        publishState(id, state)
                        update(id) {
                            it.copy(
                                state = state,
                                manifest = manifest,
                                failureCount = 0,
                                firstFailureAtMs = 0,
                                quarantineCause = null,
                                reason = (result as? ModuleInitResult.Degraded)?.reason,
                                // Back in service, so there is no pending removal to
                                // explain any more — whether Play reclaimed the split
                                // and it was fetched again, or the user changed their
                                // mind before Play got round to it.
                                awaitingPlayCleanup = false,
                            )
                        }
                    }

                    is ModuleInitResult.Failed -> {
                        span.recordException(result.error)
                        telemetry.recordError(result.error, "module_init_failed:${id.value}")
                        onInitFailure(id, manifest, result)
                    }
                }
            } catch (t: Throwable) {
                // A module throwing from initialize() is a contract violation. Contain
                // it here — it must never propagate into the Shell's composition.
                telemetry.recordError(t, "module_init_threw:${id.value}")
                span.recordException(t)
                onInitFailure(id, null, ModuleInitResult.Failed(t, retryable = false))
            }
        }
    }

    suspend fun suspendModule(id: ModuleId, reason: SuspendReason) {
        instances[id]?.let { module ->
            runCatching { module.suspend(reason) }
                .onFailure { telemetry.recordError(it, "module_suspend_failed:${id.value}") }
        }
        update(id) { if (it.state.isUsable) it.copy(state = ModuleState.SUSPENDED) else it }
    }

    /**
     * Brings a suspended module back (architecture.md §7.1, `Suspended -> Active`).
     *
     * The module instance is still in memory, so this re-enters `initialize` on the
     * same object rather than reloading it — which is exactly why the contract
     * requires `initialize` to be idempotent.
     */
    suspend fun resume(id: ModuleId): ModuleRuntime {
        val current = _modules.value[id] ?: error("Unknown module $id")
        if (current.state != ModuleState.SUSPENDED) return current
        update(id) { it.copy(state = ModuleState.INSTALLED) }
        return activate(id)
    }

    /**
     * Applies the server-side kill switch continuously (ADR-009, QA-9: a live module
     * must be contained within five minutes of the flag being pushed).
     *
     * Checking only at activation — which is all the state machine did before — meant
     * a module already on screen kept running until the user next navigated to it,
     * which is precisely the case the kill switch exists for. Collected for as long as
     * the Shell process lives.
     */
    suspend fun watchKillSwitches() {
        val ids = _modules.value.keys.toList()
        if (ids.isEmpty()) return

        combine(ids.map { id -> flags.booleanFlow(killSwitchKey(id), default = true).map { id to it } }) { it }
            .collect { states -> states.forEach { (id, enabled) -> applyKillSwitch(id, enabled) } }
    }

    private suspend fun applyKillSwitch(id: ModuleId, enabled: Boolean) {
        val runtime = _modules.value[id] ?: return
        when {
            !enabled && runtime.state != ModuleState.QUARANTINED ->
                quarantine(id, "Disabled by the OmniDeck team.", QuarantineCause.KILL_SWITCH)

            // Only a kill switch is reversible from the server. A module quarantined
            // for crashing on start stays quarantined until a new version ships,
            // because nothing about flipping a flag has fixed it.
            enabled && runtime.quarantineCause == QuarantineCause.KILL_SWITCH -> release(id)

            else -> Unit
        }
    }

    /** Returns a kill-switched module to service once the flag is cleared. */
    private fun release(id: ModuleId): ModuleRuntime {
        telemetry.event("module_kill_switch_cleared", mapOf("module.id" to id.value))
        val runtime = _modules.value.getValue(id)
        val installed = providerFor(runtime.descriptor)?.isInstalled(id) == true
        return update(id) {
            it.copy(
                state = if (installed) ModuleState.INSTALLED else ModuleState.ADVERTISED,
                reason = null,
                quarantineCause = null,
                failureCount = 0,
                firstFailureAtMs = 0,
            )
        }
    }

    /**
     * Erases module-owned data. Because storage is structurally isolated (ADR-005),
     * this is deterministic — which is what makes GDPR/DPDP erasure testable.
     */
    suspend fun purge(id: ModuleId, scope: PurgeScope) {
        update(id) { it.copy(state = ModuleState.PURGING) }
        instances[id]?.let { module ->
            runCatching { module.purge(scope) }
                .onFailure { telemetry.recordError(it, "module_purge_failed:${id.value}") }
        }
        servicesFactory.purge(id, scope)
        events.publish(PlatformEvent.DataPurged(id, scope))

        if (scope == PurgeScope.ALL) {
            instances.remove(id)
            destinations.removeAll(id)
            capabilities.removeAll(id)

            val provider = providerFor(_modules.value.getValue(id).descriptor)
            provider?.uninstall(id)
            // Asked afterwards, and believed. `deferredUninstall` is a request, not an
            // action: for a Play feature split this is almost always still true here,
            // and pretending otherwise is what made "Remove" look like it did nothing.
            val stillOnDevice = provider?.isInstalled(id) ?: false

            update(id) {
                it.copy(
                    state = ModuleState.ADVERTISED,
                    manifest = null,
                    awaitingPlayCleanup = stillOnDevice,
                )
            }
        } else {
            update(id) { it.copy(state = ModuleState.INSTALLED) }
        }
    }

    /** Forces a module out of service. Callable from a remote kill switch (< 5 min, QA-9). */
    suspend fun quarantine(
        id: ModuleId,
        reason: String,
        cause: QuarantineCause = QuarantineCause.KILL_SWITCH,
    ): ModuleRuntime {
        telemetry.event(
            "module_quarantined",
            mapOf("module.id" to id.value, "reason" to reason, "cause" to cause.name),
        )
        instances[id]?.let { runCatching { it.suspend(SuspendReason.KILL_SWITCH) } }
        servicesFactory.cancelWork(id)
        destinations.removeAll(id)
        capabilities.removeAll(id)
        publishState(id, ModuleState.QUARANTINED)
        return update(id) { it.copy(state = ModuleState.QUARANTINED, reason = reason, quarantineCause = cause) }
    }

    fun stateOf(id: ModuleId): ModuleState = _modules.value[id]?.state ?: ModuleState.ADVERTISED

    fun manifestOf(id: ModuleId): ModuleManifest? = _modules.value[id]?.manifest

    // -----------------------------------------------------------------------

    /**
     * Counts failures in a *rolling* window (architecture.md §7.1).
     *
     * A plain lifetime counter quarantines a module that failed once a month for three
     * months, which is a flaky network rather than a broken module. Only failures
     * close enough together to be the same fault are allowed to add up.
     */
    private fun onInitFailure(
        id: ModuleId,
        manifest: ModuleManifest?,
        result: ModuleInitResult.Failed,
    ): ModuleRuntime {
        val now = clock.nowMillis()
        val previous = _modules.value[id]
        val windowOpen = previous != null &&
            previous.firstFailureAtMs != 0L &&
            now - previous.firstFailureAtMs <= QUARANTINE_WINDOW_MS

        val failures = if (windowOpen) previous.failureCount + 1 else 1
        val windowStart = if (windowOpen) previous.firstFailureAtMs else now
        val message = result.error.message ?: "Initialisation failed"

        return if (!result.retryable || failures >= QUARANTINE_THRESHOLD) {
            publishState(id, ModuleState.QUARANTINED)
            update(id) {
                it.copy(
                    state = ModuleState.QUARANTINED,
                    manifest = manifest,
                    reason = message,
                    failureCount = failures,
                    firstFailureAtMs = windowStart,
                    quarantineCause = QuarantineCause.INIT_FAILURE,
                )
            }
        } else {
            update(id) {
                it.copy(
                    state = ModuleState.INSTALLED,
                    manifest = manifest,
                    reason = message,
                    failureCount = failures,
                    firstFailureAtMs = windowStart,
                )
            }
        }
    }

    private fun isKilled(id: ModuleId): Boolean = !flags.boolean(killSwitchKey(id), default = true)

    private fun providerFor(descriptor: ModuleDescriptor): ModuleProvider? =
        providers.firstOrNull { it.handles == descriptor.delivery }

    private fun lockFor(id: ModuleId): Mutex = locks.getOrPut(id) { Mutex() }

    private fun publishState(id: ModuleId, state: ModuleState) =
        events.publish(PlatformEvent.ModuleStateChanged(id, state))

    private inline fun update(id: ModuleId, transform: (ModuleRuntime) -> ModuleRuntime): ModuleRuntime {
        val updated = transform(_modules.value.getValue(id))
        _modules.value = _modules.value + (id to updated)
        return updated
    }

    private companion object {
        /** Three failures inside the window and the module is contained (architecture.md §7.1). */
        const val QUARANTINE_THRESHOLD = 3

        /**
         * How long failures keep adding up. An hour is long enough to catch a module
         * that fails every time the user opens it, and short enough that a genuinely
         * transient failure from this morning does not count against one tonight.
         */
        const val QUARANTINE_WINDOW_MS = 60L * 60L * 1000L
    }
}

/** ADR-009. The one convention the kill switch depends on, in one place. */
private fun killSwitchKey(id: ModuleId) = "module.${id.value}.enabled"

/**
 * Checks that a module registered what its manifest promised.
 *
 * A declared deep link with no destination behind it is a dead notification tap and a
 * dead App Link — invisible until a user reports it, because nothing in the module's
 * own tests exercises the Shell's route table. Reported rather than fatal: taking a
 * working module offline over a missing secondary route would be the worse outcome.
 * OD-212 asserts on this signal.
 */
private fun verifyDeclaredRoutes(
    destinations: MutableDestinationRegistry,
    telemetry: TelemetryService,
    manifest: ModuleManifest,
) {
    val registered = destinations.destinations.value.map { it.pattern }.toSet()
    val missing = manifest.deepLinks.filterNot { it in registered }
    val entryUnresolved = destinations.resolve(manifest.entryRoute) == null

    if (missing.isEmpty() && !entryUnresolved) return

    telemetry.event(
        "module_contract_violation",
        mapOf(
            "module.id" to manifest.id.value,
            "owner" to manifest.owner.value,
            "entry_route_unresolved" to entryUnresolved,
            "unregistered_deep_links" to missing.joinToString { it.pattern },
        ),
    )
}
