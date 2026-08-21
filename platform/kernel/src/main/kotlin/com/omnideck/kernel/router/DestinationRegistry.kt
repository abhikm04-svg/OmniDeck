package com.omnideck.kernel.router

import androidx.compose.runtime.Composable
import com.omnideck.sdk.DestinationRegistry
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.Route
import com.omnideck.sdk.RouteArgs
import com.omnideck.sdk.RoutePattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class Destination(val owner: ModuleId, val pattern: RoutePattern, val content: @Composable (RouteArgs) -> Unit)

/**
 * The Shell's route table.
 *
 * Modules write into it through a per-module view (see [scopedTo]) so a module can
 * only ever register destinations under its own short id — nothing stops a module
 * *asking* to own `omnideck://payments/...`, so the registry enforces it instead of
 * trusting the manifest.
 */
@Singleton
class MutableDestinationRegistry @Inject constructor() {

    private val _destinations = MutableStateFlow<List<Destination>>(emptyList())
    val destinations: StateFlow<List<Destination>> = _destinations.asStateFlow()

    /** A view that attributes every registration to [moduleId] and validates ownership. */
    fun scopedTo(moduleId: ModuleId): DestinationRegistry = ScopedRegistry(moduleId)

    fun removeAll(moduleId: ModuleId) {
        _destinations.value = _destinations.value.filterNot { it.owner == moduleId }
    }

    /** Best match for [route]: literal segments win over placeholders. */
    fun resolve(route: Route): Pair<Destination, RouteArgs>? = _destinations.value
        .asSequence()
        .mapNotNull { dest -> dest.pattern.extract(route)?.let { dest to it } }
        .maxByOrNull { (dest, _) -> dest.pattern.specificity }
        ?.let { (dest, args) -> dest to RouteArgs(args + route.query) }

    fun ownerOf(route: Route): ModuleId? = resolve(route)?.first?.owner

    private inner class ScopedRegistry(private val moduleId: ModuleId) : DestinationRegistry {

        override fun destination(pattern: String, content: @Composable (RouteArgs) -> Unit) {
            val routePattern = RoutePattern(pattern)
            val host = pattern.removePrefix(Route.SCHEME_PREFIX).substringBefore('/')

            require(host == moduleId.shortId) {
                "Module $moduleId tried to register '$pattern', which belongs to '$host'. " +
                    "A module may only own routes under omnideck://${moduleId.shortId}/."
            }
            require(_destinations.value.none { it.pattern == routePattern }) {
                "Route '$pattern' is already registered by ${_destinations.value.first {
                    it.pattern == routePattern
                }.owner}."
            }

            _destinations.value = _destinations.value + Destination(moduleId, routePattern, content)
        }
    }
}
