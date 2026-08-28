package com.omnideck.shell.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnideck.designsystem.component.LoadingSurface
import com.omnideck.designsystem.layout.ReadableColumn
import com.omnideck.designsystem.layout.rememberWindowWidthClass
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.shell.ShellViewModel
import java.util.Locale

@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel(), shell: ShellViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SettingsScreen(
        state = state,
        onBack = shell::onBack,
        onPrivacy = { shell.onPrivacy() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsState, onBack: () -> Unit, onPrivacy: () -> Unit, modifier: Modifier = Modifier) {
    val widthClass = rememberWindowWidthClass()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                SectionHeader("Privacy")
                NavigationRow(
                    title = "Privacy Centre",
                    subtitle = "What each module stores, and how to erase it",
                    onClick = onPrivacy,
                )

                SectionHeader("Modules")
                state.modules.forEach { module ->
                    ModuleRow(module)
                    HorizontalDivider()
                }

                SectionHeader("About")
                DetailRow("Host SDK", state.hostSdkVersion.toString())
                DetailRow("Build", state.hostVersionCode.toString())
                Text(
                    text = "Modules declare the SDK range they support. One outside this " +
                        "version is never started — it is shown as needing an app update instead.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
        }
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

@Composable
private fun NavigationRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun ModuleRow(module: InstalledModule) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text("${module.displayName} ${module.version}", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "${module.state.name.lowercase(Locale.ROOT)} · ${formatBytes(module.storageBytes)} · " +
                "owned by ${module.owner}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Deliberately decimal, not binary: a user comparing this with the figure Android's
 * own storage settings shows should see the same number.
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes < BYTES_IN_KB -> "$bytes B"
    bytes < BYTES_IN_MB -> String.format(Locale.ROOT, "%.0f kB", bytes / BYTES_IN_KB)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / BYTES_IN_MB)
}

private const val BYTES_IN_KB = 1_000.0
private const val BYTES_IN_MB = 1_000_000.0
