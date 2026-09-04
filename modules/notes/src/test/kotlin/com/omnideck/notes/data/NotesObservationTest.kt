package com.omnideck.notes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.omnideck.core.MutableClock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Does the notes list actually update when a note is saved?
 *
 * Every existing test reads `observeNotes().first()` — the *first* emission — which
 * asserts the query is right and says nothing about whether the Flow re-emits after an
 * insert. That is the whole behaviour the list screen depends on, and a device reported
 * saved notes never appearing in the list, so it is asserted here directly.
 *
 * Two configurations on purpose. `NotesTestFixture` pins Room to same-thread executors
 * so invalidation is synchronous and tests cannot race; production
 * (`StorageServiceImpl.database`) does no such thing and gets Room's default background
 * executors. A bug that only exists under the second configuration would be invisible
 * to every other test in this module.
 */
@RunWith(RobolectricTestRunner::class)
class NotesObservationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val open = mutableListOf<NotesDatabase>()

    @After
    fun tearDown() = open.forEach(NotesDatabase::close)

    /** Same-thread executors — what the module's own fixture uses. */
    private fun synchronousDatabase(): NotesDatabase = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(Runnable::run)
        .setTransactionExecutor(Runnable::run)
        .build()
        .also(open::add)

    /** Room's defaults — what `StorageServiceImpl` actually builds in production. */
    private fun productionLikeDatabase(): NotesDatabase =
        Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .build()
            .also(open::add)

    private fun repositoryOn(database: NotesDatabase) =
        NotesRepository(database, MutableClock(startMillis = 1_700_000_000_000), newId = { "n-${++ids}" })

    private var ids = 0

    @Test
    fun `saving a note re-emits the list, with synchronous invalidation`() = runTest {
        val repository = repositoryOn(synchronousDatabase())

        repository.observeNotes().test {
            assertThat(awaitItem()).isEmpty()

            repository.create("Shopping", "Milk")

            assertThat(awaitItem().map { it.title }).containsExactly("Shopping")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving a note re-emits the list, with Room's production executors`() = runTest {
        // The configuration the Shell actually runs. If this fails while the test
        // above passes, the module is correct and the fixture was hiding the defect.
        val repository = repositoryOn(productionLikeDatabase())

        repository.observeNotes().test {
            assertThat(awaitItem()).isEmpty()

            repository.create("Shopping", "Milk")

            assertThat(awaitItem().map { it.title }).containsExactly("Shopping")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a second save re-emits again`() = runTest {
        // One re-emission could be the initial query resolving late. Two cannot.
        val repository = repositoryOn(productionLikeDatabase())

        repository.observeNotes().test {
            assertThat(awaitItem()).isEmpty()

            repository.create("First", "a")
            assertThat(awaitItem()).hasSize(1)

            repository.create("Second", "b")
            assertThat(awaitItem()).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
