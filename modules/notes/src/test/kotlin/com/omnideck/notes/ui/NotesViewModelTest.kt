package com.omnideck.notes.ui

import com.google.common.truth.Truth.assertThat
import com.omnideck.notes.NotesTestFixture
import com.omnideck.testing.FakeRouter
import com.omnideck.testing.FakeTelemetryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotesListViewModelTest {

    private val fixture = NotesTestFixture()
    private val router = FakeRouter()
    private val telemetry = FakeTelemetryService()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
    }

    @Test
    fun `the list reflects what is in the database, not what is on the server`() = runTest {
        fixture.repository.create("Groceries", "Milk")

        val state = viewModel().state.value

        assertThat(state.loading).isFalse()
        assertThat(state.notes.map { it.title }).containsExactly("Groceries")
        // Never synced, so the row shows its pending affordance.
        assertThat(state.notes.single().pendingSync).isTrue()
    }

    @Test
    fun `search filters on both title and body, case-insensitively`() = runTest {
        fixture.repository.create("Groceries", "Milk")
        fixture.repository.create("Ideas", "buy MILK crate")
        fixture.repository.create("Unrelated", "nothing")
        val vm = viewModel()

        vm.onQueryChange("milk")

        assertThat(vm.state.value.notes.map { it.title }).containsExactly("Groceries", "Ideas")
    }

    @Test
    fun `an empty query shows everything`() = runTest {
        fixture.repository.create("One", "")
        val vm = viewModel()

        vm.onQueryChange("nothing matches")
        vm.onQueryChange("")

        assertThat(vm.state.value.notes).hasSize(1)
    }

    @Test
    fun `tapping new navigates to the editor rather than creating an empty note`() = runTest {
        val vm = viewModel()

        vm.onCreate()

        assertThat(router.lastRoute()?.uri).isEqualTo("omnideck://notes/new")
        assertThat(fixture.database.notes().count()).isEqualTo(0)
        assertThat(telemetry.eventNames()).contains("notes_create_tapped")
    }

    @Test
    fun `opening a note routes to it by id`() = runTest {
        val id = fixture.repository.create("Note", "")
        val vm = viewModel()

        vm.onOpen(fixture.repository.observeNotes().first().single())

        assertThat(router.lastRoute()?.uri).isEqualTo("omnideck://notes/note/$id")
    }

    @Test
    fun `undeliverable changes are counted separately from pending ones`() = runTest {
        fixture.repository.create("Doomed", "")
        val queued = fixture.database.outbox().due(Long.MAX_VALUE, 1).single()
        fixture.database.outbox().deadLetter(queued.id, "rejected")
        fixture.repository.create("Fine", "")

        val state = viewModel().state.value

        assertThat(state.pendingChanges).isEqualTo(1)
        assertThat(state.undeliverableChanges).isEqualTo(1)
    }

    @Test
    fun `with no endpoint configured the module reports itself as local-only`() = runTest {
        assertThat(viewModel().state.value.syncConfigured).isFalse()
    }

    private fun viewModel() = NotesListViewModel(
        repository = fixture.repository,
        router = router,
        telemetry = telemetry,
        // Null is the honest Phase 2 state: no Notes service exists yet, so nothing
        // is configured and the UI says so rather than showing a spinner for ever.
        sync = null,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NoteEditorViewModelTest {

    private val fixture = NotesTestFixture()
    private val router = FakeRouter()
    private val telemetry = FakeTelemetryService()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
    }

    @Test
    fun `a new note starts empty and cannot be saved until something is typed`() = runTest {
        val vm = viewModel(noteId = null)

        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.canSave).isFalse()

        vm.onTitleChange("Title")

        assertThat(vm.state.value.canSave).isTrue()
    }

    @Test
    fun `whitespace alone is not worth saving`() = runTest {
        val vm = viewModel(noteId = null)

        vm.onBodyChange("   ")

        assertThat(vm.state.value.canSave).isFalse()
    }

    @Test
    fun `saving a new note writes it locally and returns`() = runTest {
        val vm = viewModel(noteId = null)
        vm.onTitleChange("Groceries")
        vm.onBodyChange("Milk")

        vm.onSave()

        val stored = fixture.repository.observeNotes().first().single()
        assertThat(stored.title).isEqualTo("Groceries")
        assertThat(telemetry.eventNames()).contains("notes_note_created")
        assertThat(router.navigations).isEmpty()
    }

    @Test
    fun `an existing note loads into the editor`() = runTest {
        val id = fixture.repository.create("Original", "Body")

        val vm = viewModel(noteId = id)

        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.title).isEqualTo("Original")
        assertThat(vm.state.value.body).isEqualTo("Body")
    }

    @Test
    fun `saving an edit updates the note in place`() = runTest {
        val id = fixture.repository.create("Original", "Body")
        val vm = viewModel(noteId = id)
        vm.onTitleChange("Edited")

        vm.onSave()

        assertThat(fixture.repository.observeNote(id).first()?.title).isEqualTo("Edited")
        assertThat(fixture.repository.observeNotes().first()).hasSize(1)
        assertThat(telemetry.eventNames()).contains("notes_note_updated")
    }

    @Test
    fun `a note deleted elsewhere is reported, never silently recreated`() = runTest {
        val id = fixture.repository.create("Doomed", "")
        val vm = viewModel(noteId = id)
        vm.onTitleChange("Edited")
        fixture.repository.delete(id)

        vm.onSave()

        assertThat(vm.state.value.missing).isTrue()
        assertThat(fixture.repository.observeNotes().first()).isEmpty()
    }

    @Test
    fun `opening a note that is already gone shows the missing state`() = runTest {
        val vm = viewModel(noteId = "never-existed")

        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.missing).isTrue()
        assertThat(vm.state.value.canSave).isFalse()
    }

    @Test
    fun `deleting an existing note removes it and goes back`() = runTest {
        val id = fixture.repository.create("Temporary", "")
        val vm = viewModel(noteId = id)

        vm.onDelete()

        assertThat(fixture.repository.observeNotes().first()).isEmpty()
        assertThat(telemetry.eventNames()).contains("notes_note_deleted")
    }

    @Test
    fun `discarding an unsaved note just goes back`() = runTest {
        val vm = viewModel(noteId = null)
        vm.onTitleChange("Never saved")

        vm.onDelete()

        assertThat(fixture.database.notes().count()).isEqualTo(0)
    }

    @Test
    fun `back leaves the note untouched`() = runTest {
        val id = fixture.repository.create("Original", "")
        val vm = viewModel(noteId = id)
        vm.onTitleChange("Not saved")

        vm.onBack()

        assertThat(fixture.repository.observeNote(id).first()?.title).isEqualTo("Original")
    }

    private fun viewModel(noteId: String?) = NoteEditorViewModel(
        repository = fixture.repository,
        router = router,
        telemetry = telemetry,
        sync = null,
        noteId = noteId,
    )
}
