package com.omnideck.shell.privacy

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnideck.designsystem.component.ConfirmDialog
import com.omnideck.designsystem.component.LoadingSurface
import com.omnideck.designsystem.component.SecondaryButton
import com.omnideck.designsystem.component.TertiaryButton
import com.omnideck.designsystem.layout.ReadableColumn
import com.omnideck.designsystem.layout.rememberWindowWidthClass
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.capability.ConsentPurpose
import com.omnideck.shell.settings.formatBytes
import java.util.Locale

@Composable
fun PrivacyCentreRoute(viewModel: PrivacyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PrivacyCentreScreen(
        state = state,
        onBack = viewModel::onBack,
        onConsentChanged = viewModel::onConsentChanged,
        onErase = { viewModel.onEraseModule(it) },
        onEraseEverything = { viewModel.onEraseEverything() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyCentreScreen(
    state: PrivacyState,
    onBack: () -> Unit,
    onConsentChanged: (ConsentPurpose, Boolean) -> Unit,
    onErase: (ModuleId) -> Unit,
    onEraseEverything: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val widthClass = rememberWindowWidthClass()
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Privacy Centre") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingSurface(Modifier.padding(padding))
            return@Scaffold
        }

        ReadableColumn(widthClass, Modifier.padding(padding)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ConsentSection(state, onConsentChanged)
                ModulesSection(state) { confirming = it }
                EraseEverythingSection { confirming = ERASE_ALL }
            }
        }
    }

    confirming?.let { target ->
        val everything = target == ERASE_ALL
        ConfirmDialog(
            title = if (everything) "Erase all module data?" else "Erase this module's data?",
            message = "This cannot be undone. Anything not yet synced to your account is lost.",
            confirmLabel = "Erase",
            destructive = true,
            onConfirm = {
                confirming = null
                if (everything) onEraseEverything() else onErase(ModuleId(target))
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun ConsentSection(state: PrivacyState, onConsentChanged: (ConsentPurpose, Boolean) -> Unit) {
    SectionHeader("How your data is used")
    state.purposes.forEach { (purpose, granted) ->
        ConsentRow(purpose = purpose, granted = granted, onChange = { onConsentChanged(purpose, it) })
    }
}

@Composable
private fun ModulesSection(state: PrivacyState, onEraseRequested: (String) -> Unit) {
    SectionHeader("What each module holds")
    state.modules.forEach { module ->
        ModulePrivacyCard(
            module = module,
            erasing = state.erasing == module.id,
            onErase = { onEraseRequested(module.id.value) },
        )
        HorizontalDivider()
    }
}

@Composable
private fun EraseEverythingSection(onEraseRequested: () -> Unit) {
    SectionHeader("Erase everything")
    Text(
        text = "Deletes every module's data on this device. Each module's own erase step " +
            "runs first, so nothing is left behind an open handle.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.md),
    )
    TertiaryButton(
        text = "Erase all module data",
        onClick = onEraseRequested,
        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
    )
}

@Composable
private fun ConsentRow(purpose: ConsentPurpose, granted: Boolean, onChange: (Boolean) -> Unit) {
    val essential = purpose == ConsentPurpose.ESSENTIAL

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(purpose.label(), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = purpose.explanation(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Essential is shown, and shown as on, rather than hidden: a purpose the user
        // cannot decline is still a purpose they are entitled to see.
        Switch(checked = granted, onCheckedChange = onChange, enabled = !essential)
    }
}

@Composable
private fun ModulePrivacyCard(module: ModulePrivacyRow, erasing: Boolean, onErase: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(module.displayName, style = MaterialTheme.typography.titleMedium)
        Text(
            text = when {
                module.dataCategories.isEmpty() ->
                    "Not yet known — this module has not been started on this device."
                else -> module.dataCategories.joinToString { it.name.lowercase(Locale.ROOT).replace('_', ' ') }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (module.permissions.isNotEmpty()) {
            Text(
                text = "Permissions: " + module.permissions.joinToString { it.substringAfterLast('.') },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${formatBytes(module.storageBytes)} on this device",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecondaryButton(text = "Erase", onClick = onErase, loading = erasing)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = Spacing.md, top = Spacing.lg, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

private fun ConsentPurpose.label(): String = when (this) {
    ConsentPurpose.ESSENTIAL -> "Essential"
    ConsentPurpose.PRODUCT_ANALYTICS -> "Product analytics"
    ConsentPurpose.CRASH_DIAGNOSTICS -> "Crash diagnostics"
    ConsentPurpose.PERSONALISATION -> "Personalisation"
    ConsentPurpose.MARKETING -> "Marketing"
}

private fun ConsentPurpose.explanation(): String = when (this) {
    ConsentPurpose.ESSENTIAL -> "Needed to sign in, sync and keep the app secure. Cannot be turned off."
    ConsentPurpose.PRODUCT_ANALYTICS -> "Which features are used, so we know what to improve."
    ConsentPurpose.CRASH_DIAGNOSTICS -> "Stack traces when something breaks, attributed to the module at fault."
    ConsentPurpose.PERSONALISATION -> "Ordering and suggestions based on how you use OmniDeck."
    ConsentPurpose.MARKETING -> "Messages about new modules and offers."
}

private const val ERASE_ALL = "*"
