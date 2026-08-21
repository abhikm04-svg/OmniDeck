package com.omnideck.sdk

/**
 * The entire integration surface of the OmniDeck platform.
 *
 * Implement this once per module in a class named exactly `ModuleEntryPoint`, in the
 * module's own namespace, with a public no-arg constructor. The `omnideck.module`
 * convention plugin generates the R8 keep rule and the runtime discovery descriptor,
 * so there is nothing else to register anywhere — adding a module touches zero Shell
 * files (architecture.md G1, verified continuously by OD-212).
 *
 * ```
 * class ModuleEntryPoint : OmniModule {
 *     override val manifest = ModuleManifest(...)
 *     override suspend fun initialize(services: PlatformServices) = ModuleInitResult.Ready
 *     override fun registerDestinations(registry: DestinationRegistry) {
 *         registry.destination("omnideck://notes/home") { NotesHomeScreen() }
 *     }
 * }
 * ```
 */
interface OmniModule {

    /** Static identity and requirements. Read before the module is ever initialised. */
    val manifest: ModuleManifest

    /**
     * Called once, on a background dispatcher, after the module's code is available
     * and before any destination renders.
     *
     * Contract:
     *  - must be idempotent (the Shell may retry after a transient failure)
     *  - must not block for more than 500 ms (architecture.md §16 budget; asserted
     *    in debug builds, alerted on in production)
     *  - must not touch the UI
     *  - must not throw — return [ModuleInitResult.Failed] instead, so the Shell can
     *    distinguish retryable from permanent and drive the quarantine state machine
     */
    suspend fun initialize(services: PlatformServices): ModuleInitResult

    /** Contributes this module's composable destinations to the Shell's single NavHost. */
    fun registerDestinations(registry: DestinationRegistry)

    /** Optionally offers services to other modules (architecture.md §10.3). */
    fun registerCapabilities(registry: CapabilityRegistry) = Unit

    /** The Shell is suspending this module. Release expensive resources. */
    suspend fun suspend(reason: SuspendReason) = Unit

    /**
     * Destroy module-owned data. Called on module removal, sign-out and account
     * deletion. Must be exhaustive — this is the module's half of the GDPR/DPDP
     * erasure guarantee (architecture.md §11.1, §12.5).
     */
    suspend fun purge(scope: PurgeScope) = Unit
}
