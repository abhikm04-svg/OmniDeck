package com.omnideck.shell.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnideck.kernel.lifecycle.ModuleLifecycleManager
import com.omnideck.kernel.lifecycle.ModuleRuntime
import com.omnideck.sdk.DataCategory
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.EntitlementPolicy
import com.omnideck.sdk.ModuleCategory
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.capability.TelemetryService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    /** Null until the module's own manifest has been read. */
    val category: ModuleCategory? = null,
    val version: SemVer? = null,
    val owner: String? = null,
    val supportsOffline: Boolean = false,
    /** Runtime permissions the module may ask for, requested contextually, never at start. */
    val androidPermissions: Set<String> = emptySet(),
    val dataCategories: Set<DataCategory> = emptySet(),
    val entitlement: EntitlementPolicy? = null,
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

    /**
     * True only while there is a Play session that could still be abandoned.
     *
     * Deliberately narrower than [isBusy]: initialising runs in this process and
     * finishes in milliseconds, and purging must not be interruptible half-way
     * through erasing a module's data.
     */
    val isCancellable: Boolean get() = state == ModuleState.INSTALLING && !isBundled

    /** Matched against the fields a user can actually see. */
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim()
        return title.contains(needle, ignoreCase = true) ||
            summary.contains(needle, ignoreCase = true) ||
            id.value.contains(needle, ignoreCase = true)
    }
}

/**
 * What the Catalog screen renders (OD-305).
 *
 * [categories] holds only the categories actually present in this build, so the
 * filter row never offers a chip that would empty the list — and, as everywhere else
 * in the Shell, it is derived from what was discovered rather than from a hardcoded
 * set of the categories someone expected to exist.
 */
data class CatalogUiState(
    val entries: List<CatalogEntry> = emptyList(),
    val categories: List<ModuleCategory> = emptyList(),
    val query: String = "",
    val selectedCategory: ModuleCategory? = null,
    /** Before filtering — the difference is how "no results" is told apart from "no modules". */
    val totalCount: Int = 0,
) {
    val isFiltered: Boolean get() = query.isNotBlank() || selectedCategory != null
}

/**
 * The Catalog (OD-305) — install, remove, and see why a module is not usable.
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

    private val query = MutableStateFlow("")
    private val selectedCategory = MutableStateFlow<ModuleCategory?>(null)

    /**
     * Every discovered module, unfiltered. The detail screen reads from here rather
     * than from [state], so a module stays addressable by URI when the list happens
     * to be filtered to something else — a deep link into the Catalog must not depend
     * on what the user last typed.
     */
    val entries: StateFlow<List<CatalogEntry>> = lifecycle.modules
        .map(::toEntries)
        .stateIn(viewModelScope, SharingStarted.Eagerly, toEntries(lifecycle.modules.value))

    /**
     * Shared eagerly: the upstream is a `map` over an in-memory `StateFlow`, so there
     * is nothing to be lazy about, and a `WhileSubscribed` start would render one
     * frame of "Nothing to show" over a Catalog that was already fully known.
     */
    val state: StateFlow<CatalogUiState> =
        combine(entries, query, selectedCategory, ::toUiState)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                toUiState(entries.value, query.value, selectedCategory.value),
            )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onCategorySelected(category: ModuleCategory?) {
        selectedCategory.value = category
    }

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
     * Abandons a download in progress (OD-302).
     *
     * Not implemented by cancelling the coroutine started above: that would stop this
     * process listening while Play carried on downloading in its own, which on a
     * metered connection is the user's data being spent after they pressed Cancel.
     * The lifecycle manager reaches the session itself.
     */
    fun onCancel(id: ModuleId) = viewModelScope.launch {
        telemetry.event("catalog_install_cancelled", mapOf("module.id" to id.value))
        lifecycle.cancelInstall(id)
    }

    /**
     * Removes a module and everything it stored (OD-307).
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

    private fun toUiState(all: List<CatalogEntry>, query: String, category: ModuleCategory?) = CatalogUiState(
        entries = all.filter { it.matches(query) && (category == null || it.category == category) },
        // Sorted by the enum's own order so the chip row does not reshuffle as
        // modules are installed and their categories become known.
        categories = all.mapNotNull { it.category }.distinct().sorted(),
        query = query,
        selectedCategory = category,
        totalCount = all.size,
    )

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
            category = manifest?.category,
            version = manifest?.version,
            owner = manifest?.owner?.value,
            supportsOffline = manifest?.supportsOffline ?: false,
            androidPermissions = manifest?.androidPermissions.orEmpty(),
            dataCategories = manifest?.dataCategories.orEmpty(),
            entitlement = manifest?.entitlement,
        )
    }
}
