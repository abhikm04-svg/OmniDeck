package com.omnideck.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnideck.designsystem.theme.Spacing

/**
 * The Catalog / Home tile. One component renders every module state, so a new state
 * in the lifecycle machine cannot be forgotten in the UI — the `when` below is
 * exhaustive over [TileState] and will fail to compile if a state is added.
 */
@Composable
fun ModuleTile(title: String, subtitle: String, state: TileState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = state !is TileState.Quarantined && state !is TileState.Installing

    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { tileSemantics(title, subtitle, state, enabled) },
        colors = CardDefaults.cardColors(
            containerColor = tileContainerColor(state),
            contentColor = tileContentColor(state),
            // Both halves are needed. A quarantined or installing tile is disabled,
            // and a disabled Card ignores `containerColor` in favour of
            // `disabledContainerColor` — so setting only the former silently dropped
            // the error styling on the one state that most needs to look different.
            // Caught by the OD-113 screenshot, invisible in code review.
            disabledContainerColor = tileContainerColor(state),
            disabledContentColor = tileContentColor(state),
        ),
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        is TileState.Installing -> CircularProgressIndicator(Modifier.size(24.dp))
                        is TileState.Available -> Icon(Icons.Default.Download, contentDescription = null)
                        // A clock, not a download arrow: tapping this fetches nothing.
                        is TileState.AwaitingCleanup -> Icon(Icons.Default.Schedule, contentDescription = null)
                        is TileState.Gated -> Icon(Icons.Default.Lock, contentDescription = null)
                        is TileState.Quarantined -> Icon(Icons.Default.Warning, contentDescription = null)
                        is TileState.Ready -> Icon(Icons.Default.Widgets, contentDescription = null)
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.captionOrNull ?: subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state is TileState.Installing && state.fraction != null) {
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
                )
            }
        }
    }
}

/**
 * `clearAndSetSemantics` replaces the whole merged semantics node, `Card`'s own
 * included — so without the explicit [disabled], a quarantined or installing tile is
 * unclickable (`enabled` on `Card` still gates `onClick` itself) but TalkBack has no
 * way to know that; it announces the tile as an ordinary enabled control. Found on a
 * Gradle Managed Device (OD-317/OD-319): a test asserting the tile is not enabled
 * failed even though tapping it already did nothing.
 */
private fun SemanticsPropertyReceiver.tileSemantics(
    title: String,
    subtitle: String,
    state: TileState,
    enabled: Boolean,
) {
    contentDescription = "$title. $subtitle. ${state.accessibilityLabel}"
    if (!enabled) disabled()
}

/**
 * Quarantine is the one state that must read as "something is wrong" at a glance, so
 * it takes the error container; everything else sits on the ordinary surface.
 */
@Composable
private fun tileContainerColor(state: TileState) = when (state) {
    is TileState.Quarantined -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun tileContentColor(state: TileState) = when (state) {
    is TileState.Quarantined -> MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** UI projection of `ModuleState` — deliberately smaller than the full state machine. */
sealed interface TileState {
    data object Ready : TileState
    data class Available(val downloadMb: Double) : TileState
    data class Installing(val fraction: Float?) : TileState
    data class Gated(val reason: String) : TileState
    data class Quarantined(val reason: String) : TileState

    /**
     * Removed by the user, but its code is still on the device (OD-307).
     *
     * Distinct from [Available] because the two behave differently in the one way the
     * user can see: tapping this reopens the module immediately with no download, so a
     * tile advertising a download size here would be stating a figure that never
     * materialises. Play's `deferredUninstall` reclaims the space on its own schedule,
     * which is a fact about Play and not something the Shell can hurry along or
     * predict, so the tile says so rather than guessing at a time.
     */
    data object AwaitingCleanup : TileState

    val captionOrNull: String?
        get() = when (this) {
            is Ready -> null
            is Available -> "%.1f MB · tap to install".format(downloadMb)
            is AwaitingCleanup -> "Removed · Play frees the space later"
            is Installing -> "Installing…"
            is Gated -> reason
            is Quarantined -> reason
        }

    val accessibilityLabel: String
        get() = when (this) {
            is Ready -> "Installed"
            is Available -> "Not installed, tap to install"
            is AwaitingCleanup -> "Removed. Data deleted. Google Play frees the download later. Tap to add it back"
            is Installing -> "Installing"
            is Gated -> "Locked: $reason"
            is Quarantined -> "Unavailable: $reason"
        }
}
