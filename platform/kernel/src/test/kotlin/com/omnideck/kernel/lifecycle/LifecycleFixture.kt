package com.omnideck.kernel.lifecycle

import com.omnideck.core.DispatcherProvider
import com.omnideck.core.MutableClock
import com.omnideck.kernel.loader.ModuleDescriptor
import com.omnideck.kernel.loader.ModuleProvider
import com.omnideck.kernel.registry.CapabilityRegistryImpl
import com.omnideck.kernel.router.MutableDestinationRegistry
import com.omnideck.kernel.services.ModuleScopedServicesFactory
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.CapabilityRegistry
import com.omnideck.sdk.DataCategory
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.DestinationRegistry
import com.omnideck.sdk.IconRef
import com.omnideck.sdk.InstallProgress
import com.omnideck.sdk.LocalizedString
import com.omnideck.sdk.ModuleCategory
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleInitResult
import com.omnideck.sdk.ModuleManifest
import com.omnideck.sdk.OmniModule
import com.omnideck.sdk.PlatformServices
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.Route
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.SemVerRange
import com.omnideck.sdk.SuspendReason
import com.omnideck.sdk.TeamRef
import com.omnideck.testing.FakeEventBus
import com.omnideck.testing.FakeFeatureFlagService
import com.omnideck.testing.FakePlatformServices
import com.omnideck.testing.FakeTelemetryService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher

internal const val TEST_MODULE = "com.omnideck.notes"

internal fun moduleId(value: String = TEST_MODULE) = ModuleId(value)

internal fun descriptor(id: ModuleId = moduleId(), delivery: DeliveryKind = DeliveryKind.BUNDLED) =
    ModuleDescriptor(id = id, entryPointClass = "${id.value}.ModuleEntryPoint", delivery = delivery)

internal fun manifest(
    id: ModuleId = moduleId(),
    sdkRange: SemVerRange = SemVerRange(SemVer(1, 0, 0), SemVer(2, 0, 0)),
    minHostVersionCode: Int = 1,
    required: Set<CapabilityId> = setOf(CapabilityId.TELEMETRY),
) = ModuleManifest(
    id = id,
    version = SemVer(1, 0, 0),
    displayName = LocalizedString("Notes"),
    summary = LocalizedString("Take notes"),
    category = ModuleCategory.PRODUCTIVITY,
    icon = IconRef.Symbol("note"),
    delivery = DeliveryKind.BUNDLED,
    sdkRange = sdkRange,
    minHostVersionCode = minHostVersionCode,
    // Derived, not hardcoded: ModuleManifest validates that the entry route's host
    // matches the module's short id, so a fixture with a fixed route silently breaks
    // the moment a test uses a different module.
    entryRoute = Route("omnideck://${id.shortId}/home"),
    requiredCapabilities = required,
    dataCategories = setOf(DataCategory.APP_ACTIVITY),
    owner = TeamRef("platform"),
)

/**
 * Scriptable [OmniModule]. Every lifecycle callback records that it ran, so tests can
 * assert the manager actually drove the module rather than merely changing its own map.
 */
internal class ScriptedModule(
    override val manifest: ModuleManifest = manifest(),
    private val initResult: ModuleInitResult = ModuleInitResult.Ready,
    /**
     * Result per attempt, for modelling a module that recovers (or degrades) across
     * retries. The manager caches module instances, so a retry re-enters `initialize`
     * on the same object — swapping the provider's module would not be seen. Once
     * exhausted, the last entry repeats.
     */
    private val initResults: List<ModuleInitResult>? = null,
    private val throwOnInit: Throwable? = null,
    private val throwOnSuspend: Throwable? = null,
    private val throwOnPurge: Throwable? = null,
) : OmniModule {

    var initializeCount = 0
        private set
    var destinationsRegistered = 0
        private set
    var capabilitiesRegistered = 0
        private set
    val suspensions = mutableListOf<SuspendReason>()
    val purges = mutableListOf<PurgeScope>()

    override suspend fun initialize(services: PlatformServices): ModuleInitResult {
        initializeCount++
        throwOnInit?.let { throw it }
        val scripted = initResults ?: return initResult
        return scripted[(initializeCount - 1).coerceAtMost(scripted.lastIndex)]
    }

    override fun registerDestinations(registry: DestinationRegistry) {
        destinationsRegistered++
    }

    override fun registerCapabilities(registry: CapabilityRegistry) {
        capabilitiesRegistered++
    }

    override suspend fun suspend(reason: SuspendReason) {
        suspensions += reason
        throwOnSuspend?.let { throw it }
    }

    override suspend fun purge(scope: PurgeScope) {
        purges += scope
        throwOnPurge?.let { throw it }
    }
}

