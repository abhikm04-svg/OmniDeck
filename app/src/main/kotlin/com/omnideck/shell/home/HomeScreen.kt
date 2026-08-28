package com.omnideck.shell.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.omnideck.designsystem.component.EmptySurface
import com.omnideck.designsystem.component.ModuleTile
import com.omnideck.designsystem.layout.contentPadding
import com.omnideck.designsystem.layout.moduleGridCells
import com.omnideck.designsystem.layout.rememberWindowWidthClass
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.sdk.ModuleId
import com.omnideck.shell.ModuleTileModel

/**
 * The launcher grid.
 *
 * Note that it renders whatever the lifecycle manager discovered — it has no
 * knowledge of any specific module. Dropping a new directory into `modules/` makes a
 * tile appear here with no edit to this file (goal G1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modules: List<ModuleTileModel>,
    onModuleClick: (ModuleId) -> Unit,
    onCatalog: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Taken from the design system rather than chosen here, so the Shell and every
    // module agree on what "medium" means. Play grades large-screen quality on
    // exactly this, and a phone layout stretched across a tablet is how apps lose it.
    val widthClass = rememberWindowWidthClass()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = { Text("OmniDeck") },
                actions = {
                    // The only way to reach a module that is not in the base APK
                    // (OD-305): an on-demand module has no tile until something asks
                    // Play for it.
                    IconButton(onClick = onCatalog) {
                        Icon(Icons.Default.Widgets, contentDescription = "Modules")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        if (modules.isEmpty()) {
            EmptySurface(
                title = "No modules yet",
                message = "Create a directory under modules/ with a build.gradle.kts and a " +
                    "ModuleEntryPoint, then rebuild. It will appear here automatically.",
                actionLabel = "Browse modules",
                onAction = onCatalog,
                modifier = Modifier.fillMaxSize(),
            )
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = moduleGridCells(widthClass),
            contentPadding = PaddingValues(
                start = contentPadding(widthClass),
                end = contentPadding(widthClass),
                top = padding.calculateTopPadding() + Spacing.sm,
                bottom = padding.calculateBottomPadding() + Spacing.md,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(modules, key = { it.id.value }) { module ->
                ModuleTile(
                    title = module.title,
                    subtitle = module.subtitle,
                    state = module.tileState,
                    onClick = { onModuleClick(module.id) },
                )
            }
        }
    }
}
