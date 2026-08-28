package com.omnideck.notes.sync

import com.google.common.truth.Truth.assertThat
import com.omnideck.notes.NotesTestFixture
import com.omnideck.notes.data.NotePayload
import com.omnideck.sdk.sync.OutboxRecord
import com.omnideck.sdk.sync.PushOutcome
import com.omnideck.testing.FakeTelemetryService
import com.omnideck.testing.FakeWorkScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

/**
 * The sink is the half of synchronisation that makes the local row agree with the
 * server. Without it the outbox drains and every note stays marked "not synced",
 * which is the failure mode that looks like the feature working.
 */
@RunWith(RobolectricTestRunner::class)
class NotesSyncSinkTest {

    private val fixture = NotesTestFixture()
    private val telemetry = FakeTelemetryService()
    private val sink = NotesSyncSink(fixture.repository, telemetry)

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `an accepted upsert clears the pending flag`() = runTest {
        val id = fixture.repository.create("Note", "")

        sink.onApplied(record(id, OutboxRecord.Operation.UPSERT), remoteVersion = 3)

        assertThat(fixture.repository.observeNote(id).first()?.pendingSync).isFalse()
    }

    @Test
    fun `an accepted delete removes the tombstone`() = runTest {
        val id = fixture.repository.create("Note", "")
        fixture.repository.delete(id)

        sink.onApplied(record(id, OutboxRecord.Operation.DELETE), remoteVersion = 4)

        assertThat(fixture.database.notes().find(id)).isNull()
    }

    @Test
    fun `losing a conflict overwrites the local note with the server copy`() = runTest {
        val id = fixture.repository.create("Mine", "local")
        val remote = NotePayload(id = id, title = "Theirs", body = "remote", updatedAtEpochMs = 55)

        sink.onRemoteAccepted(
            record(id, OutboxRecord.Operation.UPSERT),
            PushOutcome.Conflict(
                remoteVersion = 6,
                remotePayload = Json.encodeToString(remote),
                remoteUpdatedAtEpochMs = 55,
            ),
        )

        assertThat(fixture.repository.observeNote(id).first()?.title).isEqualTo("Theirs")
    }

    @Test
    fun `an undeliverable change is reported with enough detail to trace it`() = runTest {
        sink.onDeadLettered(record("n1", OutboxRecord.Operation.UPSERT), reason = "rejected")

        val event = telemetry.events.single()
        assertThat(event.name).isEqualTo("notes_change_undeliverable")
        assertThat(event.attributes["entity_id"]).isEqualTo("n1")
        assertThat(event.attributes["reason"]).isEqualTo("rejected")
    }

    private fun record(entityId: String, operation: OutboxRecord.Operation) = OutboxRecord(
        id = 1,
        entityType = "note",
        entityId = entityId,
        operation = operation,
        payload = null,
    )
}

/**
 * Scheduling goes through the platform's [com.omnideck.sdk.capability.WorkScheduler]
 * rather than WorkManager so that quarantine and purge can cancel this module's work
 * atomically. These assertions are about that routing, not about WorkManager.
 */
class NotesSyncSchedulerTest {

    private val work = FakeWorkScheduler()
    private val scheduler = NotesSyncScheduler(work)

    @Test
    fun `the periodic drain runs at WorkManager's minimum interval, not below it`() {
        scheduler.schedulePeriodic()

        val (spec, interval) = work.periodic.single()
        assertThat(spec.worker).isEqualTo(NotesSyncWorker::class.java)
        assertThat(spec.requiresNetwork).isTrue()
        // Anything below 15 minutes is silently floored by WorkManager, so asking for
        // less would make the module believe it syncs more often than it does.
        assertThat(interval).isEqualTo(Duration.ofMinutes(MIN_PERIODIC_MINUTES))
    }

    @Test
    fun `an immediate sync is expedited and distinct from the periodic one`() {
        scheduler.schedulePeriodic()
        scheduler.syncNow()

        assertThat(work.enqueued.map { it.name }).containsNoDuplicates()
        assertThat(work.enqueued.last().expedited).isTrue()
    }

    @Test
    fun `a periodic schedule replaces rather than stacks`() {
        // Re-registration happens on every activation. The scheduler must not leave
        // two identical periodic jobs behind, or the drain runs twice per interval.
        scheduler.schedulePeriodic()
        scheduler.schedulePeriodic()

        assertThat(work.enqueued.map { it.name }.distinct()).hasSize(1)
    }

    private companion object {
        const val MIN_PERIODIC_MINUTES = 15L
    }
}

/**
 * The holder exists because WorkManager instantiates workers reflectively with only a
 * `Context`, and modules have no Hilt. Its contract is small and easy to get wrong in
 * the direction that matters: a suspended module must stop draining.
 */
class NotesSyncRuntimeTest {

    @Test
    fun `detaching stops a scheduled drain from finding an engine`() {
        NotesSyncRuntime.detach()

        assertThat(NotesSyncRuntime.engine).isNull()
    }
}
