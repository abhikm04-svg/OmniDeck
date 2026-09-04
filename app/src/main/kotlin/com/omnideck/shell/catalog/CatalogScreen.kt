package com.omnideck.shell.catalog

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnideck.designsystem.component.ConfirmDialog
import com.omnideck.designsystem.component.EmptySurface
import com.omnideck.designsystem.component.OmniTextField
import com.omnideck.designsystem.component.PrimaryButton
import com.omnideck.designsystem.component.SecondaryButton
import com.omnideck.designsystem.component.TertiaryButton
import com.omnideck.designsystem.layout.contentPadding
import com.omnideck.designsystem.layout.rememberWindowWidthClass
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.sdk.ModuleCategory
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleState
import com.omnideck.shell.ShellViewModel
import com.omnideck.shell.navigation.LocalShellViewModel

/**
 * The Catalog (OD-305) — where a user acquires and removes modules.
 *
 * On-demand delivery is only half a feature without it: a module that is not in the
 * base APK has no tile to tap until something asks Play for it, and "free up space"
 * is not a thing a user can do at all. Both live here.
 *
 * It renders whatever was discovered, in whatever delivery mode, and names no module
 * — the same property the home grid holds (goal G1). The category chips are built
 * from what was found rather than from the full enum, for the same reason.
 */
@Composable
fun CatalogRoute(shell: ShellViewModel = LocalShellViewModel.current, catalog: CatalogViewModel = hiltViewModel()) {
    val state by catalog.state.collectAsState()

    CatalogScreen(
        state = state,
        onQueryChange = catalog::onQueryChange,
        onCategorySelected = catalog::onCategorySelected,
        onInstall = catalog::onInstall,
        onCancel = catalog::onCancel,
        onRemove = catalog::onRemove,
        onOpen = shell::onModuleClicked,
        onDetails = shell::onCatalogDetail,
        onStatus = shell::onModuleStatus,
        onBack = shell::onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    state: CatalogUiState,
    onQueryChange: (String) -> Unit,
    onCategorySelected: (ModuleCategory?) -> Unit,
    onInstall: (ModuleId) -> Unit,
    onCancel: (ModuleId) -> Unit,
    onRemove: (ModuleId) -> Unit,
    onOpen: (ModuleId) -> Unit,
    onDetails: (ModuleId) -> Unit,
    onStatus: (ModuleId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val widthClass = rememberWindowWidthClass()
    var pendingRemoval by remember { mutableStateOf<CatalogEntry?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Modules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = contentPadding(widthClass)),
        ) {
            CatalogFilters(state, onQueryChange, onCategorySelected)
            CatalogList(
                state = state,
                onInstall = onInstall,
                onCancel = onCancel,
                onOpen = onOpen,
                onDetails = onDetails,
                onStatus = onStatus,
                onRemove = { pendingRemoval = it },
            )
        }
    }

    pendingRemoval?.let { entry ->
        RemoveModuleDialog(
            entry = entry,
            onConfirm = {
                onRemove(entry.id)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

/**
 * Search and category filters.
 *
 * Both are hidden below a handful of modules: a filter row over three rows of content
 * costs a third of the screen to save nobody any scrolling, and a search box implies
 * there is something to search for.
 */
@Composable
private fun CatalogFilters(
    state: CatalogUiState,
    onQueryChange: (String) -> Unit,
    onCategorySelected: (ModuleCategory?) -> Unit,
) {
    if (state.totalCount < FILTERS_WORTH_SHOWING) return

    OmniTextField(
        value = state.query,
        onValueChange = onQueryChange,
        label = "Search",
        placeholder = "Name or id",
        imeAction = ImeAction.Search,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
    )

    if (state.categories.size < 2) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        FilterChip(
            selected = state.selectedCategory == null,
            onClick = { onCategorySelected(null) },
            label = { Text("All") },
        )
        state.categories.forEach { category ->
            FilterChip(
                selected = state.selectedCategory == category,
                onClick = { onCategorySelected(category.takeIf { it != state.selectedCategory }) },
                label = { Text(category.label()) },
            )
        }
    }
}

@Composable
private fun CatalogList(
    state: CatalogUiState,
    onInstall: (ModuleId) -> Unit,
    onCancel: (ModuleId) -> Unit,
    onOpen: (ModuleId) -> Unit,
    onDetails: (ModuleId) -> Unit,
    onStatus: (ModuleId) -> Unit,
    onRemove: (CatalogEntry) -> Unit,
) {
    if (state.entries.isEmpty()) {
        // Told apart deliberately: "your filter matched nothing" is recoverable by the
        // user, "this build has no modules" is not, and one message for both leaves
        // whoever hit the second one looking for a filter to clear.
        EmptySurface(
            title = if (state.isFiltered) "No matches" else "Nothing to show",
            message = if (state.isFiltered) {
                "No module matches that. Try a different search or category."
            } else {
                "No modules were discovered in this build."
            },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(state.entries, key = { it.id.value }) { entry ->
            CatalogRow(
                entry = entry,
                onInstall = { onInstall(entry.id) },
                onCancel = { onCancel(entry.id) },
                onOpen = { onOpen(entry.id) },
                onDetails = { onDetails(entry.id) },
                onStatus = { onStatus(entry.id) },
                onRemove = { onRemove(entry) },
            )
        }
    }
}

@Composable
private fun RemoveModuleDialog(entry: CatalogEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Remove ${entry.title}?",
        // Says what actually happens rather than what sounds reassuring: the data goes
        // now, the download does not necessarily come back immediately, and Play
        // reclaims the space on its own schedule (deferredUninstall).
        message = "Everything ${entry.title} has stored on this device is deleted straight away. " +
            "Google Play frees the download itself later, so you may not see the space back " +
            "immediately. You can install it again at any time.",
        confirmLabel = "Remove",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onStatus: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = entry.supportingText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xxs),
            )

            if (entry.isBusy) {
                InstallProgressRow(entry, onCancel)
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CatalogActions(entry, onInstall, onOpen, onDetails, onStatus, onRemove)
            }
        }
    }
}

