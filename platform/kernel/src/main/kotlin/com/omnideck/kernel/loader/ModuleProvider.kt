package com.omnideck.kernel.loader

import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.InstallProgress
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.OmniModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * ADR-001 in code.
 *
 * Three delivery mechanisms — Play Feature Delivery splits, federated satellite APKs,
 * and CDN-hosted web surfaces — behind one interface, so the rest of the platform
 * (and every module author) is unaware of which one a module uses. Changing a
 * module's delivery kind is a manifest edit, not a rewrite.
 *
 * Note what is deliberately absent: there is no provider that downloads and executes
 * code from outside Google Play. That would violate the Device and Network Abuse
 * policy and is the reason this abstraction exists at all.
 */
interface ModuleProvider {

    val handles: DeliveryKind

    fun isInstalled(id: ModuleId): Boolean

    fun install(id: ModuleId): Flow<InstallProgress>

    suspend fun uninstall(id: ModuleId)

    /** Instantiates the module's entry point. Called only when [isInstalled] is true. */
    suspend fun load(descriptor: ModuleDescriptor): OmniModule
}

/**
 * Compile-time constructors for the modules built into the base APK (OD-202).
 *
 * The kernel cannot produce this itself: the map is generated into the Shell by
 * `:tools:module-processor`, and the kernel must not know that the Shell exists. It
 * is bound in `ShellModule` alongside the other two things in the same position —
 * the navigation sink and the permission requester.
 */
fun interface BundledModuleFactories {
    fun factories(): Map<String, () -> OmniModule>
}

/**
 * Modules compiled into the base APK. Always present, nothing to install.
 *
 * This is also the provider used by the plug-and-play fitness test (OD-212), because
 * it exercises the same discovery → instantiate → register path as the split provider
 * without needing a Play-connected device.
 */
class BundledModuleProvider(
    private val io: CoroutineDispatcher,
    private val factories: BundledModuleFactories = BundledModuleFactories { emptyMap() },
    private val classLoader: ClassLoader = BundledModuleProvider::class.java.classLoader!!,
) : ModuleProvider {

    override val handles = DeliveryKind.BUNDLED

    override fun isInstalled(id: ModuleId) = true

    override fun install(id: ModuleId): Flow<InstallProgress> = flowOf(InstallProgress.Installed)

    override suspend fun uninstall(id: ModuleId) = Unit

    /**
     * Prefers the generated constructor and falls back to reflection.
     *
     * The fallback is not dead code: it is the path a module takes before its
     * generated factory exists — a split installed after this APK was built (Phase 3),
     * and any host whose registry was not generated, such as an instrumented test that
     * loads a module directly.
     */
    override suspend fun load(descriptor: ModuleDescriptor): OmniModule = withContext(io) {
        factories.factories()[descriptor.id.value]?.invoke() ?: loadReflectively(descriptor)
    }

    private fun loadReflectively(descriptor: ModuleDescriptor): OmniModule {
        // Reflection over a class that shipped inside our own signed App Bundle.
        // This is not dynamic code loading; R8 keeps the class via the rule generated
        // by the omnideck.module convention plugin.
        val klass = classLoader.loadClass(descriptor.entryPointClass)
        val instance = klass.getDeclaredConstructor().newInstance()
        return instance as? OmniModule
            ?: error(
                "${descriptor.entryPointClass} does not implement OmniModule. " +
                    "Check that the module depends on :platform:omnideck-sdk.",
            )
    }
}

/** Raised when no provider can supply a module — a configuration error, not a user error. */
class ModuleLoadException(val moduleId: ModuleId, message: String, cause: Throwable? = null) :
    Exception("Failed to load $moduleId: $message", cause)
