package com.omnideck.notes.data

import androidx.room.withTransaction
import com.omnideck.core.Clock
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.sync.OutboxRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the UI works with. Deliberately not the Room entity. */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val updatedAtEpochMs: Long,
    /** True while the server has not yet acknowledged the latest local edit. */
    val pendingSync: Boolean,
) {
    /** First line of the body, for the list row. */
    val preview: String get() = body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
}

/** Wire shape. Shared with the outbox payload so one change cannot describe two things. */
@Serializable
data class NotePayload(val id: String, val title: String, val body: String, val updatedAtEpochMs: Long)

/**
 * Offline-first notes (architecture.md §11.2).
 *
 * Every mutation does exactly two things in one transaction: write the local row, and
 * append an outbox record describing the intent. Nothing here touches the network —
 * a write succeeds on a plane and is delivered later by
 * [com.omnideck.sdk.sync.SyncEngine], which is what "offline-first" has to mean if it
 * is to mean anything.
 */
class NotesRepository(
    private val database: NotesDatabase,
    private val clock: Clock,
    private val json: Json = Json,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {

    fun observeNotes(): Flow<List<Note>> = database.notes().observeAll().map { rows -> rows.map(NoteEntity::toNote) }

    fun observeNote(id: String): Flow<Note?> = database.notes().observe(id).map { it?.toNote() }

    suspend fun create(title: String, body: String): String {
        val id = newId()
        write(
            NoteEntity(
                id = id,
                title = title,
                body = body,
                updatedAtEpochMs = clock.nowMillis(),
                pendingSync = true,
            ),
        )
        return id
    }

    /**
     * Returns false when the note is gone — a rename racing a delete from another
     * device, which is ordinary rather than exceptional, so it is a return value.
     */
    suspend fun update(id: String, title: String, body: String): Boolean {
        val existing = database.notes().find(id)?.takeUnless { it.deleted } ?: return false
        write(
            existing.copy(
                title = title,
                body = body,
                updatedAtEpochMs = clock.nowMillis(),
                pendingSync = true,
            ),
        )
        return true
    }

    /**
     * Soft delete. The row survives as a tombstone until the outbox has delivered the
     * deletion — hard-deleting first would lose the intent and the note would come
     * back on the next pull.
     */
    suspend fun delete(id: String): Boolean {
        val existing = database.notes().find(id)?.takeUnless { it.deleted } ?: return false
        val tombstone = existing.copy(
            deleted = true,
            pendingSync = true,
            updatedAtEpochMs = clock.nowMillis(),
        )
        database.withTransaction {
            database.notes().upsert(tombstone)
            database.outbox().insert(tombstone.toOutbox(OutboxRecord.Operation.DELETE, payload = null))
        }
        return true
    }

    /** Pending, undelivered local changes. Drives the "not synced yet" affordance. */
    suspend fun pendingChanges(): Int = database.outbox().pendingCount()

    /** Changes that will never be delivered. The user is entitled to know. */
    suspend fun undeliverableChanges(): Int = database.outbox().deadLetterCount()

    /**
     * The module's half of the erasure guarantee (architecture.md §12.5). The kernel
     * deletes the module's directory either way; doing it here as well means an
     * in-process purge leaves no stale rows behind an already-open connection.
     */
    suspend fun wipe(scope: PurgeScope) {
        when (scope) {
            // Notes hold no session-scoped or cached data: every note is user content
            // that a sign-out must not destroy.
            PurgeScope.CACHE, PurgeScope.SESSION -> Unit

            PurgeScope.ALL -> database.withTransaction {
                database.outbox().deleteAll()
                database.notes().deleteAll()
            }
        }
    }

    /** Applies a server-acknowledged version to the local row. Called by the sync sink. */
    suspend fun onSynced(entityId: String, remoteVersion: Long, deleted: Boolean) {
        if (deleted) {
            // The tombstone has done its job: the server knows. Now it can go.
            database.notes().hardDelete(entityId)
        } else {
            database.notes().markSynced(entityId, remoteVersion)
        }
    }

    /**
     * Overwrites the local row with the server's copy after a conflict was resolved in
     * the server's favour. A null [payload] means the server has deleted it.
     */
    suspend fun applyRemote(entityId: String, payload: NotePayload?, remoteVersion: Long) {
        if (payload == null) {
            database.notes().hardDelete(entityId)
            return
        }
        database.notes().upsert(
            NoteEntity(
                id = payload.id,
                title = payload.title,
                body = payload.body,
                updatedAtEpochMs = payload.updatedAtEpochMs,
                remoteVersion = remoteVersion,
                pendingSync = false,
            ),
        )
    }

    /** Decodes a wire payload. Kept here so the JSON configuration lives in one place. */
    fun decodePayload(raw: String?): NotePayload? =
        raw?.let { runCatching { json.decodeFromString<NotePayload>(it) }.getOrNull() }

    private suspend fun write(note: NoteEntity) = database.withTransaction {
        database.notes().upsert(note)
        database.outbox().insert(note.toOutbox(OutboxRecord.Operation.UPSERT, json.encodeToString(note.toPayload())))
    }

    private fun NoteEntity.toOutbox(operation: OutboxRecord.Operation, payload: String?) = OutboxEntity(
        entityType = ENTITY_TYPE,
        entityId = id,
        operation = operation.name,
        payload = payload,
        baseVersion = remoteVersion,
        createdAtEpochMs = updatedAtEpochMs,
    )

    companion object {
        /** The one entity type this module synchronises. Matches the server's collection. */
        const val ENTITY_TYPE = "note"
    }
}

internal fun NoteEntity.toNote() = Note(
    id = id,
    title = title,
    body = body,
    updatedAtEpochMs = updatedAtEpochMs,
    pendingSync = pendingSync,
)

internal fun NoteEntity.toPayload() = NotePayload(
    id = id,
    title = title,
    body = body,
    updatedAtEpochMs = updatedAtEpochMs,
)
