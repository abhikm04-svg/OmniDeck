package com.omnideck.shell.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnideck.kernel.lifecycle.ModuleLifecycleManager
import com.omnideck.kernel.lifecycle.ModuleRuntime
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.capability.TelemetryService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One row of the Catalog.
 *
 * Everything here is derived from the lifecycle manager's runtime state, never from a
 * list of modules held anywhere in the Shell — same reason the home grid is (goal G1).
 */
data class CatalogEntry(
    val id: ModuleId,
    val title: String,
    val summary: String,
    val delivery: DeliveryKind,
    val state: ModuleState,
    /** Download progress while installing, when the provider reports it. */
    val installProgress: Float?,
    /** Zero means "not known yet" — see [CatalogEntry.detailsAreProvisional]. */
    val downloadBytes: Long,
    val reason: String?,
) {
    /** Nothing to download and nothing to reclaim: it is inside the base APK. */
    val isBundled: Boolean get() = delivery == DeliveryKind.BUNDLED

    /**
     * True while the module's own manifest has never been read.
     *
     * A module's display name, summary and size live in its `ModuleManifest`, which is
     * Kotlin *inside the module* — so for an on-demand module that has never been
     * installed the Shell genuinely does not have them, and shows the module id
     * instead of inventing a name. This is the gap the server-side Module Registry
     * closes (architecture.md §6.2, Phase 4): the same `ModuleManifest` type is
     * `@Serializable` precisely so the Catalog can be served before the code is.
     */
    val detailsAreProvisional: Boolean get() = summary.isEmpty()

    val isBusy: Boolean
        get() = state == ModuleState.INSTALLING ||
            state == ModuleState.INITIALIZING ||
            state == ModuleState.PURGING
}

/**
 * The Catalog (OD-303) — install, remove, and see why a module is not usable.
 *
 * Deliberately not a navigator: acquisition is all it does, and the Shell's single
 * navigation owner ([com.omnideck.shell.ShellViewModel]) still decides what a tap
 * means. That split keeps the back stack in one place while this stays a plain,
 * testable state machine over the lifecycle manager.
 */
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val lifecycle: ModuleLifecycleManager,
    private val telemetry: TelemetryService,
) : ViewModel() {

    /**
     * Seeded from the current state rather than from an empty list, and shared
     * eagerly: the upstream is a `map` over an in-memory `StateFlow`, so there is
     * nothing to be lazy about, and a `WhileSubscribed` start would render one frame
     * of "Nothing to show" over a Catalog that was already fully known.
     */
    val entries: StateFlow<List<CatalogEntry>> = lifecycle.modules
        .map(::toEntries)
        .stateIn(viewModelScope, SharingStarted.Eagerly, toEntries(lifecycle.modules.value))

    /**
     * Downloads a module and brings it up.
     *
     * The activation at the end is what makes the Catalog's "Install" mean the same
     * thing as tapping an uninstalled module's tile: the Router's acquisition flow
     * installs *and* initialises, and a Catalog that stopped at "downloaded" would
     * leave the module inert until the user found it again.
     */
    fun onInstall(id: ModuleId) = viewModelScope.launch {
        telemetry.event("catalog_install_requested", mapOf("module.id" to id.value))
        // Progress reaches the UI through the lifecycle manager's own state map, which
        // it updates as this flow emits — so there is nothing to collect into here.
        lifecycle.install(id).collect { }
        if (lifecycle.stateOf(id) == ModuleState.INSTALLED) {
            lifecycle.activate(id)
        }
    }

    /**
     * Removes a module and everything it stored.
     *
     * `PurgeScope.ALL` rather than a bare uninstall: leaving a module's database
     * behind after the user removed it is the kind of quiet data retention the
     * Privacy Centre exists to make impossible (ADR-005, architecture.md §12.4).
     * Play's own reclamation is deferred, so the space is freed when Play decides —
     * which is why nothing here promises the user an immediate saving.
     */
    fun onRemove(id: ModuleId) = viewModelScope.launch {
        telemetry.event("catalog_module_removed", mapOf("module.id" to id.value))
        lifecycle.purge(id, PurgeScope.ALL)
    }

    private fun toEntries(runtimes: Map<ModuleId, ModuleRuntime>): List<CatalogEntry> =
        runtimes.values.map(::toEntry).sortedBy { it.title.lowercase() }

    private fun toEntry(runtime: ModuleRuntime): CatalogEntry {
        val manifest = runtime.manifest
        return CatalogEntry(
            id = runtime.descriptor.id,
            title = manifest?.displayName?.default
                ?: runtime.descriptor.id.shortId.replaceFirstChar(Char::titlecase),
            summary = manifest?.summary?.default.orEmpty(),
            delivery = runtime.descriptor.delivery,
            state = runtime.state,
            installProgress = runtime.installProgress,
            downloadBytes = manifest?.estimatedDownloadBytes ?: 0L,
            reason = runtime.reason,
        )
    }
}
