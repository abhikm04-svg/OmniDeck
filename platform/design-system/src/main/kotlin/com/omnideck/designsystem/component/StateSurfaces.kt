package com.omnideck.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.omnideck.designsystem.theme.Spacing

/**
 * Loading / empty / error surfaces.
 *
 * Shipped in the design system rather than left to each module because "did you
 * handle the error state?" is the most common review comment on any feature, and the
 * cheapest way to make the answer yes is to make the correct thing the easy thing.
 * The Definition of Done for a story requires all three.
 */
@Composable
fun LoadingSurface(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
    }
}

@Composable
fun EmptySurface(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = Spacing.lg)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun ErrorSurface(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onSecondary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        onRetry?.let {
            Button(onClick = it, modifier = Modifier.padding(top = Spacing.lg)) { Text("Try again") }
        }
        if (onSecondary != null && secondaryLabel != null) {
            TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}
