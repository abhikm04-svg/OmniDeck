package com.omnideck.sdk

import kotlinx.serialization.Serializable

/**
 * The declarative description of a module (architecture.md §6.2).
 *
 * This exact type is serialised into the server-side Catalog, so the client and the
 * Module Registry cannot disagree about what a module is. It is also the source for
 * the aggregated Play Data Safety declaration (§12.5) — which is why [dataCategories]
 * is required rather than optional.
 */
@Serializable
data class ModuleManifest(
    val id: ModuleId,
    val version: SemVer,
    val displayName: LocalizedString,
    val summary: LocalizedString,
    val category: ModuleCategory,
    val icon: IconRef,
    val delivery: DeliveryKind,
    /** Host SDK versions this module supports, e.g. `>=1.0.0 <2.0.0`. */
    val sdkRange: SemVerRange,
    val minHostVersionCode: Int,
    val entryRoute: Route,
    val deepLinks: List<RoutePattern> = emptyList(),
    /** Refusing to initialise without these is a fast, loud failure by design. */
    val requiredCapabilities: Set<CapabilityId>,
    val optionalCapabilities: Set<CapabilityId> = emptySet(),
    /** Requested lazily and contextually via the PermissionBroker — never at startup. */
    val androidPermissions: Set<String> = emptySet(),
    val dataCategories: Set<DataCategory>,
    val entitlement: EntitlementPolicy = EntitlementPolicy.Free,
    val estimatedDownloadBytes: Long = 0,
    val supportsOffline: Boolean = false,
    /** Run heavy components in a separate process so a crash cannot take the Shell down (§12.6). */
    val processIsolation: Boolean = false,
    val owner: TeamRef,
) {
    init {
        require(entryRoute.host == id.shortId) {
            "entryRoute host '${entryRoute.host}' must match module shortId '${id.shortId}'"
        }
        require(requiredCapabilities.isNotEmpty()) {
            "A module that needs no capability does not need the platform. Declare what you use."
        }
    }

    /** Capabilities the kernel must supply before this module may initialise. */
    fun unsatisfiedBy(available: Set<CapabilityId>): Set<CapabilityId> = requiredCapabilities - available

    fun isCompatibleWith(hostSdk: SemVer, hostVersionCode: Int): Boolean =
        hostSdk in sdkRange && hostVersionCode >= minHostVersionCode
}

@Serializable
data class LocalizedString(val default: String, val translations: Map<String, String> = emptyMap()) {
    fun resolve(languageTag: String): String = translations[languageTag]
        ?: translations[languageTag.substringBefore('-')]
        ?: default

    override fun toString(): String = default
}

@Serializable
sealed interface IconRef {
    /** An Android drawable resource id, resolved inside the owning module. */
    @Serializable
    data class Drawable(val resourceId: Int) : IconRef

    /** A remote asset served from the CDN — used by server-driven catalog entries. */
    @Serializable
    data class Remote(val url: String) : IconRef

    /** A Material Symbols name, rendered by the design system. */
    @Serializable
    data class Symbol(val name: String) : IconRef
}

@Serializable
enum class ModuleCategory {
    PRODUCTIVITY,
    FINANCE,
    HEALTH,
    MEDIA,
    UTILITIES,
    COMMUNICATION,
    LEARNING,
    INTERNAL,
}

/** ADR-001 — how this module's code reaches the device. */
@Serializable
enum class DeliveryKind {
    /** Ships inside the base APK. Home, Catalog, Settings. */
    BUNDLED,

    /** Play Feature Delivery on-demand split. The default for new modules. */
    FEATURE_SPLIT,

    /** A separately published app, federated over AIDL (architecture.md §8). */
    SATELLITE,

    /** A CDN-hosted content surface rendered in a hardened WebView. */
    WEB,
}

/**
 * Play Data Safety categories. Aggregated across all modules at build time to
 * generate the store declaration, so the listing cannot silently drift from what
 * the code actually collects (§12.5, OD-701).
 */
@Serializable
enum class DataCategory {
    NONE,
    PERSONAL_INFO,
    FINANCIAL_INFO,
    HEALTH_AND_FITNESS,
    MESSAGES,
    PHOTOS_AND_VIDEOS,
    AUDIO,
    FILES_AND_DOCS,
    CALENDAR,
    CONTACTS,
    APP_ACTIVITY,
    WEB_BROWSING,
    APP_INFO_AND_PERFORMANCE,
    DEVICE_OR_OTHER_IDS,
    LOCATION,
}

@Serializable
sealed interface EntitlementPolicy {
    @Serializable
    data object Free : EntitlementPolicy

    @Serializable
    data class RequiresEntitlement(val sku: Sku) : EntitlementPolicy

    /** Dogfood / internal-only. Never advertised in a production catalog. */
    @Serializable
    data object Internal : EntitlementPolicy
}
