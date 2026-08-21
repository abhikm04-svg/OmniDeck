package com.omnideck.kernel.events

import com.omnideck.sdk.capability.EventBus
import com.omnideck.sdk.capability.PlatformEvent
import com.omnideck.sdk.capability.TelemetryService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local, typed, lossy-by-design event bus (architecture.md §10.3).
 *
 * `BufferOverflow.DROP_OLDEST` is a deliberate choice: an event bus that applies
 * backpressure lets a slow subscriber stall a publisher, which in a super-app means
 * one module can freeze another. Events are facts, not commands — if a subscriber is
 * too slow to hear one, dropping it is correct. Anything that must not be dropped is
 * a Capability call, not an event.
 */
@Singleton
class EventBusImpl @Inject constructor(private val telemetry: TelemetryService) : EventBus {

    private val flow = MutableSharedFlow<PlatformEvent>(
        replay = REPLAY,
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun publish(event: PlatformEvent) {
        if (!flow.tryEmit(event)) {
            telemetry.event("event_bus_dropped", mapOf("type" to event::class.java.simpleName))
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : PlatformEvent> subscribe(type: Class<T>): Flow<T> = flow.asSharedFlow()
        .filter { type.isInstance(it) }
        .map { it as T }

    private companion object {
        /** Enough that a module initialising late still sees recent session/theme facts. */
        const val REPLAY = 8
        const val BUFFER = 64
    }
}
