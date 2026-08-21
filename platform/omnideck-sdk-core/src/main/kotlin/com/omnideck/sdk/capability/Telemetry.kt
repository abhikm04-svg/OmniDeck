package com.omnideck.sdk.capability

import com.omnideck.sdk.ModuleId

/**
 * The single logging/metrics/tracing surface a module is allowed to use
 * (architecture.md §15). Every signal is automatically tagged with the calling
 * module's id by the kernel — a module cannot attribute its noise to someone else,
 * and a crash always has an owner.
 *
 * Detekt forbids `android.util.Log` repo-wide so this stays the only path.
 */
interface TelemetryService {

    /** A product-analytics event. Names come from the governed taxonomy (OD-603). */
    fun event(name: String, attributes: Map<String, Any?> = emptyMap())

    /** A numeric measurement. */
    fun metric(name: String, value: Double, attributes: Map<String, Any?> = emptyMap())

    /** Structured, non-PII diagnostic breadcrumb attached to any subsequent crash. */
    fun breadcrumb(message: String, attributes: Map<String, Any?> = emptyMap())

    /** A handled error. Unhandled ones are captured by the Shell automatically. */
    fun recordError(throwable: Throwable, message: String? = null, fatal: Boolean = false)

    /** Starts a trace span; close it to record the duration. */
    fun startSpan(name: String, attributes: Map<String, Any?> = emptyMap()): Span

    interface Span : AutoCloseable {
        val traceId: String
        fun setAttribute(key: String, value: Any?)
        fun recordException(throwable: Throwable)
        fun setStatus(ok: Boolean, description: String? = null)
        override fun close()
    }
}

/** Convenience: time a suspending block and report failures automatically. */
@Suppress("TooGenericExceptionCaught") // records to the span and always rethrows — nothing is swallowed
suspend inline fun <T> TelemetryService.traced(
    name: String,
    attributes: Map<String, Any?> = emptyMap(),
    crossinline block: suspend () -> T,
): T {
    val span = startSpan(name, attributes)
    return try {
        block().also { span.setStatus(ok = true) }
    } catch (t: Throwable) {
        span.recordException(t)
        span.setStatus(ok = false, description = t.message)
        throw t
    } finally {
        span.close()
    }
}

/** Identifies which module a telemetry signal came from. Set by the kernel, not by modules. */
data class TelemetryScope(val moduleId: ModuleId?, val hostVersion: String)
