package com.omnideck.shell.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnideck.designsystem.component.ErrorSurface
import com.omnideck.designsystem.component.LoadingSurface
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.kernel.lifecycle.ModuleRuntime
import com.omnideck.kernel.router.MutableDestinationRegistry
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.Route
import com.omnideck.shell.ShellViewModel
import com.omnideck.shell.home.HomeScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Shell's one NavHost.
 *
 * Rather than pre-declaring routes, it renders whatever destination the registry
 * resolves for the current route. That indirection is what allows a module installed
 * *after* the Shell was built to become navigable without any Shell change.
 */
@Composable
fun ShellNavHost(onReady: () -> Unit, viewModel: ShellViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val runtimes by viewModel.runtimes.collectAsState()
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(state.ready) {
        if (state.ready) onReady()
    }

    // Predictive back is enabled in the manifest; without an explicit handler the
    // system pops the Activity and the user leaves the app from a module screen.
    BackHandler(enabled = state.currentRoute != null) { viewModel.onBack() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbars.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            val currentRoute = state.currentRoute
            when {
                !state.ready -> LoadingSurface(label = "Starting OmniDeck…")

                currentRoute == null -> HomeScreen(
                    modules = state.modules,
                    onModuleClick = viewModel::onModuleClicked,
                    onCatalog = viewModel::onCatalog,
                    onSettings = viewModel::onSettings,
                )

                else -> ModuleDestination(
                    route = currentRoute,
                    registry = viewModel.destinations,
                    degradedReason = degradedReasonFor(currentRoute, runtimes),
                    onBack = viewModel::onBack,
                )
            }
        }
    }
}

/**
 * The advisory banner of `ModuleInitResult.Degraded`.
 *
 * Only for a module that came back *usable but incomplete*. Anything worse than that
 * never reaches this composable — the Router sends it to the status screen instead.
 */
private fun degradedReasonFor(route: Route, runtimes: Map<ModuleId, ModuleRuntime>): String? {
    val runtime = runtimes.entries.firstOrNull { (id, _) -> id.shortId == route.host }?.value ?: return null
    return runtime.reason.takeIf { runtime.state == ModuleState.DEGRADED }
}

@Composable
private fun ModuleDestination(
    route: Route,
    registry: MutableDestinationRegistry,
    degradedReason: String?,
    onBack: () -> Unit,
) {
    // Recomposes when a module registers new destinations, so a just-installed
    // module's screen appears without any further navigation.
    val destinations by registry.destinations.collectAsState()
    val banners by registry.degradedBanners.collectAsState()
    val resolved = remember(route, destinations) { registry.resolve(route) }

    if (resolved == null) {
        ErrorSurface(
            title = "Not available",
            message = "Nothing handles ${route.uri} yet.",
            onRetry = onBack,
        )
        return
    }

    val (destination, args) = resolved
    Column(Modifier.fillMaxSize()) {
        if (degradedReason != null) {
            // The module's own banner if it registered one, so it can say something
            // specific; otherwise the platform's, so the state is never invisible.
            banners[destination.owner]?.invoke(degradedReason) ?: DegradedBanner(degradedReason)
        }
        destination.content(args)
    }
}

@Composable
private fun DegradedBanner(reason: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(BANNER_ICON))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
    }
}

private val BANNER_ICON = 18.dp

/** Bridges the Router (no Compose dependency) to the NavHost (all Compose). */
@Singleton
class ShellNavigationSink @Inject constructor() : com.omnideck.kernel.router.NavigationCommandSink {

    private val commands = Channel<NavCommand>(Channel.BUFFERED)

    val stream = commands.receiveAsFlow()

    override fun navigate(route: Route) {
        commands.trySend(NavCommand.Navigate(route))
    }

    override fun back(): Boolean = commands.trySend(NavCommand.Back).isSuccess

    sealed interface NavCommand {
        data class Navigate(val route: Route) : NavCommand
        data object Back : NavCommand
    }
}
