package com.omnideck.sdk.sync

import kotlinx.serialization.Serializable

/**
 * One pending local change, waiting to reach the server (architecture.md §11.2).
 *
 * The outbox row — not the entity table — is the unit of synchronisation. That
 * distinction is what makes an offline-first module correct rather than merely
 * offline-capable: the user's *intent* ("rename this note", "delete that one") is
 * durable and ordered, so a change made on a plane still lands after a reinstall of
 * the network, in the order it was made, exactly once.
 *
 * [baseVersion] is the server version the local edit was made against. Sending it is
 * what lets the server detect a conflict instead of silently overwriting a newer
 * remote value — the difference between "last writer wins" and "last writer wins and
 * nobody finds out".
 */
@Serializable
data class OutboxRecord(
    val id: Long,
    /** Logical table name, e.g. `note`. Lets one outbox carry several entity types. */
    val entityType: String,
    val entityId: String,
    val operation: Operation,
    /** Serialised entity for [Operation.UPSERT]; null for [Operation.DELETE]. */
    val payload: String?,
    /** Server version this edit was based on. `0` for a locally created entity. */
    val baseVersion: Long = 0,
    val createdAtEpochMs: Long = 0,
    /** Delivery attempts made so far. Drives the backoff schedule. */
    val attempt: Int = 0,
    /** Earliest time this record may be retried. `0` means "now". */
    val nextAttemptAtEpochMs: Long = 0,
    val lastError: String? = null,
) {
    enum class Operation { UPSERT, DELETE }
}

/**
 * The module's durable queue of pending changes.
 *
 * Implemented by the module over its own Room table — the platform deliberately does
 * not own the storage, because the outbox must commit in the *same transaction* as
 * the entity write. Split across two databases, a crash between the two writes loses
 * the change or duplicates it, and no amount of retry logic in the engine can repair
 * that.
 */
interface Outbox {

    /** Records eligible for delivery at [nowEpochMs], oldest first. */
    suspend fun due(nowEpochMs: Long, limit: Int): List<OutboxRecord>

    /** Delivered (or resolved). Removes the record permanently. */
    suspend fun remove(id: Long)

    /** Delivery failed but may succeed later; the engine has computed the next slot. */
    suspend fun reschedule(id: Long, attempt: Int, nextAttemptAtEpochMs: Long, lastError: String?)

    /**
     * Delivery has failed permanently or exhausted its attempts. Kept, not dropped:
     * a silently discarded write is data loss the user never sees, and support needs
     * something to look at.
     */
    suspend fun deadLetter(id: Long, reason: String)

    /** Records still pending, including dead-lettered ones. Drives the UI's sync badge. */
    suspend fun size(): Int
}

/**
 * How a record reaches the server. One method, because the outbox already decided
 * *what* to send and *when* — this is the only part that is module-specific.
 */
fun interface SyncTransport {
    suspend fun push(record: OutboxRecord): PushOutcome
}

/** What the server did with a pushed record. */
sealed interface PushOutcome {

    /** Accepted. [remoteVersion] is the entity's new server version. */
    data class Applied(val remoteVersion: Long) : PushOutcome

    /**
     * Rejected because the entity moved on server-side since [OutboxRecord.baseVersion].
     * Carries what the server currently holds, so a resolver can make a real decision
     * rather than guessing.
     */
    data class Conflict(val remoteVersion: Long, val remotePayload: String?, val remoteUpdatedAtEpochMs: Long) :
        PushOutcome

    /** Transient — offline, 5xx, timeout, rate limit. The engine will back off and retry. */
    data class Retryable(val reason: String) : PushOutcome

    /** Permanent — 4xx, schema rejection, revoked entitlement. Retrying cannot help. */
    data class Rejected(val reason: String) : PushOutcome
}

/**
 * What to do about a [PushOutcome.Conflict].
 *
 * Declared per entity type by the module (architecture.md §11.2), because the right
 * answer is a product decision and never a platform one: silently keeping the server
 * copy of a note the user just edited is a bug, and silently overwriting a shared
 * ledger entry is a worse one.
 */
fun interface ConflictResolver {

    suspend fun resolve(record: OutboxRecord, conflict: PushOutcome.Conflict): Resolution

    sealed interface Resolution {
        /**
         * Keep the local change and push it again, rebased onto [baseVersion]. The
         * payload may be a merge of the two sides.
         */
        data class Retry(val payload: String?, val baseVersion: Long) : Resolution

        /** Discard the local change; the server copy is authoritative. */
        data object AcceptRemote : Resolution
    }

    companion object {
        /**
         * The local edit wins if it happened after the remote one. Requires both
         * clocks to be roughly in step, which is why it compares the *edit* time the
         * outbox recorded rather than the moment of the push.
         */
        fun lastWriteWins(): ConflictResolver = ConflictResolver { record, conflict ->
            if (record.createdAtEpochMs >= conflict.remoteUpdatedAtEpochMs) {
                Resolution.Retry(record.payload, conflict.remoteVersion)
            } else {
                Resolution.AcceptRemote
            }
        }

        /** The server is always authoritative. The safe default for money and inventory. */
        fun serverWins(): ConflictResolver = ConflictResolver { _, _ -> Resolution.AcceptRemote }
    }
}

/**
 * Where the engine reports outcomes that the module's local database must reflect.
 *
 * Every method has a no-op default: a module that only pushes need implement none of
 * them, and one that must reconcile — clear a dirty flag, adopt a server version,
 * surface a dead letter — implements only what it uses.
 */
interface SyncSink {

    /** The server accepted the change; store [remoteVersion] against the entity. */
    suspend fun onApplied(record: OutboxRecord, remoteVersion: Long) = Unit

    /** A conflict was resolved in the server's favour; overwrite the local entity. */
    suspend fun onRemoteAccepted(record: OutboxRecord, conflict: PushOutcome.Conflict) = Unit

    /** The change will never be delivered. The user needs to be told something. */
    suspend fun onDeadLettered(record: OutboxRecord, reason: String) = Unit

    companion object {
        val NoOp: SyncSink = object : SyncSink {}
    }
}
