package com.omnideck.shell.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnideck.designsystem.component.EmptySurface
import com.omnideck.designsystem.component.ModuleTile
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.sdk.ModuleId

/**
 * The launcher grid.
 *
 * Note that it renders whatever the lifecycle manager discovered — it has no
 * knowledge of any specific module. Dropping a new directory into `modules/` makes a
 * tile appear here with no edit to this file (goal G1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(modules: List<ModuleTileModel>, onModuleClick: (ModuleId) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { LargeTopAppBar(title = { Text("OmniDeck") }) },
    ) { padding ->
        if (modules.isEmpty()) {
            EmptySurface(
                title = "No modules yet",
                message = "Create a directory under modules/ with a build.gradle.kts and a " +
                    "ModuleEntryPoint, then rebuild. It will appear here automatically.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Scaffold
        }

        LazyVerticalGrid(
            // Adaptive rather than a fixed count: one grid works on phone, tablet and
            // unfolded foldable, which is a Play large-screen quality requirement.
            columns = GridCells.Adaptive(minSize = 220.dp),
            contentPadding = PaddingValues(
                start = Spacing.md,
                end = Spacing.md,
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
