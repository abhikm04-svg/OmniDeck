package com.omnideck.sdk.sync

import com.google.common.truth.Truth.assertThat
import com.omnideck.core.MutableClock
import com.omnideck.sdk.capability.TelemetryService
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random

/**
 * OD-210. What is asserted here is the behaviour a module must be able to rely on
 * without re-reading the engine: ordering, exactly-once delivery, backoff growth, and
 * that nothing is ever silently dropped.
 *
 * The doubles below are deliberately local rather than the ones in `:platform:testing`
 * — that module depends on this one, so consuming it here would be a project cycle.
 * They also serve a different purpose: these record every engine call, where the
 * published fakes are a module-author harness.
 */
class SyncEngineTest {

    private val clock = MutableClock(startMillis = 1_000)
    private val telemetry = RecordingTelemetry()

    @Test
    fun `an accepted record is removed and reported once`() = runTest {
        val outbox = TestOutbox(record(id = 1))
        val transport = TestTransport(PushOutcome.Applied(remoteVersion = 7))
        val sink = TestSink()

        val report = engine(outbox, transport, sink = sink).drain()

        assertThat(report.pushed).isEqualTo(1)
        assertThat(report.remaining).isEqualTo(0)
        assertThat(outbox.removed).containsExactly(1L)
        assertThat(sink.applied).containsExactly(1L to 7L)
    }

