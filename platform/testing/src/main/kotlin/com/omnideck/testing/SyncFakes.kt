package com.omnideck.testing

import com.omnideck.sdk.sync.Outbox
import com.omnideck.sdk.sync.OutboxRecord
import com.omnideck.sdk.sync.PushOutcome
import com.omnideck.sdk.sync.SyncSink
import com.omnideck.sdk.sync.SyncTransport
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory [Outbox] with the same ordering and due-time semantics as a Room-backed
 * one (OD-210).
 *
 * A module's offline behaviour is mostly outbox behaviour, and "does a failed write
 * come back later, once, in order" is not a question a mock can answer — so this is
 * a working queue rather than a stub, and doubles as the assertion surface for what
 * the engine did to it.
 */
class FakeOutbox(private val records: MutableList<OutboxRecord> = mutableListOf()) : Outbox {

    private val ids = AtomicLong()

    /** Records the engine gave up on, with the reason. Assert on this, not on logs. */
    val deadLettered = mutableListOf<Pair<OutboxRecord, String>>()

    /** Every reschedule, in order: id, attempt number and the slot chosen. */
    val rescheduled = mutableListOf<Triple<Long, Int, Long>>()

    /** Enqueues a record the way a module's repository would, and returns its id. */
    fun enqueue(
        entityType: String,
        entityId: String,
        operation: OutboxRecord.Operation = OutboxRecord.Operation.UPSERT,
        payload: String? = "{}",
        baseVersion: Long = 0,
        createdAtEpochMs: Long = 0,
    ): Long {
        val id = ids.incrementAndGet()
        records += OutboxRecord(
            id = id,
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            payload = payload,
            baseVersion = baseVersion,
            createdAtEpochMs = createdAtEpochMs,
        )
        return id
    }

    override suspend fun due(nowEpochMs: Long, limit: Int): List<OutboxRecord> = records
        .filter { it.nextAttemptAtEpochMs <= nowEpochMs && it !in deadLetteredRecords }
        .sortedBy { it.id }
        .take(limit)

    override suspend fun remove(id: Long) {
        records.removeAll { it.id == id }
    }

    override suspend fun reschedule(id: Long, attempt: Int, nextAttemptAtEpochMs: Long, lastError: String?) {
        rescheduled += Triple(id, attempt, nextAttemptAtEpochMs)
        replace(id) {
            it.copy(attempt = attempt, nextAttemptAtEpochMs = nextAttemptAtEpochMs, lastError = lastError)
        }
    }

    override suspend fun deadLetter(id: Long, reason: String) {
        records.firstOrNull { it.id == id }?.let { deadLettered += it to reason }
    }

    override suspend fun size(): Int = records.size

    /** Everything still queued, including dead letters — the module's own view. */
    fun snapshot(): List<OutboxRecord> = records.toList()

    private val deadLetteredRecords: Set<OutboxRecord> get() = deadLettered.map { it.first }.toSet()

    private fun replace(id: Long, transform: (OutboxRecord) -> OutboxRecord) {
        val index = records.indexOfFirst { it.id == id }
        if (index >= 0) records[index] = transform(records[index])
    }
}

/**
 * A scriptable [SyncTransport].
 *
 * Outcomes are queued and consumed in order, so a test can express "fails twice, then
 * succeeds" — the sequence that actually exercises backoff — in one line.
 */
class FakeSyncTransport(private val outcomes: ArrayDeque<PushOutcome> = ArrayDeque()) : SyncTransport {

    /** Every record pushed, in order, including rebased retries after a conflict. */
    val pushed = mutableListOf<OutboxRecord>()

    /** Used when the scripted queue is exhausted. */
    var default: PushOutcome = PushOutcome.Applied(remoteVersion = 1)

    /** Thrown instead of returning, to exercise the engine's containment of a broken transport. */
    var throws: Throwable? = null

    fun script(vararg next: PushOutcome) = apply { outcomes.addAll(next) }

    override suspend fun push(record: OutboxRecord): PushOutcome {
        pushed += record
        throws?.let { throw it }
        return outcomes.removeFirstOrNull() ?: default
    }
}

/** Records everything the engine asked the module's local store to reconcile. */
class RecordingSyncSink : SyncSink {

    val applied = mutableListOf<Pair<OutboxRecord, Long>>()
    val remoteAccepted = mutableListOf<OutboxRecord>()
    val deadLettered = mutableListOf<Pair<OutboxRecord, String>>()

    override suspend fun onApplied(record: OutboxRecord, remoteVersion: Long) {
        applied += record to remoteVersion
    }

    override suspend fun onRemoteAccepted(record: OutboxRecord, conflict: PushOutcome.Conflict) {
        remoteAccepted += record
    }

    override suspend fun onDeadLettered(record: OutboxRecord, reason: String) {
        deadLettered += record to reason
    }
}
