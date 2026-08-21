package com.omnideck.sdk.capability

import com.omnideck.sdk.CorrelationId
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.Route
import kotlinx.coroutines.flow.Flow

/**
 * Navigation across module boundaries (architecture.md §10).
 *
 * A module can link to a destination owned by a module it has no compile-time
 * knowledge of — and if that module is not installed, the Router runs the whole
 * acquisition flow (entitlement check, download, init) and then continues to the
 * destination. That single behaviour *is* the "one-click shop" (goal G1 + the store
 * experience), which is why it belongs in the platform and not in any screen.
 */
interface Router {

    suspend fun navigate(route: Route): NavResult

    /**
     * Navigates and awaits a typed result. The correlation id is persisted, so the
     * flow survives Shell process death and satellite round-trips alike (§10.2).
     */
    fun <T : Any> navigateForResult(route: Route, type: Class<T>): Flow<NavResultValue<T>>

    /** True if some installed-or-installable module claims this route. */
    fun canHandle(route: Route): Boolean

    /** Pops the current destination. Returns false if the back stack is at its root. */
    fun back(): Boolean

    /** Returns a result to whoever called [navigateForResult]. */
    fun <T : Any> setResult(correlationId: CorrelationId, value: T)
}

sealed interface NavResult {
    data class Navigated(val route: Route) : NavResult

    /** The module had to be fetched first; [route] was reached afterwards. */
    data class NavigatedAfterInstall(val route: Route, val moduleId: ModuleId) : NavResult

    /** No module claims this route. */
    data class Unhandled(val route: Route) : NavResult

    /** Owning module is quarantined, gated or incompatible. */
    data class Unavailable(val moduleId: ModuleId, val reason: String) : NavResult

    /** User declined the install, or it failed. */
    data class AcquisitionAborted(val moduleId: ModuleId, val reason: String) : NavResult
}

sealed interface NavResultValue<out T> {
    data class Success<T>(val value: T) : NavResultValue<T>
    data object Cancelled : NavResultValue<Nothing>
    data class Failed(val reason: String) : NavResultValue<Nothing>
}

inline fun <reified T : Any> Router.navigateForResult(route: Route): Flow<NavResultValue<T>> =
    navigateForResult(route, T::class.java)
