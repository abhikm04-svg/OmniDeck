package com.omnideck.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnideck.notes.NotesComponent
import com.omnideck.notes.data.Note
import com.omnideck.notes.data.NotesRepository
import com.omnideck.sdk.Route
import com.omnideck.sdk.capability.Router
import com.omnideck.sdk.capability.TelemetryService
import com.omnideck.sdk.sync.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotesListState(
    val notes: List<Note> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    /** Local edits the server has not acknowledged. Zero is the quiet, normal case. */
    val pendingChanges: Int = 0,
    /** Edits that will never be delivered. Non-zero must be visible, not buried in a log. */
    val undeliverableChanges: Int = 0,
    val syncing: Boolean = false,
    /** Null when the module is running local-only because no endpoint is configured. */
    val syncConfigured: Boolean = true,
)

/**
 * The notes list.
 *
 * Reads from the database, never from the network: the list is correct offline, and
 * a sync merely changes the badge (architecture.md §11.2). Filtering happens here
 * rather than in SQL because a note body is small and the corpus is a user's own —
 * a LIKE query would buy nothing and cost an FTS table to keep in step.
 */
class NotesListViewModel(
    private val repository: NotesRepository,
    private val router: Router,
    private val telemetry: TelemetryService,
    private val sync: NotesComponent.NotesSync?,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val _state = MutableStateFlow(NotesListState(syncConfigured = sync != null))
    val state: StateFlow<NotesListState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.observeNotes(), query) { notes, q -> notes.filter { it.matches(q) } to q }
                .collect { (visible, q) ->
                    _state.update { it.copy(notes = visible, query = q, loading = false) }
                    refreshCounts()
                }
        }
        sync?.let { active ->
            viewModelScope.launch {
                active.engine.state.collect { syncState ->
                    _state.update {
                        it.copy(
                            syncing = syncState is SyncState.Syncing,
                            pendingChanges = syncState.pending,
                        )
                    }
                }
            }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onCreate() {
        telemetry.event("notes_create_tapped")
        navigate(Route("omnideck://notes/new"))
    }

    fun onOpen(note: Note) = navigate(Route("omnideck://notes/note/${note.id}"))

    private fun navigate(route: Route) = viewModelScope.launch { router.navigate(route) }

    private suspend fun refreshCounts() = _state.update {
        it.copy(
            pendingChanges = repository.pendingChanges(),
            undeliverableChanges = repository.undeliverableChanges(),
        )
    }

    private fun Note.matches(query: String): Boolean = query.isBlank() ||
        title.contains(query, ignoreCase = true) ||
        body.contains(query, ignoreCase = true)
}
