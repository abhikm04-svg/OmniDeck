package com.omnideck.notes

import com.omnideck.notes.sync.NotesSyncRuntime
import com.omnideck.notes.ui.NoteEditorRoute
import com.omnideck.notes.ui.NotesListRoute
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.DataCategory
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.DestinationRegistry
import com.omnideck.sdk.IconRef
import com.omnideck.sdk.LocalizedString
import com.omnideck.sdk.ModuleCategory
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleInitResult
import com.omnideck.sdk.ModuleManifest
import com.omnideck.sdk.OmniModule
import com.omnideck.sdk.PlatformServices
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.Route
import com.omnideck.sdk.RoutePattern
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.SemVerRange
import com.omnideck.sdk.SuspendReason
import com.omnideck.sdk.TeamRef

/**
 * Notes — the first OmniDeck module (OD-209).
 *
 * The entire integration surface is this class. There is no registration anywhere in
 * the Shell, no entry in a manifest the Shell owns, and no Gradle line naming this
 * module in `:app`: the directory under `modules/` is the whole installation
 * procedure (goal G1, verified by OD-212).
 *
 * It is also the contract's first real test. Everything it needs — persistence,
 * background work, HTTP, navigation, telemetry — it takes from [PlatformServices],
 * and every one of those has a fake in `:platform:testing`, so this module's tests
 * run with no Shell and no kernel on the classpath.
 */
class ModuleEntryPoint : OmniModule {

    // Not `lateinit`: initialize() may be retried after a failure, and a half-built
    // component from the previous attempt must not be reachable in between.
    @Volatile
    private var component: NotesComponent? = null

    override val manifest = ModuleManifest(
        id = ModuleId("com.omnideck.notes"),
        version = SemVer(0, 1, 0),
        displayName = LocalizedString(default = "Notes"),
        summary = LocalizedString(default = "Write things down. Works offline."),
        category = ModuleCategory.PRODUCTIVITY,
        icon = IconRef.Symbol("sticky_note_2"),
        delivery = DeliveryKind.BUNDLED,
        sdkRange = SemVerRange.parse(">=1.0.0 <2.0.0"),
        minHostVersionCode = 1,
        entryRoute = Route("omnideck://notes/home"),
        // Declared so a notification or an App Link can open one note directly, even
        // before the module has been loaded in this process.
        deepLinks = listOf(RoutePattern("omnideck://notes/note/{noteId}")),
        // Required: without these the module cannot function and must not start.
        requiredCapabilities = setOf(CapabilityId.STORAGE, CapabilityId.TELEMETRY, CapabilityId.ROUTER),
        // Optional: their absence costs synchronisation, not the module.
        optionalCapabilities = setOf(CapabilityId.NETWORK, CapabilityId.WORK),
        dataCategories = setOf(DataCategory.PERSONAL_INFO, DataCategory.APP_ACTIVITY),
        estimatedDownloadBytes = ESTIMATED_DOWNLOAD_BYTES,
        supportsOffline = true,
        owner = TeamRef("productivity-squad"),
    )

    /**
     * Cheap by contract: opening the Room database is lazy, and nothing here touches
     * the network or the disk, so the 500 ms budget (architecture.md §16) is met with
     * room to spare.
     */
    override suspend fun initialize(services: PlatformServices): ModuleInitResult = try {
        val built = NotesComponent.build(services)
        component = built

        built.sync?.let {
            NotesSyncRuntime.attach(it.engine)
            it.scheduler.schedulePeriodic()
        }
        built.telemetry.event("notes_initialized", mapOf("sync" to (built.sync != null)))

        if (built.sync == null) {
            // Honest rather than silent. Every note is safe on the device; the Shell
            // shows an advisory banner instead of pretending edits are backed up.
            ModuleInitResult.Degraded("Sync is not configured — notes are saved on this device only.")
        } else {
            ModuleInitResult.Ready
        }
    } catch (e: IllegalStateException) {
        // Storage unavailable (no space, a corrupt database file). Retryable: the
        // Shell will try again, and three failures quarantine the module rather than
        // leaving it half-alive.
        ModuleInitResult.Failed(e, retryable = true)
    }

    override fun registerDestinations(registry: DestinationRegistry) {
        registry.destination("omnideck://notes/home") {
            NotesListRoute(requireComponent())
        }
        registry.destination("omnideck://notes/new") {
            NoteEditorRoute(requireComponent(), noteId = null)
        }
        registry.destination("omnideck://notes/note/{noteId}") { args ->
            NoteEditorRoute(requireComponent(), noteId = args.string("noteId"))
        }
    }

    /**
     * Releasing the engine reference matters: a suspended module must not keep
     * draining its outbox in the background, and the holder is the only thing that
     * would let a WorkManager job outlive the suspension.
     */
    override suspend fun suspend(reason: SuspendReason) {
        NotesSyncRuntime.detach()
    }

    override suspend fun purge(scope: PurgeScope) {
        if (scope == PurgeScope.ALL) NotesSyncRuntime.detach()
        component?.repository?.wipe(scope)
        if (scope == PurgeScope.ALL) component = null
    }

    private fun requireComponent(): NotesComponent = checkNotNull(component) {
        "Notes destinations were rendered before initialize() completed. " +
            "The Shell activates a module before routing to it, so this is a platform bug."
    }

    private companion object {
        const val ESTIMATED_DOWNLOAD_BYTES = 1_200_000L
    }
}
