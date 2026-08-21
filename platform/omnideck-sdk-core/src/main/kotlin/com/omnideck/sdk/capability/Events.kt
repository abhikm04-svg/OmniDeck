package com.omnideck.sdk.capability

import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.Sku
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Fire-and-forget, many-to-many facts (architecture.md §10.3).
 *
 * Deliberately weak: no delivery guarantee, no return value, no ordering promise
 * across publishers. Anything needing a response must be a Capability instead. That
 * constraint is what stops the event bus from decaying into an untyped RPC mechanism
 * — the failure mode of every "just use an event bus" architecture.
 */
interface EventBus {
    fun publish(event: PlatformEvent)

    fun <T : PlatformEvent> subscribe(type: Class<T>): Flow<T>
}

inline fun <reified T : PlatformEvent> EventBus.subscribe(): Flow<T> = subscribe(T::class.java)

/**
 * Base type for every cross-module event. Subtypes live in the SDK — never in a
 * module — because an event is a shared contract by definition.
 */
@Serializable
sealed interface PlatformEvent {
    /** Schema version; bumped when a payload changes shape (§8.2 versioning rules). */
    val schemaVersion: Int get() = 1

    @Serializable
    data class SessionChanged(val signedIn: Boolean, val userIdHash: String?) : PlatformEvent

    @Serializable
    data class ThemeChanged(val darkMode: Boolean, val dynamicColor: Boolean) : PlatformEvent

    @Serializable
    data class LocaleChanged(val languageTag: String) : PlatformEvent

    @Serializable
    data class ConnectivityChanged(val online: Boolean, val metered: Boolean) : PlatformEvent

    @Serializable
    data class PurchaseCompleted(val sku: Sku) : PlatformEvent

    @Serializable
    data class EntitlementsChanged(val skus: Set<Sku>) : PlatformEvent

    @Serializable
    data class ModuleStateChanged(val moduleId: ModuleId, val state: com.omnideck.sdk.ModuleState) : PlatformEvent

    /** Emitted before data is destroyed so modules can drop in-memory caches. */
    @Serializable
    data class DataPurged(val moduleId: ModuleId?, val scope: com.omnideck.sdk.PurgeScope) : PlatformEvent
}
