package com.omnideck.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnideck.notes.NotesComponent
import com.omnideck.notes.data.NotesRepository
import com.omnideck.sdk.capability.Router
import com.omnideck.sdk.capability.TelemetryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NoteEditorState(
    val title: String = "",
    val body: String = "",
    val loading: Boolean = true,
    /** True once the note has diverged from what is stored. Drives the enabled Save. */
    val dirty: Boolean = false,
    val saving: Boolean = false,
    /** Set when the note was deleted on another device while this screen was open. */
    val missing: Boolean = false,
) {
    val canSave: Boolean get() = dirty && !saving && !missing && (title.isNotBlank() || body.isNotBlank())
}

/**
 * The note editor, for both a new note ([noteId] null) and an existing one.
 *
 * Saving writes locally and returns immediately — the outbox and
 * [com.omnideck.sdk.sync.SyncEngine] deliver it afterwards. A user who taps Save on a
 * train sees the same thing as one on wifi, which is the entire point of the pattern
 * (architecture.md §11.2).
 */
class NoteEditorViewModel(
    private val repository: NotesRepository,
    private val router: Router,
    private val telemetry: TelemetryService,
    private val sync: NotesComponent.NotesSync?,
    private val noteId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(NoteEditorState(loading = noteId != null))
    val state: StateFlow<NoteEditorState> = _state.asStateFlow()

    init {
        if (noteId != null) {
            viewModelScope.launch {
                val note = repository.observeNote(noteId).first()
                _state.update {
                    if (note == null) {
                        it.copy(loading = false, missing = true)
                    } else {
                        it.copy(title = note.title, body = note.body, loading = false)
                    }
                }
            }
        }
    }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value, dirty = true) }

    fun onBodyChange(value: String) = _state.update { it.copy(body = value, dirty = true) }

    fun onSave() {
        if (!_state.value.canSave) return
        _state.update { it.copy(saving = true) }

        viewModelScope.launch {
            val current = _state.value
            if (noteId == null) {
                repository.create(current.title, current.body)
                telemetry.event("notes_note_created")
            } else {
                // False means it was deleted elsewhere while open. Surfaced rather
                // than resurrected: silently recreating a note the user deleted on
                // another device is the wrong answer.
                if (!repository.update(noteId, current.title, current.body)) {
                    _state.update { it.copy(saving = false, missing = true) }
                    return@launch
                }
                telemetry.event("notes_note_updated")
            }
            sync?.scheduler?.syncNow()
            _state.update { it.copy(saving = false, dirty = false) }
            router.back()
        }
    }

    fun onDelete() {
        // Nothing to delete for an unsaved note; discarding it is just going back.
        val id = noteId
        if (id == null) {
            router.back()
            return
        }
        viewModelScope.launch {
            repository.delete(id)
            telemetry.event("notes_note_deleted")
            sync?.scheduler?.syncNow()
            router.back()
        }
    }

    fun onBack() {
        router.back()
    }
}
