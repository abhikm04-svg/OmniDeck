package com.omnideck.shell.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnideck.kernel.lifecycle.ModuleLifecycleManager
import com.omnideck.kernel.services.ConsentServiceImpl
import com.omnideck.sdk.DataCategory
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.capability.ConsentPurpose
import com.omnideck.sdk.capability.Router
import com.omnideck.sdk.capability.TelemetryService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModulePrivacyRow(
    val id: ModuleId,
    val displayName: String,
    val dataCategories: List<DataCategory>,
    val permissions: List<String>,
    val storageBytes: Long,
)

data class PrivacyState(
    val purposes: Map<ConsentPurpose, Boolean> = emptyMap(),
    val modules: List<ModulePrivacyRow> = emptyList(),
    val erasing: ModuleId? = null,
    val loading: Boolean = true,
)

/**
 * The Privacy Centre (OD-207).
 *
 * Two obligations meet here, both of which the architecture is built to make cheap:
 *
 *  - **Disclosure.** Every module declares its `dataCategories` in a manifest that
 *    also generates the Play Data Safety form (§12.5), so what this screen shows and
 *    what the store listing claims come from one source and cannot drift.
 *  - **Erasure.** Per-module storage is a directory (ADR-005), so "delete everything
 *    this module knows about me" is a deterministic operation rather than an audit —
 *    and the byte count above the button is measured from that same directory.
 */
@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val lifecycle: ModuleLifecycleManager,
    private val services: com.omnideck.kernel.services.ModuleScopedServicesFactory,
    private val consent: ConsentServiceImpl,
    private val telemetry: TelemetryService,
    private val router: Router,
) : ViewModel() {

    private val _state = MutableStateFlow(PrivacyState())
    val state: StateFlow<PrivacyState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            consent.state.collect { snapshot ->
                _state.update {
                    it.copy(purposes = ConsentPurpose.entries.associateWith { p -> p in snapshot.granted })
                }
            }
        }
        viewModelScope.launch {
            lifecycle.modules.collect { runtimes ->
                val rows = runtimes.values.map { runtime ->
                    ModulePrivacyRow(
                        id = runtime.descriptor.id,
                        displayName = runtime.manifest?.displayName?.default
                            ?: runtime.descriptor.id.shortId.replaceFirstChar(Char::titlecase),
                        // Empty until the module has been activated once: the manifest
                        // lives in the module's own code. Rendered as "not yet known"
                        // rather than as "collects nothing", which would be a lie.
                        dataCategories = runtime.manifest?.dataCategories.orEmpty().sortedBy { c -> c.name },
                        permissions = runtime.manifest?.androidPermissions.orEmpty().sorted(),
                        storageBytes = services.usageBytes(runtime.descriptor.id),
                    )
                }
                _state.update { it.copy(modules = rows.sortedBy { row -> row.displayName }, loading = false) }
            }
        }
    }

    fun onConsentChanged(purpose: ConsentPurpose, granted: Boolean) {
        consent.set(purpose, granted)
        telemetry.event(
            "consent_changed",
            mapOf("purpose" to purpose.name, "granted" to granted),
        )
    }

    /**
     * Erases one module's data (GDPR/DPDP right to erasure, architecture.md §12.5).
     *
     * Goes through the lifecycle manager rather than deleting the directory here, so
     * the module's own `purge()` runs first — it may hold caches or open handles the
     * kernel cannot see — and the `DataPurged` event reaches every other module.
     */
    fun onEraseModule(id: ModuleId) = viewModelScope.launch {
        _state.update { it.copy(erasing = id) }
        telemetry.event("privacy_erase_requested", mapOf("module.id" to id.value))
        lifecycle.purge(id, PurgeScope.ALL)
        _state.update { it.copy(erasing = null) }
    }

    fun onEraseEverything() = viewModelScope.launch {
        telemetry.event("privacy_erase_all_requested")
        lifecycle.modules.value.keys.forEach { id ->
            _state.update { it.copy(erasing = id) }
            lifecycle.purge(id, PurgeScope.ALL)
        }
        _state.update { it.copy(erasing = null) }
    }

    fun onBack() {
        router.back()
    }
}
