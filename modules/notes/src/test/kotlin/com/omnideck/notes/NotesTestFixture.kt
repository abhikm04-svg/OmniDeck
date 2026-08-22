package com.omnideck.notes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.omnideck.core.MutableClock
import com.omnideck.notes.data.NotesDatabase
import com.omnideck.notes.data.NotesRepository
import com.omnideck.sdk.ModuleId
import com.omnideck.testing.FakePlatformServices
import com.omnideck.testing.FakeStorageService

/**
 * Shared setup for the module's tests.
 *
 * Room is exercised for real, in memory, under Robolectric. `FakeStorageService`
 * exists precisely for this: its `databaseFactory` hook is how a module tests its own
 * persistence without a Shell, a kernel or a device (`:platform:testing`, OD-105).
 */
internal class NotesTestFixture {

    val clock = MutableClock(startMillis = START_MILLIS)

    val database: NotesDatabase = Room
        .inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            NotesDatabase::class.java,
        )
        .allowMainThreadQueries()
        // Same-thread executors. Room otherwise emits invalidations on a background
        // pool, so a ViewModel's first state would arrive after the assertion that
        // reads it — a race that makes every test flaky rather than one test wrong.
        .setQueryExecutor(Runnable::run)
        .setTransactionExecutor(Runnable::run)
        .build()

    val repository = NotesRepository(database, clock, newId = { "note-${++ids}" })

    private var ids = 0

    /**
     * A complete [com.omnideck.sdk.PlatformServices] for this module, with the same
     * in-memory database behind `storage.database(...)` that [repository] uses.
     */
    fun services(): FakePlatformServices = FakePlatformServices(
        moduleId = ModuleId("com.omnideck.notes"),
        storage = FakeStorageService(databaseFactory = { _, _ -> database }),
    )

    fun close() = database.close()

    private companion object {
        const val START_MILLIS = 1_700_000_000_000
    }
}
