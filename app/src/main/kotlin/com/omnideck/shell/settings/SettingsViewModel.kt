package com.omnideck.shell.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnideck.kernel.lifecycle.HostInfo
import com.omnideck.kernel.lifecycle.ModuleLifecycleManager
import com.omnideck.kernel.services.ModuleScopedServicesFactory
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.capability.Router
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InstalledModule(
    val id: ModuleId,
    val displayName: String,
    val version: String,
    val state: ModuleState,
    val owner: String,
    val storageBytes: Long,
)

data class SettingsState(
    val hostSdkVersion: SemVer = SemVer(0, 0, 0),
    val hostVersionCode: Int = 0,
    val modules: List<InstalledModule> = emptyList(),
    val loading: Boolean = true,
)

/**
 * Settings (OD-207).
 *
 * Its job in Phase 2 is to make the platform's own state legible: which modules the
 * device actually has, which SDK they were admitted against, and what each is using
 * on disk. That last number is measured through the same namespaced storage the
 * module was handed, so what a user is shown here is exactly what the Privacy
 * Centre's delete erases.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val lifecycle: ModuleLifecycleManager,
    private val services: ModuleScopedServicesFactory,
    private val router: Router,
    hostInfo: HostInfo,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsState(hostSdkVersion = hostInfo.sdkVersion, hostVersionCode = hostInfo.versionCode),
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            lifecycle.modules.collect { runtimes ->
                val rows = runtimes.values.map { runtime ->
                    InstalledModule(
                        id = runtime.descriptor.id,
                        displayName = runtime.manifest?.displayName?.default
                            ?: runtime.descriptor.id.shortId.replaceFirstChar(Char::titlecase),
                        version = runtime.manifest?.version?.toString() ?: "—",
                        state = runtime.state,
                        owner = runtime.manifest?.owner?.value ?: "unassigned",
                        storageBytes = services.usageBytes(runtime.descriptor.id),
                    )
                }
                _state.update { it.copy(modules = rows.sortedBy { row -> row.displayName }, loading = false) }
            }
        }
    }

    fun onBack() {
        router.back()
    }
}