    @Test
    fun `records are delivered oldest first`() = runTest {
        val outbox = TestOutbox(record(id = 3), record(id = 1), record(id = 2))
        val transport = TestTransport()

        engine(outbox, transport).drain()

        assertThat(transport.pushed.map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `a retryable failure stops the batch so later edits cannot overtake it`() = runTest {
        val outbox = TestOutbox(record(id = 1), record(id = 2))
        val transport = TestTransport(PushOutcome.Retryable("offline"))

        val report = engine(outbox, transport).drain()

        assertThat(transport.pushed.map { it.id }).containsExactly(1L)
        assertThat(report.retried).isEqualTo(1)
        assertThat(report.stopped).isTrue()
        assertThat(outbox.removed).isEmpty()
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val policy = BackoffPolicy(jitter = 0.0, maxAttempts = 20)
        val first = policy.delayFor(1)
        val second = policy.delayFor(2)
        val far = policy.delayFor(20)

        assertThat(second).isEqualTo(first * 2)
        assertThat(far).isEqualTo(policy.max)
    }

    @Test
    fun `jitter keeps retries within the configured spread`() {
        val policy = BackoffPolicy(jitter = 0.2, maxAttempts = 20)
        val nominal = BackoffPolicy(jitter = 0.0, maxAttempts = 20).delayFor(3).inWholeMilliseconds

        repeat(JITTER_SAMPLES) { seed ->
            val actual = policy.delayFor(3, Random(seed)).inWholeMilliseconds
            assertThat(actual).isAtLeast((nominal * (1 - 0.2)).toLong())
            assertThat(actual).isAtMost((nominal * (1 + 0.2)).toLong())
        }
    }

    @Test
    fun `the next attempt is scheduled in the future, not immediately`() = runTest {
        val outbox = TestOutbox(record(id = 1))

        engine(outbox, TestTransport(PushOutcome.Retryable("5xx"))).drain()

        val (_, attempt, nextAt) = outbox.rescheduled.single()
        assertThat(attempt).isEqualTo(1)
        assertThat(nextAt).isGreaterThan(clock.nowMillis())
    }

    @Test
    fun `a record that exhausts its attempts is dead lettered, never dropped`() = runTest {
        val outbox = TestOutbox(record(id = 1, attempt = 2))
        val sink = TestSink()

        val report = engine(
            outbox,
            TestTransport(PushOutcome.Retryable("still offline")),
            backoff = BackoffPolicy(maxAttempts = 3),
            sink = sink,
        ).drain()

        assertThat(report.deadLettered).isEqualTo(1)
        assertThat(outbox.deadLettered).hasSize(1)
        assertThat(outbox.removed).isEmpty()
        assertThat(sink.deadLettered).containsExactly(1L)
    }

    @Test
    fun `a permanent rejection is dead lettered without burning retries`() = runTest {
        val outbox = TestOutbox(record(id = 1))

        val report = engine(outbox, TestTransport(PushOutcome.Rejected("schema"))).drain()

        assertThat(report.deadLettered).isEqualTo(1)
        assertThat(outbox.rescheduled).isEmpty()
    }

    @Test
    fun `last-write-wins rebases the local edit onto the server version and re-pushes`() = runTest {
        val outbox = TestOutbox(record(id = 1, createdAtEpochMs = 500, payload = "local"))
        val transport = TestTransport(
            PushOutcome.Conflict(remoteVersion = 9, remotePayload = "remote", remoteUpdatedAtEpochMs = 100),
            PushOutcome.Applied(remoteVersion = 10),
        )

        val report = engine(outbox, transport).drain()

        assertThat(report.conflicts).isEqualTo(1)
        assertThat(report.pushed).isEqualTo(1)
        assertThat(transport.pushed).hasSize(2)
        assertThat(transport.pushed[1].baseVersion).isEqualTo(9)
        assertThat(transport.pushed[1].payload).isEqualTo("local")
        assertThat(outbox.removed).containsExactly(1L)
    }

    @Test
    fun `last-write-wins yields to a newer server edit`() = runTest {
        val outbox = TestOutbox(record(id = 1, createdAtEpochMs = 100))
        val transport = TestTransport(
            PushOutcome.Conflict(remoteVersion = 9, remotePayload = "remote", remoteUpdatedAtEpochMs = 500),
        )
        val sink = TestSink()

        engine(outbox, transport, sink = sink).drain()

        assertThat(transport.pushed).hasSize(1)
        assertThat(sink.remoteAccepted).containsExactly(1L)
        assertThat(outbox.removed).containsExactly(1L)
    }

    @Test
    fun `server-wins never re-pushes`() = runTest {
        val outbox = TestOutbox(record(id = 1, createdAtEpochMs = Long.MAX_VALUE))
        val transport = TestTransport(
            PushOutcome.Conflict(remoteVersion = 2, remotePayload = null, remoteUpdatedAtEpochMs = 0),
        )

        engine(outbox, transport, conflicts = ConflictResolver.serverWins()).drain()

        assertThat(transport.pushed).hasSize(1)
        assertThat(outbox.removed).containsExactly(1L)
    }

    @Test
    fun `a conflict that survives resolution backs off instead of spinning`() = runTest {
        val outbox = TestOutbox(record(id = 1, createdAtEpochMs = 500))
        val transport = TestTransport(
            PushOutcome.Conflict(remoteVersion = 2, remotePayload = null, remoteUpdatedAtEpochMs = 0),
            PushOutcome.Conflict(remoteVersion = 3, remotePayload = null, remoteUpdatedAtEpochMs = 0),
        )

        val report = engine(outbox, transport).drain()

        assertThat(transport.pushed).hasSize(2)
        assertThat(report.retried).isEqualTo(1)
        assertThat(outbox.rescheduled).hasSize(1)
    }

    @Test
    fun `a transport that throws is contained and treated as transient`() = runTest {
        val outbox = TestOutbox(record(id = 1))
        val transport = TestTransport().apply { throws = IllegalStateException("boom") }

        val report = engine(outbox, transport).drain()

        assertThat(report.retried).isEqualTo(1)
        assertThat(telemetry.errors).hasSize(1)
        assertThat(outbox.removed).isEmpty()
    }

    @Test
    fun `a record whose backoff has not elapsed is not attempted`() = runTest {
        val outbox = TestOutbox(record(id = 1, nextAttemptAtEpochMs = clock.nowMillis() + 60_000))
        val transport = TestTransport()

        val report = engine(outbox, transport).drain()

        assertThat(transport.pushed).isEmpty()
        assertThat(report.remaining).isEqualTo(1)
    }

    @Test
    fun `state reports pending work and the last failure`() = runTest {
        val outbox = TestOutbox(record(id = 1))
        val engine = engine(outbox, TestTransport(PushOutcome.Retryable("offline")))

        assertThat(engine.state.value).isEqualTo(SyncState.Idle(pending = 0))
        engine.drain()

        val state = engine.state.value
        assertThat(state).isInstanceOf(SyncState.Failed::class.java)
        assertThat(state.pending).isEqualTo(1)
    }

    @Test
    fun `an empty outbox is a no-op that still reports the queue depth`() = runTest {
        val outbox = TestOutbox()
        val transport = TestTransport()

        val report = engine(outbox, transport).drain()

        assertThat(transport.pushed).isEmpty()
        assertThat(report).isEqualTo(SyncReport(remaining = 0))
    }

    @Test
    fun `the batch size bounds one drain`() = runTest {
        val outbox = TestOutbox(record(id = 1), record(id = 2), record(id = 3))

        val transport = TestTransport()
        engine(outbox, transport, batchSize = 2).drain()

        assertThat(transport.pushed).hasSize(2)
    }

    @Test
    fun `backoff rejects a nonsensical configuration`() {
        runCatching { BackoffPolicy(maxAttempts = 0) }.let { assertThat(it.isFailure).isTrue() }
        runCatching { BackoffPolicy(jitter = 2.0) }.let { assertThat(it.isFailure).isTrue() }
        runCatching { BackoffPolicy().delayFor(0) }.let { assertThat(it.isFailure).isTrue() }
    }

    // -----------------------------------------------------------------------

    private fun engine(
        outbox: Outbox,
        transport: SyncTransport,
        conflicts: ConflictResolver = ConflictResolver.lastWriteWins(),
        sink: SyncSink = SyncSink.NoOp,
        backoff: BackoffPolicy = BackoffPolicy(),
        batchSize: Int = 50,
    ) = SyncEngine(
        outbox = outbox,
        transport = transport,
        clock = clock,
        telemetry = telemetry,
        conflicts = conflicts,
        sink = sink,
        backoff = backoff,
        batchSize = batchSize,
        random = Random(SEED),
    )

    private fun record(
        id: Long,
        payload: String? = "{}",
        baseVersion: Long = 0,
        createdAtEpochMs: Long = 0,
        attempt: Int = 0,
        nextAttemptAtEpochMs: Long = 0,
    ) = OutboxRecord(
        id = id,
        entityType = "note",
        entityId = "n$id",
        operation = OutboxRecord.Operation.UPSERT,
        payload = payload,
        baseVersion = baseVersion,
        createdAtEpochMs = createdAtEpochMs,
        attempt = attempt,
        nextAttemptAtEpochMs = nextAttemptAtEpochMs,
    )

    private companion object {
        const val SEED = 42
        const val JITTER_SAMPLES = 200
    }
}

private class TestOutbox(vararg initial: OutboxRecord) : Outbox {
    private val records = initial.toMutableList()
    val removed = mutableListOf<Long>()
    val rescheduled = mutableListOf<Triple<Long, Int, Long>>()
    val deadLettered = mutableListOf<Pair<Long, String>>()

    override suspend fun due(nowEpochMs: Long, limit: Int) =
        records.filter { it.nextAttemptAtEpochMs <= nowEpochMs }.sortedBy { it.id }.take(limit)

    override suspend fun remove(id: Long) {
        removed += id
        records.removeAll { it.id == id }
    }

    override suspend fun reschedule(id: Long, attempt: Int, nextAttemptAtEpochMs: Long, lastError: String?) {
        rescheduled += Triple(id, attempt, nextAttemptAtEpochMs)
        val index = records.indexOfFirst { it.id == id }
        if (index >= 0) {
            records[index] = records[index].copy(attempt = attempt, nextAttemptAtEpochMs = nextAttemptAtEpochMs)
        }
    }

    override suspend fun deadLetter(id: Long, reason: String) {
        deadLettered += id to reason
    }

    override suspend fun size() = records.size
}

private class TestTransport(vararg scripted: PushOutcome) : SyncTransport {
    private val queue = ArrayDeque(scripted.toList())
    val pushed = mutableListOf<OutboxRecord>()
    var throws: Throwable? = null

    override suspend fun push(record: OutboxRecord): PushOutcome {
        pushed += record
        throws?.let { throw it }
        return queue.removeFirstOrNull() ?: PushOutcome.Applied(remoteVersion = 1)
    }
}

private class TestSink : SyncSink {
    val applied = mutableListOf<Pair<Long, Long>>()
    val remoteAccepted = mutableListOf<Long>()
    val deadLettered = mutableListOf<Long>()

    override suspend fun onApplied(record: OutboxRecord, remoteVersion: Long) {
        applied += record.id to remoteVersion
    }

    override suspend fun onRemoteAccepted(record: OutboxRecord, conflict: PushOutcome.Conflict) {
        remoteAccepted += record.id
    }

    override suspend fun onDeadLettered(record: OutboxRecord, reason: String) {
        deadLettered += record.id
    }
}

private class RecordingTelemetry : TelemetryService {
    val events = mutableListOf<String>()
    val errors = mutableListOf<Throwable>()

    override fun event(name: String, attributes: Map<String, Any?>) {
        events += name
    }

    override fun metric(name: String, value: Double, attributes: Map<String, Any?>) = Unit

    override fun breadcrumb(message: String, attributes: Map<String, Any?>) = Unit

    override fun recordError(throwable: Throwable, message: String?, fatal: Boolean) {
        errors += throwable
    }

    override fun startSpan(name: String, attributes: Map<String, Any?>) = object : TelemetryService.Span {
        override val traceId = "test"
        override fun setAttribute(key: String, value: Any?) = Unit
        override fun recordException(throwable: Throwable) = Unit
        override fun setStatus(ok: Boolean, description: String?) = Unit
        override fun close() = Unit
    }
}
