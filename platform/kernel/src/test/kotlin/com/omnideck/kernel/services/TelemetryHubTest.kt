package com.omnideck.kernel.services

import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.capability.ConsentPurpose
import com.omnideck.sdk.capability.ConsentService
import com.omnideck.sdk.capability.ConsentState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

/**
 * Two properties matter here and neither is cosmetic.
 *
 * **Attribution** — every signal carries the id of the module that produced it. Without
 * it, per-module error budgets and the quarantine counter measure nothing, and a crash
 * has no owner.
 *
 * **Consent gating** — analytics stop when the user withdraws consent, and crash
 * diagnostics are gated separately, because they are a different purpose with a
 * different lawful basis. Collapsing the two is a compliance failure, not a bug.
 */
class TelemetryHubTest {

    private class RecordingSink : TelemetrySink {
        val signals = mutableListOf<TelemetrySignal>()
        override fun emit(signal: TelemetrySignal) {
            signals += signal
        }
    }

    private class StubConsent(private val granted: Set<ConsentPurpose>) : ConsentService {
        override val state: Flow<ConsentState> = flowOf(ConsentState(granted, 0L))
        override fun isGranted(purpose: ConsentPurpose) = purpose in granted
        override suspend fun request(purpose: ConsentPurpose) = purpose in granted
    }

    private val notes = ModuleId("com.omnideck.notes")

    // -- attribution --------------------------------------------------------

    @Test
    fun `signals carry the module that produced them`() {
        val sink = RecordingSink()
        val hub = TelemetryHub().apply { addSink(sink) }

        hub.scopedTo(notes).event("note_saved")

        val event = sink.signals.single() as TelemetrySignal.Event
        assertThat(event.moduleId).isEqualTo(notes)
        assertThat(event.name).isEqualTo("note_saved")
    }

    @Test
    fun `a module cannot attribute its noise to another module`() {
        // Scoping is by construction: a module only ever holds its own view.
        val sink = RecordingSink()
        val hub = TelemetryHub().apply { addSink(sink) }

        hub.scopedTo(notes).event("from_notes")
        hub.scopedTo(ModuleId("com.omnideck.finance")).event("from_finance")

        val owners = sink.signals.map { (it as TelemetrySignal.Event).moduleId?.value }
        assertThat(owners).containsExactly("com.omnideck.notes", "com.omnideck.finance").inOrder()
    }

    @Test
    fun `shell-scoped signals have no module id`() {
        val sink = RecordingSink()
        val hub = TelemetryHub().apply { addSink(sink) }

        hub.scopedTo(null).event("shell_started")

        assertThat((sink.signals.single() as TelemetrySignal.Event).moduleId).isNull()
    }

    @Test
    fun `every signal kind reaches the sink`() {
        val sink = RecordingSink()
        val telemetry = TelemetryHub().apply { addSink(sink) }.scopedTo(notes)

        telemetry.event("e")
        telemetry.metric("m", 1.0)
        telemetry.breadcrumb("b")
        telemetry.recordError(IllegalStateException("x"))
        telemetry.startSpan("s").close()

        assertThat(sink.signals.map { it::class.simpleName })
            .containsExactly("Event", "Metric", "Breadcrumb", "Error", "SpanEnd")
    }

    // -- fan-out ------------------------------------------------------------

    @Test
    fun `all sinks receive every signal`() {
        val a = RecordingSink()
        val b = RecordingSink()
        val hub = TelemetryHub().apply { addSink(a); addSink(b) }

        hub.scopedTo(notes).event("e")

        assertThat(a.signals).hasSize(1)
        assertThat(b.signals).hasSize(1)
    }

    @Test
    fun `a throwing sink does not stop the others`() {
        // An exporter failing must never take down the app it is reporting on.
        val exploding = object : TelemetrySink {
            override fun emit(signal: TelemetrySignal) = error("exporter down")
        }
        val healthy = RecordingSink()
        val hub = TelemetryHub().apply { addSink(exploding); addSink(healthy) }

        hub.scopedTo(notes).event("e")

        assertThat(healthy.signals).hasSize(1)
    }

