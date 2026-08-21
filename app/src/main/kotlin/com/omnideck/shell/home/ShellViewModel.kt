package com.omnideck.shell.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnideck.designsystem.component.TileState
import com.omnideck.kernel.lifecycle.ModuleLifecycleManager
import com.omnideck.kernel.lifecycle.ModuleRuntime
import com.omnideck.kernel.router.MutableDestinationRegistry
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.Route
import com.omnideck.sdk.capability.NavResult
import com.omnideck.sdk.capability.Router
import com.omnideck.sdk.capability.TelemetryService
import com.omnideck.shell.navigation.ShellNavigationSink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModuleTileModel(val id: ModuleId, val title: String, val subtitle: String, val tileState: TileState)

data class ShellState(
    val ready: Boolean = false,
    val modules: List<ModuleTileModel> = emptyList(),
    val currentRoute: Route? = null,
    val message: String? = null,
)

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val lifecycle: ModuleLifecycleManager,
    private val router: Router,
    private val telemetry: TelemetryService,
    private val navigationSink: ShellNavigationSink,
    val destinations: MutableDestinationRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(ShellState())
    val state: StateFlow<ShellState> = _state.asStateFlow()

    private val backStack = ArrayDeque<Route>()

    init {
        viewModelScope.launch {
            telemetry.startSpan("shell.startup").use {
                lifecycle.discover()
                _state.update { it.copy(ready = true) }
            }
        }
        viewModelScope.launch {
            lifecycle.modules.collect { runtimes ->
                _state.update { it.copy(modules = runtimes.values.map(::toTile)) }
            }
        }
        viewModelScope.launch {
            navigationSink.stream.collect { command ->
                when (command) {
                    is ShellNavigationSink.NavCommand.Navigate -> {
                        _state.value.currentRoute?.let(backStack::addLast)
                        _state.update { it.copy(currentRoute = command.route) }
                    }

                    ShellNavigationSink.NavCommand.Back -> onBack()
                }
            }
        }
    }

    /**
     * The whole "one-click shop" gesture: a tile tap is just a route navigation, and
     * the Router decides whether that means rendering a screen or running a full
     * download-and-initialise flow first.
     */
    fun onModuleClicked(id: ModuleId) = viewModelScope.launch {
        val runtime = lifecycle.modules.value[id] ?: return@launch
        val route = runtime.manifest?.entryRoute ?: Route.of(id, "home")

        when (val result = router.navigate(route)) {
            is NavResult.Navigated, is NavResult.NavigatedAfterInstall -> Unit
            is NavResult.Unavailable -> _state.update { it.copy(message = result.reason) }
            is NavResult.AcquisitionAborted -> _state.update { it.copy(message = result.reason) }
            is NavResult.Unhandled -> _state.update { it.copy(message = "Nothing handles ${route.uri}.") }
        }
    }

    fun onBack() {
        _state.update { it.copy(currentRoute = backStack.removeLastOrNull()) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun toTile(runtime: ModuleRuntime): ModuleTileModel {
        val manifest = runtime.manifest
        return ModuleTileModel(
            id = runtime.descriptor.id,
            title = manifest?.displayName?.default ?: runtime.descriptor.id.shortId.replaceFirstChar(Char::titlecase),
            subtitle = manifest?.summary?.default.orEmpty(),
            tileState = when (runtime.state) {
                ModuleState.ACTIVE, ModuleState.DEGRADED, ModuleState.INSTALLED, ModuleState.SUSPENDED ->
                    TileState.Ready

                ModuleState.ADVERTISED ->
                    TileState.Available((manifest?.estimatedDownloadBytes ?: 0L) / BYTES_IN_MB)

                ModuleState.INSTALLING, ModuleState.INITIALIZING, ModuleState.PURGING ->
                    TileState.Installing(fraction = null)

                ModuleState.GATED ->
                    TileState.Gated(runtime.reason ?: "Locked")

                ModuleState.QUARANTINED, ModuleState.FAILED ->
                    TileState.Quarantined(runtime.reason ?: "Temporarily unavailable")
            },
        )
    }

    private companion object {
        const val BYTES_IN_MB = 1_048_576.0
    }
}