/**
 * The bar, and the way out of it.
 *
 * A download with no Cancel is the other half of the "stuck at 0%" complaint OD-302
 * is about: a user who cannot stop a stalled or unexpectedly large download can only
 * kill the app, which leaves Play downloading anyway.
 */
@Composable
private fun InstallProgressRow(entry: CatalogEntry, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A determinate bar wherever the provider gave us bytes: a stalled 43% is
        // diagnosable and an indeterminate spinner is not (OD-302).
        val fraction = entry.installProgress
        val barModifier = Modifier
            .weight(1f)
            .semantics { contentDescription = entry.progressDescription() }
        if (fraction == null) {
            LinearProgressIndicator(barModifier)
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = barModifier)
        }
        if (entry.isCancellable) {
            TertiaryButton(text = "Cancel", onClick = onCancel)
        }
    }
}

@Composable
private fun CatalogActions(
    entry: CatalogEntry,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onStatus: () -> Unit,
    onRemove: () -> Unit,
) {
    when (entry.state) {
        ModuleState.ADVERTISED -> {
            PrimaryButton(text = entry.installLabel(), onClick = onInstall)
            // Size and permissions before the download, not after: OD-305's disclosure
            // is only a disclosure if it precedes the decision it informs.
            SecondaryButton(text = "Details", onClick = onDetails)
        }

        ModuleState.ACTIVE, ModuleState.DEGRADED, ModuleState.INSTALLED, ModuleState.SUSPENDED -> {
            PrimaryButton(text = "Open", onClick = onOpen)
            SecondaryButton(text = "Details", onClick = onDetails)
            // A bundled module ships inside the base APK: there is nothing for Play to
            // reclaim, and offering "Remove" would promise a space saving that cannot
            // happen. Its data is still erasable — from the Privacy Centre, where
            // erasure belongs.
            if (!entry.isBundled) {
                TertiaryButton(text = "Remove", onClick = onRemove)
            }
        }

        // Gated, quarantined or failed: the detail screen carries the status section
        // that explains why in the user's language and offers what can actually help.
        ModuleState.GATED, ModuleState.QUARANTINED, ModuleState.FAILED ->
            SecondaryButton(text = "Why not?", onClick = onStatus)

        ModuleState.INSTALLING, ModuleState.INITIALIZING, ModuleState.PURGING -> Unit
    }
}

internal fun ModuleCategory.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)

private fun CatalogEntry.installLabel(): String = when {
    // Removed, but Play has not reclaimed the split yet (OD-307): the code is still on
    // the device, so this fetches nothing and quoting a size would be a false promise.
    awaitingPlayCleanup -> "Add back"
    downloadBytes <= 0 -> "Install"
    else -> "Install (${downloadBytes.asMegabytes()})"
}

/** Spoken instead of the bar, which TalkBack otherwise announces as a bare percentage. */
private fun CatalogEntry.progressDescription(): String = when {
    state == ModuleState.PURGING -> "Removing $title"
    installProgress == null -> "Installing $title"
    else -> "Downloading $title, ${(installProgress * PERCENT).toInt()} percent"
}

/**
 * One line under the title. Prefers the module's own summary, then the reason it is
 * unusable, and only then falls back to describing the state — because "Quarantined"
 * tells a user nothing they can act on.
 */
private fun CatalogEntry.supportingText(): String {
    val problem = reason
    return when {
        isBusy && state == ModuleState.PURGING -> "Removing…"
        isBusy -> "Installing…"
        // Covers a degraded module, a failed download and a quarantine alike: the
        // lifecycle manager already phrased each of those for a person to read.
        problem != null -> problem
        summary.isNotEmpty() -> summary
        detailsAreProvisional && state == ModuleState.ADVERTISED ->
            "Not installed yet — details arrive with the download."
        isBundled -> "Included with OmniDeck"
        else -> "Installed"
    }
}

internal fun Long.asMegabytes(): String = "%.1f MB".format(this / BYTES_IN_MB)

private const val BYTES_IN_MB = 1_048_576.0
private const val PERCENT = 100
private const val FILTERS_WORTH_SHOWING = 4
