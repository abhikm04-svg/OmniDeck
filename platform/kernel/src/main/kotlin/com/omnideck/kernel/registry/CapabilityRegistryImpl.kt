package com.omnideck.kernel.registry

import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.CapabilityRegistry
import com.omnideck.sdk.ModuleId
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-module service exchange (architecture.md §10.3).
 *
 * Providers register lazily (`() -> T`) so a capability costs nothing until someone
 * actually resolves it. Consumers get `null` when the providing module is absent,
 * quarantined or simply not released yet — and because the SDK forces them to handle
 * that, a module can be removed from the platform without breaking its consumers.
 * This is the mobile equivalent of a service registry with graceful degradation.
 */
@Singleton
class CapabilityRegistryImpl @Inject constructor() {

    private data class Entry(val owner: ModuleId, val type: Class<*>, val provider: () -> Any)

    private val entries = ConcurrentHashMap<CapabilityId, Entry>()
    private val instances = ConcurrentHashMap<CapabilityId, Any>()

    /** Emits whenever the set of available capabilities changes. */
    val available = MutableStateFlow<Set<CapabilityId>>(emptySet())

    fun scopedTo(moduleId: ModuleId): CapabilityRegistry = ScopedRegistry(moduleId)

    fun available(): Set<CapabilityId> = entries.keys.toSet()

    fun removeAll(moduleId: ModuleId) {
        entries.entries.removeIf { it.value.owner == moduleId }
        instances.keys.removeIf { it !in entries.keys }
        available.value = entries.keys.toSet()
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> resolve(id: CapabilityId, type: Class<T>): T? {
        val entry = entries[id] ?: return null
        if (!type.isAssignableFrom(entry.type)) return null
        return instances.getOrPut(id) { entry.provider() } as? T
    }

    private inner class ScopedRegistry(private val owner: ModuleId) : CapabilityRegistry {

        override fun <T : Any> register(id: CapabilityId, type: Class<T>, provider: () -> T) {
            require(id !in CapabilityId.KERNEL_PROVIDED) {
                "$id is a kernel capability and cannot be overridden by a module."
            }
            val existing = entries[id]
            require(existing == null || existing.owner == owner) {
                "Capability $id is already provided by ${existing?.owner}. " +
                    "Two modules cannot provide the same capability — raise an RFC if you need to take it over."
            }
            entries[id] = Entry(owner, type, provider)
            instances.remove(id)
            available.value = entries.keys.toSet()
        }

        override fun <T : Any> resolve(id: CapabilityId, type: Class<T>): T? =
            this@CapabilityRegistryImpl.resolve(id, type)

        override fun isAvailable(id: CapabilityId): Boolean = entries.containsKey(id)
    }
}
