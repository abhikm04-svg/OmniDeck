package com.omnideck.shell.navigation

import android.net.Uri
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
 * The Shell's own [ShellViewModel], scoped to the Activity.
 *
 * Every module destination now renders inside its own `NavBackStackEntry`, which is
 * also a `ViewModelStoreOwner` — that is the entire point of the back stack, and it is
 * what gives a screen's ViewModel a lifecycle. But `hiltViewModel()` resolves against
 * *whatever* owner is current, so a Shell screen asking for `ShellViewModel` that way
 * would get a **new one per entry**: a second copy re-running discovery, re-subscribing
 * every kill switch, and disagreeing with the first about what is installed.
 *
 * The Shell's own state belongs to the Shell, not to a screen, so it is provided once
 * here and read through this local. Screen-local ViewModels (Catalog, Settings,
 * Privacy) deliberately keep using `hiltViewModel()` and are now entry-scoped, which is
 * what makes them get cleared when the user navigates away.
 */
val LocalShellViewModel = staticCompositionLocalOf<ShellViewModel> {
    error("LocalShellViewModel accessed outside ShellNavHost")
}

/**
 * The Shell's one NavHost.
 *
 * Rather than pre-declaring routes, it declares exactly **two**: Home, and one generic
 * pattern that carries an `omnideck://` URI as an argument and renders whatever the
 * `DestinationRegistry` resolves for it. A module installed after the Shell was built
 * is navigable through that second pattern with no Shell change, so goal G1 survives
 * the move to androidx Navigation — no module, and no module's route, is named here.
 *
 * Why a real NavController rather than the hand-rolled `ArrayDeque` this used to keep:
 * a back stack is not only an ordering. Each entry is a `ViewModelStoreOwner` and a
 * `SavedStateRegistryOwner`, so a destination's ViewModels are *cleared when it is
 * popped* and its state survives process death. Without that, every module ViewModel
 * resolved against the Activity and lived for the whole process — one `NotesListViewModel`
 * for ever, holding a repository belonging to a module that might since have been
 * purged, and one "new note" editor whose stale contents came back every time. Both
 * were reported from a device (OD-205, OD-307).
 */
@Composable
fun ShellNavHost(onReady: () -> Unit, viewModel: ShellViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val runtimes by viewModel.runtimes.collectAsState()
    val snackbars = remember { SnackbarHostState() }
    val navController = rememberNavController()

    LaunchedEffect(state.ready) {
        if (state.ready) onReady()
    }

    // The Router and the Shell's own screens both express navigation as an intent
    // rather than touching the controller, so acquisition, gating and the status-screen
    // fallbacks all stay in the ViewModel where they are testable without Compose.
    LaunchedEffect(navController) {
        viewModel.navigationIntents.collect { intent ->
            when (intent) {
                is ShellViewModel.NavIntent.Open -> navController.navigate(intent.route.toNavRoute())
                ShellViewModel.NavIntent.Back -> navController.popBackStack()
            }
        }
    }

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
            if (!state.ready) {
                LoadingSurface(label = "Starting OmniDeck…")
                return@Box
            }

            // The ViewModel keeps an accurate mirror of where the user is, because it
            // still owns the decisions that depend on it: abandoning a
            // `navigateForResult` correlation id on the way out (OD-205), and telling
            // the back handler whether there is anywhere to go. Reported from the
            // controller rather than tracked in parallel, so the two cannot drift.
            val entry by navController.currentBackStackEntryAsState()
            LaunchedEffect(entry) {
                viewModel.onCurrentDestinationChanged(entry.toRouteOrNull())
            }

            // Predictive back is enabled in the manifest; without a handler the system
            // pops the Activity and the user leaves the app from a module screen
            // instead of going Home.
            BackHandler(enabled = state.currentRoute != null) { viewModel.onBack() }

            CompositionLocalProvider(LocalShellViewModel provides viewModel) {
                ShellNavGraph(
                    navController = navController,
                    viewModel = viewModel,
                    runtimes = runtimes,
                )
            }
        }
    }
}

@Composable
private fun ShellNavGraph(
    navController: NavHostController,
    viewModel: ShellViewModel,
    runtimes: Map<ModuleId, ModuleRuntime>,
) {
    NavHost(navController = navController, startDestination = HOME_ROUTE) {
        composable(HOME_ROUTE) {
            val state by viewModel.state.collectAsState()
            HomeScreen(
                modules = state.modules,
                onModuleClick = viewModel::onModuleClicked,
                onCatalog = viewModel::onCatalog,
                onSettings = viewModel::onSettings,
            )
        }

        // The one generic destination. Everything that is not Home — every module
        // screen and every Shell screen alike — arrives here as a URI argument and is
        // resolved at render time, which is what keeps the graph independent of which
        // modules exist.
        composable(
            route = DESTINATION_ROUTE,
            arguments = listOf(navArgument(URI_ARG) { type = NavType.StringType }),
        ) { entry ->
            val route = remember(entry) {
                Route(Uri.decode(entry.arguments?.getString(URI_ARG).orEmpty()))
            }

            ModuleDestination(
                route = route,
                registry = viewModel.destinations,
                degradedReason = degradedReasonFor(route, runtimes),
                onBack = viewModel::onBack,
            )
        }
    }
}

private const val HOME_ROUTE = "omnideck.home"
private const val URI_ARG = "uri"
private const val DESTINATION_ROUTE = "omnideck.destination/{$URI_ARG}"

/**
 * A [Route] as a NavController route string.
 *
 * Encoded, because an `omnideck://` URI contains the `/` and `?` characters
 * NavController uses to delimit its own path and query — an unencoded route would be
 * parsed as several path segments and never match the pattern.
 */
private fun Route.toNavRoute(): String = "omnideck.destination/${Uri.encode(uri)}"

/**
 * The `omnideck://` route this entry renders, or null for Home.
 *
 * Null is the signal that there is nowhere to go back to, which is what the back
 * handler keys off — Home is the bottom of the stack, not a destination to pop.
 */
private fun NavBackStackEntry?.toRouteOrNull(): Route? {
    if (this == null || destination.route == HOME_ROUTE) return null
    val encoded = arguments?.getString(URI_ARG) ?: return null
    return Route(Uri.decode(encoded))
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
