package com.omnideck.finance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.omnideck.designsystem.component.EmptySurface
import com.omnideck.designsystem.component.PrimaryButton
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.finance.FinanceComponent
import com.omnideck.finance.data.CategoryTotal

@Composable
fun InsightsRoute(component: FinanceComponent) {
    val viewModel: InsightsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                InsightsViewModel(
                    repository = component.repository,
                    entitlements = component.entitlements,
                    telemetry = component.telemetry,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    InsightsScreen(
        state = state,
        onPurchase = { viewModel.purchase() },
        onMessageShown = viewModel::dismissMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    state: InsightsState,
    onPurchase: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbars.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Insights") }) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.md)) {
            if (!state.entitled) {
                Paywall(price = state.price, inFlight = state.purchaseInFlight, onPurchase = onPurchase)
                return@Column
            }
            if (state.breakdown.isEmpty()) {
                EmptySurface(
                    title = "Nothing to break down",
                    message = "Record some spending and the split by category appears here.",
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }
            state.breakdown.forEach { CategoryRow(it) }
        }
    }
}

/**
 * The gate.
 *
 * It shows what the feature is before asking for money — a paywall that will not say
 * what is behind it is why people stop tapping upgrade buttons.
 */
@Composable
private fun Paywall(price: String?, inFlight: Boolean, onPurchase: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.lg)) {
        Text("See where it goes", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Finance Pro breaks your spending down by category, so you can see " +
                "what actually adds up. Everything you have already recorded is included.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        PrimaryButton(
            text = price?.let { "Upgrade — $it" } ?: "Upgrade",
            onClick = onPurchase,
            // Guards the double tap: Play's sheet takes a moment to appear, and a
            // second purchase call in that window is a second charge to explain.
            enabled = !inFlight,
            modifier = Modifier.padding(top = Spacing.md),
        )
        if (price == null) {
            Text(
                text = "Pricing is unavailable right now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun CategoryRow(total: CategoryTotal) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(total.category.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(total.minorUnits.asMoney(), style = MaterialTheme.typography.bodyLarge)
        }
        LinearProgressIndicator(
            progress = { total.share },
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
        )
    }
}
