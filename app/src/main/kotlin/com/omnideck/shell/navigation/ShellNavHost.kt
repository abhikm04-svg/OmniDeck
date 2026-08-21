package com.omnideck.shell.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnideck.designsystem.component.ErrorSurface
import com.omnideck.designsystem.component.LoadingSurface
import com.omnideck.kernel.router.MutableDestinationRegistry
import com.omnideck.sdk.Route
import com.omnideck.shell.home.HomeScreen
import com.omnideck.shell.home.ShellViewModel
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

    LaunchedEffect(state.ready) {
        if (state.ready) onReady()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(Modifier.padding(padding)) {
            val currentRoute = state.currentRoute
            when {
                !state.ready -> LoadingSurface(label = "Starting OmniDeck…")

                currentRoute == null -> HomeScreen(
                    modules = state.modules,
                    onModuleClick = viewModel::onModuleClicked,
                )

                else -> ModuleDestination(
                    route = currentRoute,
                    registry = viewModel.destinations,
                    onBack = viewModel::onBack,
                )
            }
        }
    }
}

@Composable
private fun ModuleDestination(route: Route, registry: MutableDestinationRegistry, onBack: () -> Unit) {
    // Recomposes when a module registers new destinations, so a just-installed
    // module's screen appears without any further navigation.
    val destinations by registry.destinations.collectAsState()
    val resolved = remember(route, destinations) { registry.resolve(route) }

    if (resolved == null) {
        ErrorSurface(
            title = "Not available",
            message = "Nothing handles ${route.uri} yet.",
            onRetry = onBack,
        )
    } else {
        val (destination, args) = resolved
        destination.content(args)
    }
}

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
