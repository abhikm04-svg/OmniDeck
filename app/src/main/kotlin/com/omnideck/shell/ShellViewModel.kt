package com.omnideck.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnideck.designsystem.component.TileState
import com.omnideck.kernel.lifecycle.ModuleLifecycleManager
import com.omnideck.kernel.lifecycle.ModuleRuntime
import com.omnideck.kernel.router.MutableDestinationRegistry
import com.omnideck.kernel.router.RouterImpl
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.Route
import com.omnideck.sdk.capability.NavResult
import com.omnideck.sdk.capability.TelemetryService
import com.omnideck.shell.navigation.ShellDestinations
import com.omnideck.shell.navigation.ShellNavigationSink
import com.omnideck.shell.navigation.ShellRoutes
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
    /** Transient user-facing text for a navigation that could not be completed. */
    val message: String? = null,
)

/**
 * The Shell's single source of navigation and module state.
 *
 * It knows about *no* module. Everything on the home grid comes from what the
 * lifecycle manager discovered, and every destination comes from what modules
 * registered — which is what makes goal G1 hold at runtime and not just at build time.
 */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val lifecycle: ModuleLifecycleManager,
    // The kernel implementation rather than the SDK's Router: abandoning a pending
    // navigateForResult (OD-205) is something the Shell observes about its own back
    // stack, and is deliberately not part of the module-facing contract.
    private val router: RouterImpl,
    private val telemetry: TelemetryService,
    private val navigationSink: ShellNavigationSink,
    private val shellDestinations: ShellDestinations,
    val destinations: MutableDestinationRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(ShellState())
    val state: StateFlow<ShellState> = _state.asStateFlow()

    /** Live module state, for the degraded banner and the status screen. */
    val runtimes: StateFlow<Map<ModuleId, ModuleRuntime>> = lifecycle.modules

    private val backStack = ArrayDeque<Route>()

    init {
        viewModelScope.launch {
            telemetry.startSpan("shell.startup").use {
                shellDestinations.registerInto(destinations)
                lifecycle.discover()
                _state.update { it.copy(ready = true) }
            }
            // Only after discovery: the watcher subscribes to a flag per discovered
            // module, and starting it earlier would watch nothing (QA-9).
            lifecycle.watchKillSwitches()
        }
        viewModelScope.launch {
            lifecycle.modules.collect { runtimes ->
                _state.update { it.copy(modules = runtimes.values.map(::toTile)) }
            }
        }
        viewModelScope.launch {
            navigationSink.stream.collect { command ->
                when (command) {
                    is ShellNavigationSink.NavCommand.Navigate -> push(command.route)
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
        navigate(runtime.manifest?.entryRoute ?: Route.of(id, "home"))
    }

    fun onSettings() = viewModelScope.launch { navigate(ShellRoutes.settings()) }

    fun onPrivacy() = viewModelScope.launch { navigate(ShellRoutes.privacy()) }

    /** The Catalog (OD-303) — install and remove modules. */
    fun onCatalog() = viewModelScope.launch { navigate(ShellRoutes.catalog()) }

    /**
     * Why a module is unusable, and what can be done about it (OD-208).
     *
     * Routed rather than pushed directly, so a link the Catalog makes and a link the
     * Router makes after a failed acquisition land in exactly the same place.
     */
    fun onModuleStatus(id: ModuleId) = viewModelScope.launch { navigate(ShellRoutes.moduleStatus(id)) }

    /** Retry from the module status screen, after a failed install or a cleared quarantine. */
    fun onRetryModule(id: ModuleId) = viewModelScope.launch {
        onBack()
        onModuleClicked(id)
    }

    fun onBack() {
        val leaving = _state.value.currentRoute
        // OD-205. A destination reached through navigateForResult carries the
        // correlation id in its route; popping it without a result is the user saying
        // no. Unless someone says so, the caller's flow never completes and whatever
        // it was gating waits for ever.
        leaving?.correlationId?.let(router::abandon)
        _state.update { it.copy(currentRoute = backStack.removeLastOrNull()) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun navigate(route: Route) {
        when (val result = router.navigate(route)) {
            is NavResult.Navigated, is NavResult.NavigatedAfterInstall -> Unit

            // A module the user cannot currently use gets a screen that says why and
            // offers a way forward, rather than a toast that vanishes (OD-208).
            is NavResult.Unavailable -> push(ShellRoutes.moduleStatus(result.moduleId))
            is NavResult.AcquisitionAborted -> push(ShellRoutes.moduleStatus(result.moduleId))

            is NavResult.Unhandled -> _state.update { it.copy(message = "Nothing handles ${route.uri}.") }
        }
    }

    private fun push(route: Route) {
        _state.value.currentRoute?.let(backStack::addLast)
        _state.update { it.copy(currentRoute = route) }
    }

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
                    TileState.Installing(fraction = runtime.installProgress)

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