    // -- consent gating -----------------------------------------------------

    @Test
    fun `analytics are dropped when product analytics consent is withheld`() {
        val sink = RecordingSink()
        val hub = TelemetryHub().apply {
            addSink(sink)
            consent = StubConsent(setOf(ConsentPurpose.ESSENTIAL))
        }

        hub.scopedTo(notes).event("tracked_thing")

        assertThat(sink.signals).isEmpty()
    }

    @Test
    fun `crash diagnostics are gated on their own purpose, not on analytics consent`() {
        // Different purposes, different lawful bases — a user may allow crash reports
        // while refusing analytics, and the reverse.
        val sink = RecordingSink()
        val hub = TelemetryHub().apply {
            addSink(sink)
            consent = StubConsent(setOf(ConsentPurpose.CRASH_DIAGNOSTICS))
        }

        hub.scopedTo(notes).event("tracked_thing")
        hub.scopedTo(notes).recordError(IllegalStateException("boom"))

        assertThat(sink.signals.map { it::class.simpleName }).containsExactly("Error")
    }

    @Test
    fun `everything flows when no consent service is attached`() {
        // Consent is wired after construction to break a dependency cycle; until then
        // the platform must not silently discard its own startup telemetry.
        val sink = RecordingSink()
        val hub = TelemetryHub().apply { addSink(sink) }

        hub.scopedTo(notes).event("e")

        assertThat(sink.signals).hasSize(1)
    }

    // -- spans --------------------------------------------------------------

    @Test
    fun `a span emits once on close with its name and duration`() {
        val sink = RecordingSink()
        val hub = TelemetryHub().apply { addSink(sink) }

        hub.scopedTo(notes).startSpan("module.activate").close()

        val span = sink.signals.single() as TelemetrySignal.SpanEnd
        assertThat(span.name).isEqualTo("module.activate")
        assertThat(span.ok).isTrue()
        assertThat(span.durationMs).isAtLeast(0)
    }

    @Test
    fun `closing a span twice emits once`() {
        // `use { }` closes, and callers often close explicitly too.
        val sink = RecordingSink()
        val span = TelemetryHub().apply { addSink(sink) }.scopedTo(notes).startSpan("s")

        span.close()
        span.close()

        assertThat(sink.signals.filterIsInstance<TelemetrySignal.SpanEnd>()).hasSize(1)
    }

    @Test
    fun `a failed status is carried through to the span end`() {
        val sink = RecordingSink()
        val span = TelemetryHub().apply { addSink(sink) }.scopedTo(notes).startSpan("s")

        span.setStatus(ok = false, description = "gated")
        span.close()

        val end = sink.signals.filterIsInstance<TelemetrySignal.SpanEnd>().single()
        assertThat(end.ok).isFalse()
    }

    @Test
    fun `recording an exception on a span emits an error and marks it failed`() {
        val sink = RecordingSink()
        val span = TelemetryHub().apply { addSink(sink) }.scopedTo(notes).startSpan("s")
        val boom = IllegalStateException("boom")

        span.recordException(boom)
        span.close()

        assertThat(sink.signals.filterIsInstance<TelemetrySignal.Error>().single().throwable)
            .isSameInstanceAs(boom)
        assertThat(sink.signals.filterIsInstance<TelemetrySignal.SpanEnd>().single().ok).isFalse()
    }

    @Test
    fun `each span gets its own trace id`() {
        val telemetry = TelemetryHub().scopedTo(notes)

        val first = telemetry.startSpan("a").traceId
        val second = telemetry.startSpan("b").traceId

        assertThat(first).isNotEqualTo(second)
        // 32 hex chars, matching the W3C traceparent trace-id field.
        assertThat(first).matches("[0-9a-f]{32}")
    }
}
