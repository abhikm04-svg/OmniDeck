package com.omnideck.kernel.router

import com.omnideck.kernel.lifecycle.ModuleLifecycleManager
import com.omnideck.sdk.CorrelationId
import com.omnideck.sdk.InstallProgress
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.Route
import com.omnideck.sdk.capability.NavResult
import com.omnideck.sdk.capability.NavResultValue
import com.omnideck.sdk.capability.Router
import com.omnideck.sdk.capability.TelemetryService
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What the Shell's NavHost listens to. Kept separate so the Router has no Compose deps. */
interface NavigationCommandSink {
    fun navigate(route: Route)
    fun back(): Boolean
}

/**
 * Implements architecture.md §10.1.
 *
 * The behaviour that matters is in [navigate]: if the module owning a route is not
 * installed, the Router runs the whole acquisition flow and *then* continues to the
 * destination. Callers — a module, a notification tap, an App Link — write the same
 * one line either way. That is the entire "one-click shop" experience, implemented
 * once, in the platform.
 */
@Singleton
class RouterImpl @Inject constructor(
    private val destinations: MutableDestinationRegistry,
    private val lifecycle: ModuleLifecycleManager,
    private val telemetry: TelemetryService,
    private val sink: NavigationCommandSink,
) : Router {

    private val results = MutableSharedFlow<Pair<CorrelationId, ResultSignal>>(extraBufferCapacity = 32)
    private val pending = ConcurrentHashMap<CorrelationId, Route>()

    /** A produced result, or the absence of one. Distinguishing them is all of OD-205. */
    private sealed interface ResultSignal {
        data class Produced(val value: Any) : ResultSignal
        data object Abandoned : ResultSignal
    }

    override suspend fun navigate(route: Route): NavResult {
        telemetry.breadcrumb("navigate", mapOf("route" to route.uri))

        val owner = ownerOf(route) ?: return NavResult.Unhandled(route).also {
            telemetry.event("nav_unhandled", mapOf("route" to route.uri))
        }

        // Shell-owned destinations — Home, Settings, the Privacy Centre — are in the
        // same route table as modules but have no lifecycle: nothing to install, gate
        // or quarantine. Without this they would fall through to the acquisition flow
        // and fail on a module id the lifecycle manager has never heard of.
        if (owner !in lifecycle.modules.value) {
            sink.navigate(route)
            return NavResult.Navigated(route)
        }

        return when (lifecycle.stateOf(owner)) {
            ModuleState.ACTIVE, ModuleState.DEGRADED -> {
                sink.navigate(route)
                NavResult.Navigated(route)
            }

            ModuleState.INSTALLED, ModuleState.SUSPENDED, ModuleState.INITIALIZING -> {
                val runtime = lifecycle.activate(owner)
                if (runtime.state.isUsable) {
                    sink.navigate(route)
                    NavResult.Navigated(route)
                } else {
                    NavResult.Unavailable(owner, runtime.reason ?: "Module is unavailable.")
                }
            }

            ModuleState.ADVERTISED, ModuleState.GATED -> acquireThenNavigate(owner, route)

            ModuleState.QUARANTINED ->
                NavResult.Unavailable(owner, lifecycle.modules.value[owner]?.reason ?: "Temporarily unavailable.")

            ModuleState.INSTALLING, ModuleState.PURGING ->
                NavResult.Unavailable(owner, "Busy — try again in a moment.")

            ModuleState.FAILED ->
                NavResult.Unavailable(owner, "This module could not be loaded.")
        }
    }

    /**
     * Navigates and awaits one typed result (§10.2, OD-205).
     *
     * The correlation id travels **in the route's query string**, not in memory. That
     * is what lets the destination find it: it arrives in the destination's
     * `RouteArgs` like any other parameter, and the route itself is held in the
     * navigation back stack, which Android saves and restores across process death.
     *
     * Cold by design — nothing navigates until the flow is collected, so a caller that
     * builds a flow and discards it has not moved the user. The subscription is
     * established *before* navigating, so a destination that returns a result
     * immediately cannot beat the collector to it.
     */
    override fun <T : Any> navigateForResult(route: Route, type: Class<T>): Flow<NavResultValue<T>> {
        val correlationId = CorrelationId(java.util.UUID.randomUUID().toString())

        return channelFlow {
            pending[correlationId] = route

            val awaiting = launch {
                results.filter { (id, _) -> id == correlationId }
                    .take(1)
                    .collect { (_, signal) ->
                        send(
                            when (signal) {
                                is ResultSignal.Produced -> asResultValue(signal.value, type)
                                ResultSignal.Abandoned -> NavResultValue.Cancelled
                            },
                        )
                    }
            }

            try {
                when (val outcome = navigate(route.withCorrelationId(correlationId))) {
                    is NavResult.Navigated, is NavResult.NavigatedAfterInstall -> awaiting.join()

                    // Never reached the destination, so no result is coming. Reporting
                    // the reason beats leaving the caller suspended forever.
                    is NavResult.Unavailable -> failed(awaiting, outcome.reason)
                    is NavResult.AcquisitionAborted -> failed(awaiting, outcome.reason)
                    is NavResult.Unhandled -> failed(awaiting, "Nothing handles ${route.uri}.")
                }
            } finally {
                pending.remove(correlationId)
            }
        }
    }

    private suspend fun ProducerScope<*>.failed(awaiting: Job, reason: String) {
        awaiting.cancel()
        @Suppress("UNCHECKED_CAST")
        (this as ProducerScope<NavResultValue<Nothing>>).send(NavResultValue.Failed(reason))
    }

    private fun <T : Any> asResultValue(value: Any, type: Class<T>): NavResultValue<T> = if (type.isInstance(value)) {
        @Suppress("UNCHECKED_CAST")
        NavResultValue.Success(value as T)
    } else {
        // A type mismatch is the destination's bug, but it must surface as a value
        // rather than a ClassCastException thrown inside the caller's collector.
        NavResultValue.Failed(
            "Result type mismatch: expected ${type.simpleName}, got ${value::class.java.simpleName}",
        )
    }

    override fun canHandle(route: Route): Boolean = ownerOf(route) != null

    override fun back(): Boolean = sink.back()

    override fun <T : Any> setResult(correlationId: CorrelationId, value: T) {
        pending.remove(correlationId)
        results.tryEmit(correlationId to ResultSignal.Produced(value))
    }

    /**
     * The user left without producing a result (OD-205).
     *
     * Called by the Shell when a destination carrying a correlation id is popped.
     * Without it a caller of `navigateForResult` waits for ever on a screen the user
     * dismissed — the flow never completes, and whatever it was gating never runs.
     * Kernel-facing rather than part of the SDK's `Router`: abandonment is something
     * the Shell observes about its own back stack, not something a module reports.
     */
    fun abandon(correlationId: CorrelationId) {
        // Only for ids we actually handed out; a stale one from a restored back stack
        // has no collector left and must not resurrect a completed flow.
        if (pending.remove(correlationId) == null) return
        telemetry.event("nav_result_abandoned")
        results.tryEmit(correlationId to ResultSignal.Abandoned)
    }

    /** The acquisition flow: entitlement + download + init, then continue to the route. */
    private suspend fun acquireThenNavigate(owner: ModuleId, route: Route): NavResult {
        telemetry.event("module_acquisition_started", mapOf("module.id" to owner.value))

        val terminal = lifecycle.install(owner)
            .filter {
                it is InstallProgress.Installed || it is InstallProgress.Failed || it is InstallProgress.Canceled
            }
            .first()

        return when (terminal) {
            is InstallProgress.Installed -> {
                val runtime = lifecycle.activate(owner)
                if (runtime.state.isUsable) {
                    sink.navigate(route)
                    telemetry.event("module_acquisition_completed", mapOf("module.id" to owner.value))
                    NavResult.NavigatedAfterInstall(route, owner)
                } else {
                    NavResult.Unavailable(owner, runtime.reason ?: "Module failed to start.")
                }
            }

            is InstallProgress.Failed -> NavResult.AcquisitionAborted(owner, terminal.message)
            else -> NavResult.AcquisitionAborted(owner, "Install cancelled.")
        }
    }

    /**
     * Route ownership is resolvable *before* a module is loaded: the short id in the
     * route maps to a discovered module. Without this, an uninstalled module's deep
     * links would be dead — which would break the whole acquisition flow.
     */
    private fun ownerOf(route: Route): ModuleId? = destinations.ownerOf(route)
        ?: lifecycle.modules.value.keys.firstOrNull { it.shortId == route.host }
}
