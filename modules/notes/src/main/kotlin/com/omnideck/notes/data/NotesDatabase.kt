package com.omnideck.notes.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A note as it exists on this device.
 *
 * [remoteVersion] and [pendingSync] are what make the module offline-first rather
 * than merely offline-tolerant: the local row is the source of truth, and these two
 * columns record how far the server has caught up with it (architecture.md §11.2).
 *
 * Deletes are soft. A hard delete would lose the tombstone before the outbox has
 * delivered it, and the note would reappear on the next pull.
 */
@Entity(tableName = "notes", indices = [Index("updatedAtEpochMs")])
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val updatedAtEpochMs: Long,
    /** Server version this row last agreed with. `0` means the server has never seen it. */
    val remoteVersion: Long = 0,
    val pendingSync: Boolean = false,
    val deleted: Boolean = false,
)

/**
 * The durable queue behind [com.omnideck.sdk.sync.SyncEngine].
 *
 * It lives in the *same* Room database as [NoteEntity] deliberately: the entity write
 * and its outbox row must commit in one transaction, or a crash between them either
 * loses the user's change or delivers one that was rolled back.
 */
@Entity(tableName = "outbox", indices = [Index("nextAttemptAtEpochMs")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: String?,
    val baseVersion: Long,
    val createdAtEpochMs: Long,
    val attempt: Int = 0,
    val nextAttemptAtEpochMs: Long = 0,
    val lastError: String? = null,
    /**
     * Undeliverable, but kept. A silently discarded write is data loss the user never
     * sees; support and the Privacy Centre both need something to point at.
     */
    val deadLettered: Boolean = false,
)

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes WHERE deleted = 0 ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id AND deleted = 0")
    fun observe(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun find(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("UPDATE notes SET remoteVersion = :version, pendingSync = 0 WHERE id = :id")
    suspend fun markSynced(id: String, version: Long)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM notes WHERE deleted = 0")
    suspend fun count(): Int
}

@Dao
interface OutboxDao {

    @Query(
        """
        SELECT * FROM outbox
        WHERE deadLettered = 0 AND nextAttemptAtEpochMs <= :nowEpochMs
        ORDER BY id ASC
        LIMIT :limit
        """,
    )
    suspend fun due(nowEpochMs: Long, limit: Int): List<OutboxEntity>

    @Insert
    suspend fun insert(record: OutboxEntity): Long

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "UPDATE outbox SET attempt = :attempt, nextAttemptAtEpochMs = :nextAt, lastError = :error WHERE id = :id",
    )
    suspend fun reschedule(id: Long, attempt: Int, nextAt: Long, error: String?)

    @Query("UPDATE outbox SET deadLettered = 1, lastError = :reason WHERE id = :id")
    suspend fun deadLetter(id: Long, reason: String)

    @Query("SELECT COUNT(*) FROM outbox WHERE deadLettered = 0")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE deadLettered = 1")
    suspend fun deadLetterCount(): Int

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}

/**
 * The module's own database, opened through `StorageService` so its file lands under
 * `files/modules/com.omnideck.notes/` and is erased by a directory delete (ADR-005).
 *
 * `exportSchema = false` while the schema is still moving. It flips to true, with the
 * committed schema JSON and a migration test, at the first shipped release — that is
 * a release gate, not a Phase 2 one.
 */
@Database(entities = [NoteEntity::class, OutboxEntity::class], version = 1, exportSchema = false)
abstract class NotesDatabase : RoomDatabase() {

    abstract fun notes(): NoteDao

    abstract fun outbox(): OutboxDao
}
