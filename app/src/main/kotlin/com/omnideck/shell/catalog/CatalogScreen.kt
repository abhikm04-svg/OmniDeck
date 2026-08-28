package com.omnideck.shell.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnideck.designsystem.component.ConfirmDialog
import com.omnideck.designsystem.component.EmptySurface
import com.omnideck.designsystem.component.PrimaryButton
import com.omnideck.designsystem.component.SecondaryButton
import com.omnideck.designsystem.component.TertiaryButton
import com.omnideck.designsystem.layout.contentPadding
import com.omnideck.designsystem.layout.rememberWindowWidthClass
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleState
import com.omnideck.shell.ShellViewModel

/**
 * The Catalog (OD-305) — where a user acquires and removes modules.
 *
 * On-demand delivery is only half a feature without it: a module that is not in the
 * base APK has no tile to tap until something asks Play for it, and "free up space"
 * is not a thing a user can do at all. Both live here.
 *
 * It renders whatever was discovered, in whatever delivery mode, and names no module
 * — the same property the home grid holds (goal G1).
 */
@Composable
fun CatalogRoute(shell: ShellViewModel = hiltViewModel(), catalog: CatalogViewModel = hiltViewModel()) {
    val entries by catalog.entries.collectAsState()

    CatalogScreen(
        entries = entries,
        onInstall = catalog::onInstall,
        onRemove = catalog::onRemove,
        onOpen = shell::onModuleClicked,
        onDetails = shell::onModuleStatus,
        onBack = shell::onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    entries: List<CatalogEntry>,
    onInstall: (ModuleId) -> Unit,
    onRemove: (ModuleId) -> Unit,
    onOpen: (ModuleId) -> Unit,
    onDetails: (ModuleId) -> Unit,
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
        if (entries.isEmpty()) {
            EmptySurface(
                title = "Nothing to show",
                message = "No modules were discovered in this build.",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = contentPadding(widthClass),
                end = contentPadding(widthClass),
                top = padding.calculateTopPadding() + Spacing.sm,
                bottom = padding.calculateBottomPadding() + Spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(entries, key = { it.id.value }) { entry ->
                CatalogRow(
                    entry = entry,
                    onInstall = { onInstall(entry.id) },
                    onOpen = { onOpen(entry.id) },
                    onDetails = { onDetails(entry.id) },
                    onRemove = { pendingRemoval = entry },
                )
            }
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
    onOpen: () -> Unit,
    onDetails: () -> Unit,
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
                // A determinate bar wherever the provider gave us bytes: a stalled 43%
                // is diagnosable and an indeterminate spinner is not (OD-302).
                val fraction = entry.installProgress
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Spacing.sm))
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    )
                }
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CatalogActions(entry, onInstall, onOpen, onDetails, onRemove)
            }
        }
    }
}

@Composable
private fun CatalogActions(
    entry: CatalogEntry,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
) {
    when (entry.state) {
        ModuleState.ADVERTISED -> PrimaryButton(text = entry.installLabel(), onClick = onInstall)

        ModuleState.ACTIVE, ModuleState.DEGRADED, ModuleState.INSTALLED, ModuleState.SUSPENDED -> {
            PrimaryButton(text = "Open", onClick = onOpen)
            // A bundled module ships inside the base APK: there is nothing for Play to
            // reclaim, and offering "Remove" would promise a space saving that cannot
            // happen. Its data is still erasable — from the Privacy Centre, where
            // erasure belongs.
            if (!entry.isBundled) {
                TertiaryButton(text = "Remove", onClick = onRemove)
            }
        }

        // Gated, quarantined or failed: the status screen is the one place that
        // explains why in the user's language and offers what can actually help.
        ModuleState.GATED, ModuleState.QUARANTINED, ModuleState.FAILED ->
            SecondaryButton(text = "Why not?", onClick = onDetails)

        ModuleState.INSTALLING, ModuleState.INITIALIZING, ModuleState.PURGING -> Unit
    }
}

private fun CatalogEntry.installLabel(): String =
    if (downloadBytes <= 0) "Install" else "Install (${downloadBytes.asMegabytes()})"

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

private fun Long.asMegabytes(): String = "%.1f MB".format(this / BYTES_IN_MB)

private const val BYTES_IN_MB = 1_048_576.0
