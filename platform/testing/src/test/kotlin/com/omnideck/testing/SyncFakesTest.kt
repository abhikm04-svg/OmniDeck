package com.omnideck.testing

import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.sync.OutboxRecord
import com.omnideck.sdk.sync.PushOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The sync harness a module author writes offline-first tests against (OD-210).
 *
 * A fake that quietly gets the queue semantics wrong is worse than no fake: every
 * module built on it would pass tests that prove nothing about ordering or retry. So
 * the fakes are held to the same behaviour the real Room-backed outbox has.
 */
class FakeOutboxTest {

    private val outbox = FakeOutbox()

    @Test
    fun `records come back oldest first`() = runTest {
        outbox.enqueue("note", "a")
        outbox.enqueue("note", "b")

        assertThat(outbox.due(NOW, limit = 10).map { it.entityId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `the limit bounds the batch`() = runTest {
        repeat(5) { outbox.enqueue("note", "n$it") }

        assertThat(outbox.due(NOW, limit = 2)).hasSize(2)
    }

    @Test
    fun `a rescheduled record is withheld until its slot arrives`() = runTest {
        val id = outbox.enqueue("note", "a")

        outbox.reschedule(id, attempt = 1, nextAttemptAtEpochMs = NOW + 1_000, lastError = "offline")

        assertThat(outbox.due(NOW, 10)).isEmpty()
        val later = outbox.due(NOW + 1_000, 10).single()
        assertThat(later.attempt).isEqualTo(1)
        assertThat(later.lastError).isEqualTo("offline")
        assertThat(outbox.rescheduled).containsExactly(Triple(id, 1, NOW + 1_000))
    }

    @Test
    fun `a dead letter leaves the queue but stays available to assert on`() = runTest {
        val id = outbox.enqueue("note", "a")

        outbox.deadLetter(id, "rejected")

        assertThat(outbox.due(NOW, 10)).isEmpty()
        assertThat(outbox.deadLettered.single().second).isEqualTo("rejected")
        // Still in the snapshot: a dropped record would hide data loss from the test
        // exactly as it hides it from the user.
        assertThat(outbox.snapshot()).hasSize(1)
    }

    @Test
    fun `removing a delivered record drops it entirely`() = runTest {
        val id = outbox.enqueue("note", "a")

        outbox.remove(id)

        assertThat(outbox.size()).isEqualTo(0)
        assertThat(outbox.snapshot()).isEmpty()
    }

    @Test
    fun `enqueue carries every field the engine reads`() = runTest {
        outbox.enqueue(
            entityType = "note",
            entityId = "a",
            operation = OutboxRecord.Operation.DELETE,
            payload = null,
            baseVersion = 4,
            createdAtEpochMs = 99,
        )

        val record = outbox.due(NOW, 1).single()
        assertThat(record.operation).isEqualTo(OutboxRecord.Operation.DELETE)
        assertThat(record.payload).isNull()
        assertThat(record.baseVersion).isEqualTo(4)
        assertThat(record.createdAtEpochMs).isEqualTo(99)
    }

    private companion object {
        const val NOW = 1_700_000_000_000
    }
}

class FakeSyncTransportTest {

    private val record = OutboxRecord(
        id = 1,
        entityType = "note",
        entityId = "a",
        operation = OutboxRecord.Operation.UPSERT,
        payload = "{}",
    )

    @Test
    fun `scripted outcomes are consumed in order, then the default repeats`() = runTest {
        val transport = FakeSyncTransport().script(
            PushOutcome.Retryable("offline"),
            PushOutcome.Applied(remoteVersion = 2),
        )

        assertThat(transport.push(record)).isEqualTo(PushOutcome.Retryable("offline"))
        assertThat(transport.push(record)).isEqualTo(PushOutcome.Applied(remoteVersion = 2))
        assertThat(transport.push(record)).isEqualTo(PushOutcome.Applied(remoteVersion = 1))
    }

    @Test
    fun `every push is recorded, so a test can assert what was actually sent`() = runTest {
        val transport = FakeSyncTransport()

        transport.push(record)
        transport.push(record.copy(id = 2))

        assertThat(transport.pushed.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `a transport can be made to throw, to exercise the engine's containment`() = runTest {
        val transport = FakeSyncTransport().apply { throws = IllegalStateException("boom") }

        val thrown = runCatching { transport.push(record) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(transport.pushed).hasSize(1)
    }

    @Test
    fun `the default outcome is configurable`() = runTest {
        val transport = FakeSyncTransport().apply { default = PushOutcome.Rejected("nope") }

        assertThat(transport.push(record)).isEqualTo(PushOutcome.Rejected("nope"))
    }
}

class RecordingSyncSinkTest {

    private val sink = RecordingSyncSink()
    private val record = OutboxRecord(
        id = 1,
        entityType = "note",
        entityId = "a",
        operation = OutboxRecord.Operation.UPSERT,
        payload = "{}",
    )

    @Test
    fun `each outcome lands in its own list`() = runTest {
        sink.onApplied(record, remoteVersion = 3)
        sink.onRemoteAccepted(
            record,
            PushOutcome.Conflict(remoteVersion = 4, remotePayload = null, remoteUpdatedAtEpochMs = 0),
        )
        sink.onDeadLettered(record, "rejected")

        assertThat(sink.applied).containsExactly(record to 3L)
        assertThat(sink.remoteAccepted).containsExactly(record)
        assertThat(sink.deadLettered).containsExactly(record to "rejected")
    }
}
