package com.omnideck.notes.data

import com.omnideck.sdk.sync.Outbox
import com.omnideck.sdk.sync.OutboxRecord

/**
 * Adapts this module's `outbox` table to the SDK's [Outbox] contract (OD-210).
 *
 * The engine owns scheduling and give-up policy; this owns nothing but translation.
 * Keeping the two apart is what let the engine be extracted from Notes and reused —
 * a module supplies rows, not retry logic.
 */
class RoomOutbox(private val dao: OutboxDao) : Outbox {

    override suspend fun due(nowEpochMs: Long, limit: Int): List<OutboxRecord> =
        dao.due(nowEpochMs, limit).map(OutboxEntity::toRecord)

    override suspend fun remove(id: Long) = dao.delete(id)

    override suspend fun reschedule(id: Long, attempt: Int, nextAttemptAtEpochMs: Long, lastError: String?) =
        dao.reschedule(id, attempt, nextAttemptAtEpochMs, lastError)

    override suspend fun deadLetter(id: Long, reason: String) = dao.deadLetter(id, reason)

    override suspend fun size(): Int = dao.pendingCount()
}

internal fun OutboxEntity.toRecord() = OutboxRecord(
    id = id,
    entityType = entityType,
    entityId = entityId,
    operation = OutboxRecord.Operation.valueOf(operation),
    payload = payload,
    baseVersion = baseVersion,
    createdAtEpochMs = createdAtEpochMs,
    attempt = attempt,
    nextAttemptAtEpochMs = nextAttemptAtEpochMs,
    lastError = lastError,
)
