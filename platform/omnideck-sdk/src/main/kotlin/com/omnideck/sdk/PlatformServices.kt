package com.omnideck.sdk

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

/**
 * Everything the platform offers a module.
 *
 * Crucially, each module receives its **own** instance, built by the kernel's
 * `ModuleScopedServicesFactory` (architecture.md §6.3). That instance:
 *
 *  - tags every telemetry signal with this module's id
 *  - namespaces every database, DataStore and file path under `modules/<id>/`
 *  - derives per-module encryption keys, so one module cannot read another's ciphertext
 *  - attaches `X-OmniDeck-Module` for server-side attribution and rate limiting
 *  - checks each call against the module's declared `requiredCapabilities`
 *  - tags scheduled work so quarantine can cancel it atomically
 *
 * A module never receives a raw kernel reference, and therefore cannot fabricate
 * another module's identity. This is the zero-trust boundary of §12.2.
 */
interface PlatformServices {

    /** The module these services are scoped to. */
    val moduleId: ModuleId

    /** Host SDK version, for modules that need to feature-detect. */
    val sdkVersion: SemVer

    val auth: AuthService
    val network: NetworkService
    val storage: StorageService
    val secureStore: SecureStore
    val telemetry: TelemetryService
    val flags: FeatureFlagService
    val router: Router
    val events: EventBus
    val permissions: PermissionBroker
    val notifications: NotificationService
    val billing: BillingService
    val work: WorkScheduler
    val consent: ConsentService
    val locale: LocaleService
    val media: MediaService

    /**
     * Resolves a capability contributed by another module. Returns null when the
     * providing module is absent, quarantined, or not yet released — always handle it.
     */
    fun <T : Any> capability(id: CapabilityId, type: Class<T>): T?
}

inline fun <reified T : Any> PlatformServices.capability(id: CapabilityId): T? = capability(id, T::class.java)

/**
 * Thrown by the kernel when a module calls a capability it did not declare in its
 * manifest. Fail loudly at the boundary rather than mysteriously in the middle
 * (architecture.md §12.2 — grants are checked at call time, not only at load time).
 */
class CapabilityNotGrantedException(val moduleId: ModuleId, val capabilityId: CapabilityId) :
    SecurityException(
        "Module $moduleId called $capabilityId without declaring it in " +
            "ModuleManifest.requiredCapabilities. Declare it and request a capability review.",
    )
