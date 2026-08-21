package com.omnideck.kernel.services

import android.content.Context
import com.omnideck.core.DispatcherProvider
import com.omnideck.kernel.registry.CapabilityRegistryImpl
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.CapabilityNotGrantedException
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleManifest
import com.omnideck.sdk.PlatformServices
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.capability.AuthService
import com.omnideck.sdk.capability.BillingService
import com.omnideck.sdk.capability.ConsentService
import com.omnideck.sdk.capability.EventBus
import com.omnideck.sdk.capability.FeatureFlagService
import com.omnideck.sdk.capability.LocaleService
import com.omnideck.sdk.capability.MediaService
import com.omnideck.sdk.capability.NetworkService
import com.omnideck.sdk.capability.NotificationService
import com.omnideck.sdk.capability.PermissionBroker
import com.omnideck.sdk.capability.Router
import com.omnideck.sdk.capability.SecureStore
import com.omnideck.sdk.capability.StorageService
import com.omnideck.sdk.capability.TelemetryService
import com.omnideck.sdk.capability.WorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The zero-trust boundary (architecture.md §6.3, §12.2).
 *
 * Each module gets its **own** [PlatformServices] instance built here. The kernel
 * never hands out a raw service reference, so a module cannot:
 *
 *  - write to another module's storage (paths are namespaced at construction)
 *  - decrypt another module's secrets (Keystore aliases are per module)
 *  - attribute its telemetry or its HTTP traffic to another module
 *  - request a permission it did not declare
 *  - call a capability it did not declare
 *
 * All of that is structural. There is no code path a module could take to opt out,
 * because it never receives the objects that would let it.
 */
@Singleton
class ModuleScopedServicesFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val telemetryHub: TelemetryHub,
    private val networkEngine: NetworkEngine,
    private val capabilities: CapabilityRegistryImpl,
    private val flags: FeatureFlagService,
    private val events: EventBus,
    private val consent: ConsentService,
    private val auth: AuthService,
    private val billing: BillingService,
    private val routerProvider: Provider<Router>,
    private val permissionRequester: Provider<PermissionRequester>,
    private val hostSdkVersion: SemVer,
) {

    private val storages = ScopedRegistryCache<ModuleId, StorageServiceImpl>()
    private val workers = ScopedRegistryCache<ModuleId, WorkSchedulerImpl>()
    private val instances = ScopedRegistryCache<ModuleId, PlatformServices>()

    fun create(manifest: ModuleManifest): PlatformServices =
        instances.getOrPut(manifest.id) { ScopedServices(manifest) }

    /** Called by the lifecycle manager during purge; erases everything this module owns. */
    suspend fun purge(moduleId: ModuleId, scope: PurgeScope) {
        storages.getOrPut(moduleId) { StorageServiceImpl(context, moduleId, dispatchers) }.clear(scope)
        if (scope == PurgeScope.ALL) {
            cancelWork(moduleId)
            storages.remove(moduleId)
            instances.remove(moduleId)
        }
    }

    fun cancelWork(moduleId: ModuleId) {
        workers.getOrPut(moduleId) { WorkSchedulerImpl(context, moduleId) }.cancelAll()
    }

    private inner class ScopedServices(private val manifest: ModuleManifest) : PlatformServices {

        override val moduleId: ModuleId = manifest.id
        override val sdkVersion: SemVer = hostSdkVersion

        /**
         * Capability grants are re-checked on every access, not only at load time.
         * A module's grants can change server-side between activations, and a check
         * that only runs once is a check that is eventually wrong.
         */
        private fun <T> gated(id: CapabilityId, service: () -> T): T {
            if (id !in manifest.requiredCapabilities && id !in manifest.optionalCapabilities) {
                throw CapabilityNotGrantedException(moduleId, id)
            }
            return service()
        }

        override val telemetry: TelemetryService by lazy { telemetryHub.scopedTo(moduleId) }

        override val storage: StorageService
            get() = gated(CapabilityId.STORAGE) {
                storages.getOrPut(moduleId) { StorageServiceImpl(context, moduleId, dispatchers) }
            }

        override val secureStore: SecureStore
            get() = gated(CapabilityId.SECURE_STORE) { SecureStoreImpl(context, moduleId, dispatchers) }

        override val network: NetworkService
            get() = gated(CapabilityId.NETWORK) {
                NetworkServiceImpl(networkEngine, moduleId) {
                    runCatching { this@ModuleScopedServicesFactory.auth.accessToken() }.getOrNull()
                }
            }

        override val work: WorkScheduler
            get() = gated(CapabilityId.WORK) {
                workers.getOrPut(moduleId) { WorkSchedulerImpl(context, moduleId) }
            }

        override val permissions: PermissionBroker
            get() = gated(CapabilityId.PERMISSIONS) {
                PermissionBrokerImpl(
                    context = context,
                    moduleId = moduleId,
                    declared = manifest.androidPermissions,
                    requester = permissionRequester.get(),
                    onAudit = { permission, result, purpose ->
                        telemetry.event(
                            "permission_decision",
                            mapOf("permission" to permission, "result" to result.name, "purpose" to purpose),
                        )
                    },
                )
            }

        override val notifications: NotificationService
            get() = gated(CapabilityId.NOTIFICATIONS) {
                NotificationServiceImpl(context, moduleId, manifest.displayName.default, permissions)
            }

        override val locale: LocaleService get() = LocaleServiceImpl(context)
        override val media: MediaService get() = gated(CapabilityId.MEDIA) { UnavailableMediaService(moduleId) }
        override val auth: AuthService get() = gated(CapabilityId.AUTH) { this@ModuleScopedServicesFactory.auth }
        override val billing: BillingService get() = gated(CapabilityId.BILLING) {
            this@ModuleScopedServicesFactory.billing
        }
        override val flags: FeatureFlagService get() = this@ModuleScopedServicesFactory.flags
        override val events: EventBus get() = this@ModuleScopedServicesFactory.events
        override val consent: ConsentService get() = this@ModuleScopedServicesFactory.consent
        override val router: Router get() = routerProvider.get()

        override fun <T : Any> capability(id: CapabilityId, type: Class<T>): T? = capabilities.resolve(id, type)
    }
}
