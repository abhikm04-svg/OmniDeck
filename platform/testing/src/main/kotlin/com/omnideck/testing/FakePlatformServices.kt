package com.omnideck.testing

import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.PlatformServices
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.capability.MediaService
import com.omnideck.sdk.capability.NetworkService
import com.omnideck.sdk.capability.StorageService

/**
 * A complete, in-memory [PlatformServices] for module unit tests.
 *
 * ```
 * val services = FakePlatformServices(ModuleId("com.omnideck.notes"))
 * val module = ModuleEntryPoint()
 * assertThat(module.initialize(services)).isEqualTo(ModuleInitResult.Ready)
 * assertThat(services.telemetry.events).contains("notes_initialized")
 * ```
 *
 * Every fake is also an assertion surface: it records what the module did, so tests
 * can verify telemetry, navigation, scheduled work and permission requests without
 * any mocking framework.
 */
// One parameter per PlatformServices property, each with a working default — the
// whole point of this class is to be a complete fake, so this mirrors the
// interface 1:1 rather than being an accidentally-long parameter list.
@Suppress("LongParameterList")
class FakePlatformServices(
    override val moduleId: ModuleId = ModuleId("com.omnideck.test"),
    override val sdkVersion: SemVer = SemVer(1, 0, 0),
    override val auth: FakeAuthService = FakeAuthService(),
    override val telemetry: FakeTelemetryService = FakeTelemetryService(),
    override val flags: FakeFeatureFlagService = FakeFeatureFlagService(),
    override val events: FakeEventBus = FakeEventBus(),
    override val router: FakeRouter = FakeRouter(),
    override val storage: StorageService = FakeStorageService(),
    override val secureStore: FakeSecureStore = FakeSecureStore(),
    override val permissions: FakePermissionBroker = FakePermissionBroker(),
    override val notifications: FakeNotificationService = FakeNotificationService(),
    override val billing: FakeBillingService = FakeBillingService(),
    override val work: FakeWorkScheduler = FakeWorkScheduler(),
    override val consent: FakeConsentService = FakeConsentService(),
    override val locale: FakeLocaleService = FakeLocaleService(),
    override val network: NetworkService = FakeNetworkService(),
    override val media: MediaService = FakeMediaService(),
) : PlatformServices {

    private val capabilities = mutableMapOf<CapabilityId, Any>()

    /** Makes a cross-module capability resolvable in this test. */
    fun <T : Any> provideCapability(id: CapabilityId, instance: T) = apply {
        capabilities[id] = instance
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(id: CapabilityId, type: Class<T>): T? =
        capabilities[id]?.takeIf { type.isInstance(it) } as T?

    /** Resets every recorded interaction. Call between test cases if reusing. */
    fun reset() {
        telemetry.reset()
        router.reset()
        notifications.reset()
        work.reset()
        permissions.reset()
        capabilities.clear()
    }
}
