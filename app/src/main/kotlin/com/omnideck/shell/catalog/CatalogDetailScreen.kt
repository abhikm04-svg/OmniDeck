package com.omnideck.shell.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnideck.designsystem.component.EmptySurface
import com.omnideck.designsystem.component.PrimaryButton
import com.omnideck.designsystem.component.TertiaryButton
import com.omnideck.designsystem.layout.contentPadding
import com.omnideck.designsystem.layout.rememberWindowWidthClass
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.sdk.DataCategory
import com.omnideck.sdk.EntitlementPolicy
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleState
import com.omnideck.shell.ShellViewModel
import com.omnideck.shell.navigation.LocalShellViewModel

/**
 * One module's detail page (OD-305).
 *
 * Its job is disclosure *before* the decision it informs: how large the download is,
 * what runtime permissions the module may ask for, and which Play Data Safety
 * categories it declares. Presenting that after the install would make it a receipt
 * rather than a disclosure.
 *
 * For a module that has never been installed most of this is genuinely unknown — the
 * manifest is Kotlin inside the split — and the page says so rather than rendering
 * empty sections that read as "asks for nothing". That gap closes with the
 * server-side Module Registry in Phase 4 (architecture.md §6.2).
 */
@Composable
fun CatalogDetailRoute(
    moduleId: ModuleId,
    shell: ShellViewModel = LocalShellViewModel.current,
    catalog: CatalogViewModel = hiltViewModel(),
) {
    val entries by catalog.entries.collectAsState()
    val entry = remember(entries, moduleId) { entries.firstOrNull { it.id == moduleId } }

    CatalogDetailScreen(
        entry = entry,
        moduleId = moduleId,
        onInstall = catalog::onInstall,
        onCancel = catalog::onCancel,
        onRemove = catalog::onRemove,
        onOpen = shell::onModuleClicked,
        onBack = shell::onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogDetailScreen(
    entry: CatalogEntry?,
    moduleId: ModuleId,
    onInstall: (ModuleId) -> Unit,
    onCancel: (ModuleId) -> Unit,
    onRemove: (ModuleId) -> Unit,
    onOpen: (ModuleId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val widthClass = rememberWindowWidthClass()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(entry?.title ?: moduleId.shortId) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (entry == null) {
            // Reachable by URI, so it can name a module this build does not have.
            EmptySurface(
                title = "Not found",
                message = "${moduleId.value} was not discovered in this build.",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = contentPadding(widthClass))
                .verticalScroll(rememberScrollState()),
        ) {
            DetailHeader(entry)
            DetailActions(entry, onInstall, onCancel, onRemove, onOpen)
            HorizontalDivider(Modifier.padding(vertical = Spacing.md))
            DetailDisclosure(entry)
        }
    }
}

@Composable
private fun DetailHeader(entry: CatalogEntry) {
    Text(
        text = entry.summary.ifEmpty { "Not installed yet — details arrive with the download." },
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = Spacing.md),
    )
    entry.reason?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

@Composable
private fun DetailActions(
    entry: CatalogEntry,
    onInstall: (ModuleId) -> Unit,
    onCancel: (ModuleId) -> Unit,
    onRemove: (ModuleId) -> Unit,
    onOpen: (ModuleId) -> Unit,
) {
    var confirmingRemoval by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        when {
            entry.isCancellable -> TertiaryButton(text = "Cancel", onClick = { onCancel(entry.id) })
            entry.isBusy -> Unit
            entry.state == ModuleState.ADVERTISED ->
                PrimaryButton(text = entry.detailInstallLabel(), onClick = { onInstall(entry.id) })

            entry.state.isUsable() -> {
                PrimaryButton(text = "Open", onClick = { onOpen(entry.id) })
                if (!entry.isBundled) {
                    TertiaryButton(text = "Remove", onClick = { confirmingRemoval = true })
                }
            }

            else -> Unit
        }
    }

    if (confirmingRemoval) {
        RemoveModuleConfirmation(
            entry = entry,
            onConfirm = {
                onRemove(entry.id)
                confirmingRemoval = false
            },
            onDismiss = { confirmingRemoval = false },
        )
    }
}

/**
 * The disclosure itself.
 *
 * Sections are omitted when the answer is genuinely unknown and shown as an explicit
 * "none" when it is known to be empty — a module that asks for no permissions is a
 * fact worth stating, and is not the same as one whose permissions have never been
 * read.
 */
@Composable
private fun DetailDisclosure(entry: CatalogEntry) {
    DetailRow("Download", entry.downloadDisclosure())
    entry.category?.let { DetailRow("Category", it.label()) }
    entry.version?.let { DetailRow("Version", it.toString()) }
    DetailRow("Delivery", if (entry.isBundled) "Included with OmniDeck" else "Downloaded on demand")
    entry.entitlement?.let { DetailRow("Access", it.label()) }
    entry.owner?.let { DetailRow("Maintained by", it) }

    if (entry.detailsAreProvisional) {
        Text(
            text = "Permissions and data use are declared inside the module, so they " +
                "become visible once it is installed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md),
        )
        return
    }

    DetailRow("Works offline", if (entry.supportsOffline) "Yes" else "Needs a connection")
    DetailRow(
        label = "May ask for",
        value = entry.androidPermissions
            .map(::permissionLabel)
            .sorted()
            .joinToString()
            .ifEmpty { "No device permissions" },
    )
    DetailRow(
        label = "Data it declares",
        value = entry.dataCategories
            .map(DataCategory::label)
            .sorted()
            .joinToString()
            .ifEmpty { "None declared" },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RemoveModuleConfirmation(entry: CatalogEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    com.omnideck.designsystem.component.ConfirmDialog(
        title = "Remove ${entry.title}?",
        message = "Everything ${entry.title} has stored on this device is deleted straight away. " +
            "Google Play frees the download itself later, so you may not see the space back " +
            "immediately. You can install it again at any time.",
        confirmLabel = "Remove",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

private fun ModuleState.isUsable(): Boolean = this == ModuleState.ACTIVE ||
    this == ModuleState.DEGRADED ||
    this == ModuleState.INSTALLED ||
    this == ModuleState.SUSPENDED

private fun CatalogEntry.detailInstallLabel(): String =
    if (downloadBytes <= 0) "Install" else "Install (${downloadBytes.asMegabytes()})"

private fun CatalogEntry.downloadDisclosure(): String = when {
    isBundled -> "Nothing to download — included with OmniDeck"
    downloadBytes > 0 -> downloadBytes.asMegabytes()
    else -> "Size not known until the module is available"
}

private fun EntitlementPolicy.label(): String = when (this) {
    is EntitlementPolicy.Free -> "Free"
    is EntitlementPolicy.RequiresEntitlement -> "Requires a subscription"
    is EntitlementPolicy.Internal -> "Internal build only"
}

private fun DataCategory.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)

/** `android.permission.CAMERA` -> `Camera`. The full string means nothing to a user. */
private fun permissionLabel(permission: String): String =
    permission.substringAfterLast('.').lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)
