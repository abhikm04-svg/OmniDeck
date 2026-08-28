package com.omnideck.notes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.omnideck.designsystem.component.EmptySurface
import com.omnideck.designsystem.component.LoadingSurface
import com.omnideck.designsystem.component.OmniTextField
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.notes.NotesComponent
import com.omnideck.notes.data.Note

/**
 * Destination wrapper.
 *
 * The module owns its own ViewModel construction because it has no Hilt (ADR-002) —
 * [NotesComponent] is the graph, and `viewModelFactory` is the only glue needed.
 */
@Composable
fun NotesListRoute(component: NotesComponent) {
    val viewModel: NotesListViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                NotesListViewModel(
                    repository = component.repository,
                    router = component.router,
                    telemetry = component.telemetry,
                    sync = component.sync,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    NotesListScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onOpen = viewModel::onOpen,
        onCreate = viewModel::onCreate,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    state: NotesListState,
    onQueryChange: (String) -> Unit,
    onOpen: (Note) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Notes") }, actions = { SyncBadge(state) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = "New note")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OmniTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = "Search",
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )

            if (state.undeliverableChanges > 0) {
                UndeliverableBanner(state.undeliverableChanges)
            }

            when {
                state.loading -> LoadingSurface(label = "Loading notes…")

                state.notes.isEmpty() && state.query.isNotBlank() -> EmptySurface(
                    title = "No matches",
                    message = "Nothing here contains \"${state.query}\".",
                )

                state.notes.isEmpty() -> EmptySurface(
                    title = "No notes yet",
                    message = "Tap + to write one. Notes are saved on this device immediately, " +
                        "with or without a connection.",
                )

                else -> LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl)) {
                    items(state.notes, key = { it.id }) { note ->
                        NoteRow(note, onClick = { onOpen(note) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(note: Note, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                text = note.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = note.preview.ifBlank { "No content" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (note.pendingSync) {
            // Informational, not an error: the note is safe locally. Announced so it
            // is not a colour-only signal.
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = "Waiting to sync",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
    }
}

@Composable
private fun SyncBadge(state: NotesListState) {
    when {
        !state.syncConfigured -> Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = "Saved on this device only",
            modifier = Modifier.padding(end = Spacing.md),
        )

        state.syncing || state.pendingChanges > 0 -> Row(
            modifier = Modifier
                .padding(end = Spacing.md)
                .clearAndSetSemantics {
                    contentDescription = "${state.pendingChanges} changes waiting to sync"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            if (state.pendingChanges > 0) {
                Text(
                    text = state.pendingChanges.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun UndeliverableBanner(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(ICON_SIZE),
        )
        Text(
            text = "$count change${if (count == 1) "" else "s"} could not be sent. " +
                "They are still saved on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = Spacing.sm),
        )
    }
}

private val ICON_SIZE = 20.dp
