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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
            .clearAndSetSemantics { contentDescription = "$title. $subtitle. ${state.accessibilityLabel}" },
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                is TileState.Quarantined -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        is TileState.Installing -> CircularProgressIndicator(Modifier.size(24.dp))
                        is TileState.Available -> Icon(Icons.Default.Download, contentDescription = null)
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

/** UI projection of `ModuleState` — deliberately smaller than the full state machine. */
sealed interface TileState {
    data object Ready : TileState
    data class Available(val downloadMb: Double) : TileState
    data class Installing(val fraction: Float?) : TileState
    data class Gated(val reason: String) : TileState
    data class Quarantined(val reason: String) : TileState

    val captionOrNull: String?
        get() = when (this) {
            is Ready -> null
            is Available -> "%.1f MB · tap to install".format(downloadMb)
            is Installing -> "Installing…"
            is Gated -> reason
            is Quarantined -> reason
        }

    val accessibilityLabel: String
        get() = when (this) {
            is Ready -> "Installed"
            is Available -> "Not installed, tap to install"
            is Installing -> "Installing"
            is Gated -> "Locked: $reason"
            is Quarantined -> "Unavailable: $reason"
        }
}