/** [ModuleProvider] whose install/load behaviour a test dictates. */
internal class ScriptedProvider(
    override val handles: DeliveryKind = DeliveryKind.BUNDLED,
    var installed: Boolean = true,
    var module: OmniModule = ScriptedModule(),
    private val installFlow: Flow<InstallProgress> = flowOf(InstallProgress.Installed),
    private val loadFailure: Throwable? = null,
    /**
     * Whether `uninstall` takes effect at once (OD-307).
     *
     * False is the Play feature-split default and the reason this parameter exists.
     * `SplitInstallManager` offers only `deferredUninstall`: it records the request and
     * reclaims the space on its own schedule, so `isInstalled` keeps answering true
     * long afterwards. A fake that removed the split synchronously modelled a Play that
     * does not exist, and the lifecycle manager's "the module is gone now" assumption
     * went unchallenged all the way to a device, where removing a module and tapping
     * install brought it straight back with no download.
     *
     * Set true for a provider that genuinely removes on request — a bundled module in a
     * test, or a future satellite uninstall.
     */
    private val uninstallIsImmediate: Boolean = false,
) : ModuleProvider {

    var loadCount = 0
        private set
    val uninstalled = mutableListOf<ModuleId>()

    override fun isInstalled(id: ModuleId): Boolean = installed

    override fun install(id: ModuleId): Flow<InstallProgress> = installFlow

    override suspend fun uninstall(id: ModuleId) {
        uninstalled += id
        if (uninstallIsImmediate) installed = false
    }

    /** Play finally reclaiming the split, at whatever later moment a test wants. */
    fun playReclaimsTheSplit() {
        installed = false
    }

    override suspend fun load(descriptor: ModuleDescriptor): OmniModule {
        loadCount++
        loadFailure?.let { throw it }
        return module
    }
}

/**
 * Assembles a [ModuleLifecycleManager] over real collaborators where they are cheap
 * (registries, event bus, flags) and a mock only where construction needs an Android
 * Context ([ModuleScopedServicesFactory]).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class LifecycleFixture(
    val descriptors: List<ModuleDescriptor> = listOf(descriptor()),
    val provider: ScriptedProvider = ScriptedProvider(),
    providers: Set<ModuleProvider>? = null,
    hostSdk: SemVer = SemVer(1, 0, 0),
    hostVersionCode: Int = 1,
) {
    val telemetry = FakeTelemetryService()
    val flags = FakeFeatureFlagService()
    val clock = MutableClock(startMillis = 1_000)
    val events = FakeEventBus()
    val destinations = MutableDestinationRegistry()
    val capabilities = CapabilityRegistryImpl()

    val servicesFactory: ModuleScopedServicesFactory = mockk(relaxed = true) {
        every { create(any()) } returns FakePlatformServices()
    }

    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
        override val unconfined = dispatcher
    }

    val manager = ModuleLifecycleManager(
        descriptorSource = { descriptors },
        providers = providers ?: setOf(provider),
        servicesFactory = servicesFactory,
        destinations = destinations,
        capabilities = capabilities,
        telemetry = telemetry,
        flags = flags,
        events = events,
        hostInfo = HostInfo(sdkVersion = hostSdk, versionCode = hostVersionCode),
        dispatchers = dispatchers,
        clock = clock,
    )

    /** Flips the server-side kill switch for [id]. */
    fun kill(id: ModuleId = moduleId()) {
        flags.set("module.${id.value}.enabled", false)
    }

    fun runtime(id: ModuleId = moduleId()): ModuleRuntime = manager.modules.value.getValue(id)
}
