package com.omnideck.notes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.omnideck.designsystem.component.ConfirmDialog
import com.omnideck.designsystem.component.ErrorSurface
import com.omnideck.designsystem.component.LoadingSurface
import com.omnideck.designsystem.component.OmniTextField
import com.omnideck.designsystem.component.PrimaryButton
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.notes.NotesComponent

/**
 * Destination wrapper for both "new note" and "edit note".
 *
 * [noteId] is part of the ViewModel key so opening a second note from a deep link
 * builds a second ViewModel rather than showing the first one's contents.
 */
@Composable
fun NoteEditorRoute(component: NotesComponent, noteId: String?) {
    val viewModel: NoteEditorViewModel = viewModel(
        key = noteId ?: "new",
        factory = viewModelFactory {
            initializer {
                NoteEditorViewModel(
                    repository = component.repository,
                    router = component.router,
                    telemetry = component.telemetry,
                    sync = component.sync,
                    noteId = noteId,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    NoteEditorScreen(
        state = state,
        isNew = noteId == null,
        onTitleChange = viewModel::onTitleChange,
        onBodyChange = viewModel::onBodyChange,
        onSave = viewModel::onSave,
        onDelete = viewModel::onDelete,
        onBack = viewModel::onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    state: NoteEditorState,
    isNew: Boolean,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Survives process death, unlike the ViewModel's own flag: a confirmation dialog
    // left open when the process is killed should still be open on restore.
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New note" else "Edit note") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete note")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingSurface(Modifier.padding(padding))

            state.missing -> ErrorSurface(
                title = "Note not found",
                message = "This note was deleted on another device.",
                onRetry = onBack,
                modifier = Modifier.padding(padding),
            )

            else -> EditorBody(
                state = state,
                onTitleChange = onTitleChange,
                onBodyChange = onBodyChange,
                onSave = onSave,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (confirmingDelete) {
        ConfirmDialog(
            title = "Delete this note?",
            message = "It will be removed from this device and from your other devices.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
private fun EditorBody(
    state: NoteEditorState,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(Spacing.md),
    ) {
        OmniTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = "Title",
            imeAction = ImeAction.Next,
        )
        OmniTextField(
            value = state.body,
            onValueChange = onBodyChange,
            label = "Note",
            singleLine = false,
            supportingText = "Saved on this device the moment you tap Save.",
            modifier = Modifier.padding(top = Spacing.md),
        )
        PrimaryButton(
            text = "Save",
            onClick = onSave,
            enabled = state.canSave,
            loading = state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.lg),
        )
    }
}
