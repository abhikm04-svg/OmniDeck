package com.omnideck.sdk

import androidx.compose.runtime.Composable

/**
 * How a module contributes UI. Modules provide composable destinations, not
 * Activities (ADR-003): the Shell owns one Activity, one NavHost and one back stack,
 * which keeps shared element transitions, predictive back and deep linking in a
 * single place, and removes manifest merging from the on-demand load path.
 */
interface DestinationRegistry {

    /**
     * Registers a destination for an exact or `{placeholder}` route pattern.
     *
     * This is the only overload, deliberately. A convenience
     * `destination(pattern) { }` taking no [RouteArgs] used to sit beside it and made
     * every ordinary registration ambiguous — `{ HomeScreen() }` satisfies both
     * `() -> Unit` and `(RouteArgs) -> Unit`, so the first module written against the
     * SDK failed to compile on its first destination (OD-209). Nothing is lost: a
     * lambda that ignores its argument needs no parameter list, so
     * `destination("omnideck://notes/home") { HomeScreen() }` still reads exactly the
     * same and now resolves.
     */
    fun destination(pattern: String, content: @Composable (RouteArgs) -> Unit)

    /**
     * A destination shown when the module is [ModuleState.DEGRADED]. Optional; the
     * Shell provides a generic one otherwise.
     */
    fun degradedFallback(content: @Composable (reason: String) -> Unit) = Unit
}

/**
 * Runtime service exchange between modules (architecture.md §10.3).
 *
 * The interface lives in the SDK; the implementation lives in whichever module
 * provides it. Consumers resolve by id and **must** handle `null` — that is exactly
 * what preserves independent deployability, since the providing module may not be
 * installed, may be quarantined, or may not exist yet on an older Shell.
 */
interface CapabilityRegistry {

    fun <T : Any> register(id: CapabilityId, type: Class<T>, provider: () -> T)

    fun <T : Any> resolve(id: CapabilityId, type: Class<T>): T?

    fun isAvailable(id: CapabilityId): Boolean
}

inline fun <reified T : Any> CapabilityRegistry.register(id: CapabilityId, noinline provider: () -> T) =
    register(id, T::class.java, provider)

inline fun <reified T : Any> CapabilityRegistry.resolve(id: CapabilityId): T? = resolve(id, T::class.java)
