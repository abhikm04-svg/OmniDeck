package com.omnideck.kernel.services

import android.os.Build
import android.os.Trace
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.capability.ConsentPurpose
import com.omnideck.sdk.capability.ConsentService
import com.omnideck.sdk.capability.TelemetryService
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where telemetry actually goes. Crashlytics, an OTLP exporter and a debug console
 * are all sinks (OD-601/602); adding one is additive and requires no module change.
 */
interface TelemetrySink {
    fun emit(signal: TelemetrySignal)
}

sealed interface TelemetrySignal {
    val moduleId: ModuleId?
    val attributes: Map<String, Any?>

    data class Event(override val moduleId: ModuleId?, val name: String, override val attributes: Map<String, Any?>) :
        TelemetrySignal
    data class Metric(
        override val moduleId: ModuleId?,
        val name: String,
        val value: Double,
        override val attributes: Map<String, Any?>,
    ) : TelemetrySignal
    data class Breadcrumb(
        override val moduleId: ModuleId?,
        val message: String,
        override val attributes: Map<String, Any?>,
    ) : TelemetrySignal
    data class Error(
        override val moduleId: ModuleId?,
        val throwable: Throwable,
        val message: String?,
        val fatal: Boolean,
        override val attributes: Map<String, Any?>,
    ) : TelemetrySignal
    data class SpanEnd(
        override val moduleId: ModuleId?,
        val name: String,
        val traceId: String,
        val durationMs: Long,
        val ok: Boolean,
        override val attributes: Map<String, Any?>,
    ) : TelemetrySignal
}

/**
 * The kernel's telemetry hub.
 *
 * Two properties that the architecture depends on:
 *
 *  1. **Attribution is not optional.** A module receives a view of this service with
 *     its own id baked in, so every signal is attributable to an owning team. Crash
 *     attribution is the prerequisite for per-module error budgets and quarantine.
 *  2. **Consent gates egress, not collection.** Diagnostics are always gathered
 *     locally; whether they leave the device is decided per purpose by
 *     [ConsentService] (architecture.md §12.5).
 */
@Singleton
class TelemetryHub @Inject constructor() {

    private val sinks = CopyOnWriteArrayList<TelemetrySink>()

    /** Set after construction to avoid a dependency cycle (consent needs telemetry). */
    @Volatile
    var consent: ConsentService? = null

    fun addSink(sink: TelemetrySink) = sinks.add(sink)

    internal fun emit(signal: TelemetrySignal) {
        val purpose = when (signal) {
            is TelemetrySignal.Error -> ConsentPurpose.CRASH_DIAGNOSTICS
            else -> ConsentPurpose.PRODUCT_ANALYTICS
        }
        if (consent?.isGranted(purpose) == false) return
        sinks.forEach { runCatching { it.emit(signal) } }
    }

    /** A view scoped to one module — the only thing modules ever receive. */
    fun scopedTo(moduleId: ModuleId?): TelemetryService = ScopedTelemetry(moduleId)

    private inner class ScopedTelemetry(private val moduleId: ModuleId?) : TelemetryService {

        override fun event(name: String, attributes: Map<String, Any?>) =
            emit(TelemetrySignal.Event(moduleId, name, attributes))

        override fun metric(name: String, value: Double, attributes: Map<String, Any?>) =
            emit(TelemetrySignal.Metric(moduleId, name, value, attributes))

        override fun breadcrumb(message: String, attributes: Map<String, Any?>) =
            emit(TelemetrySignal.Breadcrumb(moduleId, message, attributes))

        override fun recordError(throwable: Throwable, message: String?, fatal: Boolean) =
            emit(TelemetrySignal.Error(moduleId, throwable, message, fatal, emptyMap()))

        override fun startSpan(name: String, attributes: Map<String, Any?>): TelemetryService.Span =
            SpanImpl(moduleId, name, attributes)
    }

    private inner class SpanImpl(
        private val moduleId: ModuleId?,
        private val name: String,
        attributes: Map<String, Any?>,
    ) : TelemetryService.Span {

        override val traceId: String = UUID.randomUUID().toString().replace("-", "").take(32)

        private val startedAt = System.nanoTime()

        /**
         * Also opens a platform trace slice, so the same span a dashboard sees is
         * visible in Perfetto and measurable by Macrobenchmark (OD-213). The
         * *asynchronous* form is deliberate: a synchronous slice must begin and end on
         * one thread, and a span wrapping a coroutine routinely resumes on another —
         * which corrupts the whole trace, not just this slice.
         *
         * API 29+ only; below that the span still reports its duration through
         * telemetry, it is simply absent from device traces. Every call is a no-op
         * unless tracing is actually being captured.
         */
        private val traceCookie = TRACE_COOKIES.incrementAndGet()

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.beginAsyncSection(sectionName(), traceCookie)
            }
        }
        private val attrs = attributes.toMutableMap()
        private var ok = true
        private var closed = false

        override fun setAttribute(key: String, value: Any?) {
            attrs[key] = value
        }

        override fun recordException(throwable: Throwable) {
            ok = false
            emit(TelemetrySignal.Error(moduleId, throwable, name, fatal = false, attributes = attrs))
        }

        override fun setStatus(ok: Boolean, description: String?) {
            this.ok = ok
            description?.let { attrs["status.description"] = it }
        }

        override fun close() {
            if (closed) return
            closed = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.endAsyncSection(sectionName(), traceCookie)
            }
            emit(
                TelemetrySignal.SpanEnd(
                    moduleId = moduleId,
                    name = name,
                    traceId = traceId,
                    durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                    ok = ok,
                    attributes = attrs,
                ),
            )
        }

        /** atrace truncates silently past 127 characters, which loses the match. */
        private fun sectionName(): String = name.take(MAX_TRACE_SECTION_NAME)
    }

    private companion object {
        /** Distinguishes overlapping slices with the same name. */
        val TRACE_COOKIES = java.util.concurrent.atomic.AtomicInteger()

        const val MAX_TRACE_SECTION_NAME = 127
    }
}
