package com.omnideck.notes.data

import com.google.common.truth.Truth.assertThat
import com.omnideck.notes.NotesTestFixture
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.sync.OutboxRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The offline-first contract of architecture.md §11.2, asserted rather than assumed:
 * every mutation lands locally *and* leaves exactly one outbox record behind, and a
 * delete leaves a tombstone rather than a hole.
 */
@RunWith(RobolectricTestRunner::class)
class NotesRepositoryTest {

    private val fixture = NotesTestFixture()
    private val repository = fixture.repository

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `creating a note stores it and queues one outbox record`() = runTest {
        val id = repository.create("Shopping", "Milk\nBread")

        val notes = repository.observeNotes().first()
        assertThat(notes.map { it.id }).containsExactly(id)
        assertThat(notes.single().pendingSync).isTrue()

        val queued = fixture.database.outbox().due(Long.MAX_VALUE, limit = 10)
        assertThat(queued).hasSize(1)
        assertThat(queued.single().operation).isEqualTo(OutboxRecord.Operation.UPSERT.name)
        assertThat(queued.single().entityId).isEqualTo(id)
    }

    @Test
    fun `the outbox payload carries the note the user actually wrote`() = runTest {
        val id = repository.create("Title", "Body")

        val payload = repository.decodePayload(fixture.database.outbox().due(Long.MAX_VALUE, 1).single().payload)

        assertThat(payload).isEqualTo(
            NotePayload(id = id, title = "Title", body = "Body", updatedAtEpochMs = fixture.clock.nowMillis()),
        )
    }

    @Test
    fun `updating bumps the timestamp and queues a second record`() = runTest {
        val id = repository.create("Draft", "")
        fixture.clock.advanceBy(1_000)

        assertThat(repository.update(id, "Final", "Done")).isTrue()

        val note = repository.observeNote(id).first()
        assertThat(note?.title).isEqualTo("Final")
        assertThat(note?.updatedAtEpochMs).isEqualTo(fixture.clock.nowMillis())
        assertThat(fixture.database.outbox().pendingCount()).isEqualTo(2)
    }

    @Test
    fun `updating a note that no longer exists reports failure instead of resurrecting it`() = runTest {
        assertThat(repository.update("gone", "x", "y")).isFalse()
        assertThat(fixture.database.outbox().pendingCount()).isEqualTo(0)
    }

    @Test
    fun `deleting hides the note but keeps the tombstone until it is delivered`() = runTest {
        val id = repository.create("Temporary", "")

        assertThat(repository.delete(id)).isTrue()

        assertThat(repository.observeNotes().first()).isEmpty()
        assertThat(repository.observeNote(id).first()).isNull()
        // The row is still there — dropping it would lose the deletion before the
        // server heard about it, and the note would come back on the next pull.
        assertThat(fixture.database.notes().find(id)).isNotNull()
        assertThat(fixture.database.outbox().due(Long.MAX_VALUE, 10).last().operation)
            .isEqualTo(OutboxRecord.Operation.DELETE.name)
    }

    @Test
    fun `deleting twice is not an error and does not queue a second tombstone`() = runTest {
        val id = repository.create("Temporary", "")
        repository.delete(id)

        assertThat(repository.delete(id)).isFalse()
        assertThat(fixture.database.outbox().due(Long.MAX_VALUE, 10)).hasSize(2)
    }

    @Test
    fun `an acknowledged upsert clears the pending flag and records the server version`() = runTest {
        val id = repository.create("Synced", "")

        repository.onSynced(id, remoteVersion = 4, deleted = false)

        assertThat(repository.observeNote(id).first()?.pendingSync).isFalse()
        assertThat(fixture.database.notes().find(id)?.remoteVersion).isEqualTo(4)
    }

    @Test
    fun `an acknowledged delete finally removes the tombstone`() = runTest {
        val id = repository.create("Temporary", "")
        repository.delete(id)

        repository.onSynced(id, remoteVersion = 9, deleted = true)

        assertThat(fixture.database.notes().find(id)).isNull()
    }

    @Test
    fun `accepting the server copy overwrites the local one and marks it clean`() = runTest {
        val id = repository.create("Mine", "local")

        repository.applyRemote(
            entityId = id,
            payload = NotePayload(id = id, title = "Theirs", body = "remote", updatedAtEpochMs = 99),
            remoteVersion = 12,
        )

        val stored = fixture.database.notes().find(id)
        assertThat(stored?.title).isEqualTo("Theirs")
        assertThat(stored?.remoteVersion).isEqualTo(12)
        assertThat(stored?.pendingSync).isFalse()
    }

    @Test
    fun `accepting a server delete removes the local note`() = runTest {
        val id = repository.create("Mine", "local")

        repository.applyRemote(entityId = id, payload = null, remoteVersion = 12)

        assertThat(fixture.database.notes().find(id)).isNull()
    }

    @Test
    fun `a malformed payload decodes to null rather than throwing into the sync loop`() {
        assertThat(repository.decodePayload("not json")).isNull()
        assertThat(repository.decodePayload(null)).isNull()
    }

    @Test
    fun `signing out does not delete the user's notes`() = runTest {
        repository.create("Keep me", "")

        repository.wipe(PurgeScope.SESSION)
        repository.wipe(PurgeScope.CACHE)

        assertThat(fixture.database.notes().count()).isEqualTo(1)
    }

    @Test
    fun `a full purge erases every note and every queued change`() = runTest {
        repository.create("One", "")
        repository.create("Two", "")

        repository.wipe(PurgeScope.ALL)

        assertThat(fixture.database.notes().count()).isEqualTo(0)
        assertThat(fixture.database.outbox().pendingCount()).isEqualTo(0)
    }

    @Test
    fun `pending and undeliverable counts are reported separately`() = runTest {
        repository.create("One", "")
        val queued = fixture.database.outbox().due(Long.MAX_VALUE, 1).single()
        fixture.database.outbox().deadLetter(queued.id, "server said no")

        assertThat(repository.pendingChanges()).isEqualTo(0)
        assertThat(repository.undeliverableChanges()).isEqualTo(1)
    }

    @Test
    fun `notes are listed most recently edited first`() = runTest {
        val first = repository.create("First", "")
        fixture.clock.advanceBy(1_000)
        val second = repository.create("Second", "")

        assertThat(repository.observeNotes().first().map { it.id })
            .containsExactly(second, first)
            .inOrder()
    }

    @Test
    fun `preview is the first non-blank line of the body`() = runTest {
        val id = repository.create("Title", "\n\n  \nreal content\nmore")

        assertThat(repository.observeNote(id).first()?.preview).isEqualTo("real content")
    }
}
