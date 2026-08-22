package com.omnideck.notes.data

import com.google.common.truth.Truth.assertThat
import com.omnideck.notes.NotesTestFixture
import com.omnideck.sdk.sync.OutboxRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The adapter between this module's table and the SDK's `Outbox` contract.
 *
 * What is worth testing here is not the SQL but the guarantees the engine relies on:
 * strict id order, backoff actually withholding a record, and a dead letter staying
 * out of the queue without disappearing from the database.
 */
@RunWith(RobolectricTestRunner::class)
class RoomOutboxTest {

    private val fixture = NotesTestFixture()
    private val dao = fixture.database.outbox()
    private val outbox = RoomOutbox(dao)

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `records come back in insertion order`() = runTest {
        insert("a")
        insert("b")
        insert("c")

        assertThat(outbox.due(NOW, limit = 10).map { it.entityId })
            .containsExactly("a", "b", "c")
            .inOrder()
    }

    @Test
    fun `the limit bounds the batch`() = runTest {
        repeat(5) { insert("n$it") }

        assertThat(outbox.due(NOW, limit = 2)).hasSize(2)
    }

    @Test
    fun `a rescheduled record is withheld until its slot arrives`() = runTest {
        val id = insert("a")

        outbox.reschedule(id, attempt = 1, nextAttemptAtEpochMs = NOW + 5_000, lastError = "offline")

        assertThat(outbox.due(NOW, 10)).isEmpty()
        assertThat(outbox.due(NOW + 5_000, 10)).hasSize(1)
        assertThat(outbox.due(NOW + 5_000, 10).single().attempt).isEqualTo(1)
        assertThat(outbox.due(NOW + 5_000, 10).single().lastError).isEqualTo("offline")
    }

    @Test
    fun `a dead letter leaves the queue but not the database`() = runTest {
        val id = insert("a")

        outbox.deadLetter(id, "rejected")

        assertThat(outbox.due(Long.MAX_VALUE, 10)).isEmpty()
        assertThat(outbox.size()).isEqualTo(0)
        assertThat(dao.deadLetterCount()).isEqualTo(1)
    }

    @Test
    fun `removing a delivered record drops it entirely`() = runTest {
        val id = insert("a")

        outbox.remove(id)

        assertThat(outbox.size()).isEqualTo(0)
        assertThat(dao.deadLetterCount()).isEqualTo(0)
    }

    @Test
    fun `the record maps every column the engine reads`() = runTest {
        dao.insert(
            OutboxEntity(
                entityType = "note",
                entityId = "a",
                operation = OutboxRecord.Operation.DELETE.name,
                payload = null,
                baseVersion = 7,
                createdAtEpochMs = 123,
                attempt = 2,
                nextAttemptAtEpochMs = 0,
                lastError = "prior failure",
            ),
        )

        val record = outbox.due(NOW, 1).single()

        assertThat(record.entityType).isEqualTo("note")
        assertThat(record.operation).isEqualTo(OutboxRecord.Operation.DELETE)
        assertThat(record.payload).isNull()
        assertThat(record.baseVersion).isEqualTo(7)
        assertThat(record.createdAtEpochMs).isEqualTo(123)
        assertThat(record.attempt).isEqualTo(2)
        assertThat(record.lastError).isEqualTo("prior failure")
    }

    private suspend fun insert(entityId: String): Long = dao.insert(
        OutboxEntity(
            entityType = NotesRepository.ENTITY_TYPE,
            entityId = entityId,
            operation = OutboxRecord.Operation.UPSERT.name,
            payload = "{}",
            baseVersion = 0,
            createdAtEpochMs = NOW,
        ),
    )

    private companion object {
        const val NOW = 1_700_000_000_000
    }
}
