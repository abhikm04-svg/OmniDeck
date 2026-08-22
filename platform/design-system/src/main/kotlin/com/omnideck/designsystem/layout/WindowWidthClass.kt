package com.omnideck.designsystem.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.omnideck.designsystem.theme.Spacing

/**
 * Window size classes (OD-112).
 *
 * Derived from a width rather than read from the platform so the decision is a pure
 * function — testable without a device, and identical in a screenshot test and on a
 * real foldable. Breakpoints are Material's.
 */
enum class WindowWidthClass {
    /** Phone portrait, and a folded foldable. */
    Compact,

    /** Tablet portrait, unfolded foldable, large phone landscape. */
    Medium,

    /** Tablet landscape and desktop-class windows. */
    Expanded,
    ;

    companion object {
        fun fromWidth(widthDp: Int): WindowWidthClass = when {
            widthDp < COMPACT_MAX -> Compact
            widthDp < MEDIUM_MAX -> Medium
            else -> Expanded
        }

        private const val COMPACT_MAX = 600
        private const val MEDIUM_MAX = 840
    }
}

/**
 * How many grid columns a module tile list should use at [widthClass].
 *
 * Centralised because Play assesses large-screen quality on exactly this: a phone
 * layout stretched across a tablet is the most common way an app loses its
 * large-screen tier.
 */
fun moduleGridColumns(widthClass: WindowWidthClass): Int = when (widthClass) {
    WindowWidthClass.Compact -> COMPACT_COLUMNS
    WindowWidthClass.Medium -> MEDIUM_COLUMNS
    WindowWidthClass.Expanded -> EXPANDED_COLUMNS
}

private const val COMPACT_COLUMNS = 1
private const val MEDIUM_COLUMNS = 2
private const val EXPANDED_COLUMNS = 3

/** Content inset that grows with the window, so text does not run edge to edge. */
fun contentPadding(widthClass: WindowWidthClass): Dp = when (widthClass) {
    WindowWidthClass.Compact -> Spacing.md
    WindowWidthClass.Medium -> Spacing.lg
    WindowWidthClass.Expanded -> Spacing.xl
}

/**
 * Maximum width for a single column of prose.
 *
 * Long lines are hard to read; on an expanded window the answer is to stop growing
 * rather than to keep stretching. Roughly 70 characters at body size.
 */
val ReadableContentWidth: Dp = 640.dp

/** Grid cells for a module tile list at the given width. */
fun moduleGridCells(widthClass: WindowWidthClass): GridCells = GridCells.Fixed(moduleGridColumns(widthClass))

/**
 * List-detail scaffold.
 *
 * On a compact window only one pane is shown, so the caller drives navigation as
 * usual. From medium up both are visible side by side, which is what a tablet user
 * expects and what the large-screen tier rewards.
 */
@Composable
fun ListDetailScaffold(
    widthClass: WindowWidthClass,
    detailVisible: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (widthClass == WindowWidthClass.Compact) {
        Box(modifier.fillMaxSize()) {
            if (detailVisible) detail() else list()
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding(widthClass)),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Box(
            Modifier
                .weight(LIST_PANE_WEIGHT)
                .fillMaxHeight(),
        ) { list() }
        Box(
            Modifier
                .weight(DETAIL_PANE_WEIGHT)
                .fillMaxHeight(),
        ) { detail() }
    }
}

/**
 * Constrains prose to [ReadableContentWidth] and centres it, so an expanded window
 * shows comfortable text rather than one very long line.
 */
@Composable
fun ReadableColumn(widthClass: WindowWidthClass, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(contentPadding(widthClass)),
        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
    ) {
        Box(Modifier.widthIn(max = ReadableContentWidth)) { content() }
    }
}

private const val LIST_PANE_WEIGHT = 0.38f
private const val DETAIL_PANE_WEIGHT = 0.62f
