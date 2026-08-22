package com.omnideck.notes.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omnideck.notes.data.NotesRepository
import com.omnideck.sdk.capability.TelemetryService
import com.omnideck.sdk.capability.WorkScheduler
import com.omnideck.sdk.sync.OutboxRecord
import com.omnideck.sdk.sync.PushOutcome
import com.omnideck.sdk.sync.SyncEngine
import com.omnideck.sdk.sync.SyncSink
import java.time.Duration

/**
 * Writes the engine's verdicts back into the local database.
 *
 * Without this the outbox would drain and the notes would stay marked "not synced"
 * for ever — the sink is the half of synchronisation that makes the local row agree
 * with what the server now holds.
 */
class NotesSyncSink(private val repository: NotesRepository, private val telemetry: TelemetryService) : SyncSink {

    override suspend fun onApplied(record: OutboxRecord, remoteVersion: Long) {
        repository.onSynced(
            entityId = record.entityId,
            remoteVersion = remoteVersion,
            deleted = record.operation == OutboxRecord.Operation.DELETE,
        )
    }

    override suspend fun onRemoteAccepted(record: OutboxRecord, conflict: PushOutcome.Conflict) {
        repository.applyRemote(
            entityId = record.entityId,
            payload = repository.decodePayload(conflict.remotePayload),
            remoteVersion = conflict.remoteVersion,
        )
    }

    override suspend fun onDeadLettered(record: OutboxRecord, reason: String) {
        // Not a crash and not silent: the note stays on the device, the row stays in
        // the outbox marked undeliverable, and the module surfaces a count the user
        // can see. Support needs the entity id to reconstruct what happened.
        telemetry.event(
            "notes_change_undeliverable",
            mapOf("entity_id" to record.entityId, "operation" to record.operation.name, "reason" to reason),
        )
    }
}

/**
 * Where a WorkManager worker finds the engine.
 *
 * WorkManager instantiates workers reflectively with nothing but a `Context`, and
 * modules deliberately have no access to Hilt (ADR-002) — so a process-wide holder,
 * populated when the module initialises and cleared when it is suspended or purged,
 * is the seam. It is `internal` and holds exactly one thing so it cannot grow into a
 * service locator.
 */
internal object NotesSyncRuntime {

    @Volatile
    var engine: SyncEngine? = null
        private set

    fun attach(engine: SyncEngine) {
        this.engine = engine
    }

    fun detach() {
        engine = null
    }
}

/**
 * Drains the outbox in the background.
 *
 * It always reports success, even with work left over. Returning `Result.retry()`
 * would put WorkManager's backoff in front of the outbox's own, and two independent
 * backoffs multiply: a record already waiting an hour would not be attempted for
 * hours. The outbox owns *when* a record is due; this worker owns only *that*
 * something ran (architecture.md §11.2).
 */
class NotesSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        NotesSyncRuntime.engine?.drain()
        return Result.success()
    }
}

/**
 * Schedules the drain through the platform's [WorkScheduler] rather than WorkManager
 * directly, so every job carries the module tag that lets quarantine and purge cancel
 * this module's work atomically.
 */
class NotesSyncScheduler(private val work: WorkScheduler) {

    /** The safety net: catches anything an immediate sync missed. */
    fun schedulePeriodic() {
        work.enqueuePeriodic(spec(PERIODIC_NAME), Duration.ofMinutes(PERIOD_MINUTES))
    }

    /** Called after an edit, so a change made with connectivity lands in seconds. */
    fun syncNow() {
        work.enqueue(spec(IMMEDIATE_NAME, expedited = true))
    }

    private fun spec(name: String, expedited: Boolean = false) = WorkScheduler.WorkSpec(
        name = name,
        worker = NotesSyncWorker::class.java,
        requiresNetwork = true,
        expedited = expedited,
    )

    private companion object {
        const val PERIODIC_NAME = "notes-sync-periodic"
        const val IMMEDIATE_NAME = "notes-sync-now"

        /** WorkManager's own floor for periodic work is 15 minutes; asking for less is ignored. */
        const val PERIOD_MINUTES = 15L
    }
}
