package com.omnideck.finance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.omnideck.designsystem.component.EmptySurface
import com.omnideck.designsystem.component.LoadingSurface
import com.omnideck.designsystem.component.OmniTextField
import com.omnideck.designsystem.component.PrimaryButton
import com.omnideck.designsystem.component.SecondaryButton
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.finance.FinanceComponent
import com.omnideck.finance.data.Spend
import com.omnideck.finance.data.SpendCategory

/**
 * Destination wrapper.
 *
 * The module owns its own ViewModel construction because it has no Hilt (ADR-002) —
 * [FinanceComponent] is the graph, and `viewModelFactory` is the only glue needed.
 */
@Composable
fun FinanceHomeRoute(component: FinanceComponent) {
    val viewModel: FinanceHomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                FinanceHomeViewModel(
                    repository = component.repository,
                    router = component.router,
                    telemetry = component.telemetry,
                    clock = component.clock,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    FinanceHomeScreen(
        state = state,
        onAdd = viewModel::add,
        onRemove = viewModel::remove,
        onInsights = { viewModel.openInsights() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceHomeScreen(
    state: FinanceHomeState,
    onAdd: (String, Long, SpendCategory) -> Unit,
    onRemove: (String) -> Unit,
    onInsights: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Finance") }) },
    ) { padding ->
        if (!state.loaded) {
            LoadingSurface(label = "Loading your spending…", modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.md)) {
            TotalHeader(state.totalMinorUnits, onInsights)
            AddSpendForm(onAdd)
            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
            SpendList(state.spends, onRemove)
        }
    }
}

@Composable
private fun TotalHeader(totalMinorUnits: Long, onInsights: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Total recorded", style = MaterialTheme.typography.labelMedium)
            Text(totalMinorUnits.asMoney(), style = MaterialTheme.typography.headlineSmall)
        }
        SecondaryButton(text = "Insights", onClick = onInsights)
    }
}

@Composable
private fun AddSpendForm(onAdd: (String, Long, SpendCategory) -> Unit) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(SpendCategory.OTHER) }

    // Parsed as minor units from the start. Taking pounds as a Double and
    // multiplying by 100 is how 19.99 becomes 1998.
    val minorUnits = amount.toMinorUnitsOrNull()

    Column(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        OmniTextField(
            value = description,
            onValueChange = { description = it },
            label = "What was it?",
            modifier = Modifier.fillMaxWidth(),
        )
        OmniTextField(
            value = amount,
            onValueChange = { amount = it },
            label = "Amount",
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            errorText = "Enter an amount like 12.50".takeIf { amount.isNotBlank() && minorUnits == null },
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        )
        CategoryPicker(selected = category, onSelect = { category = it })
        PrimaryButton(
            text = "Add",
            onClick = {
                onAdd(description, minorUnits ?: 0L, category)
                description = ""
                amount = ""
            },
            enabled = description.isNotBlank() && minorUnits != null,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

@Composable
private fun CategoryPicker(selected: SpendCategory, onSelect: (SpendCategory) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        SpendCategory.entries.forEach { category ->
            androidx.compose.material3.FilterChip(
                selected = category == selected,
                onClick = { onSelect(category) },
                label = { Text(category.label) },
            )
        }
    }
}

@Composable
private fun SpendList(spends: List<Spend>, onRemove: (String) -> Unit) {
    if (spends.isEmpty()) {
        EmptySurface(
            title = "Nothing recorded yet",
            message = "Add what you spend and it will show up here. It stays on this device.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = Spacing.md)) {
        items(spends, key = Spend::id) { spend ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(spend.description, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = spend.category.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(spend.minorUnits.asMoney(), style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = { onRemove(spend.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove ${spend.description}")
                }
            }
        }
    }
}

/**
 * Minor units to a displayable amount.
 *
 * No currency symbol: the module has no idea which currency the user thinks in, and
 * a hardcoded one is wrong for most of the world. Locale-aware formatting arrives
 * with the LocaleService work in Phase 6.
 */
internal fun Long.asMoney(): String = "%d.%02d".format(this / MINOR_UNITS, this % MINOR_UNITS)

/**
 * `12.50` -> `1250`. Null for anything that is not an amount.
 *
 * Parsed straight to minor units rather than to a `Double` that is then multiplied
 * by 100, which is how 19.99 becomes 1998. More than two decimal places is rejected
 * rather than rounded: silently dropping a digit off someone's amount is worse than
 * asking them to retype it.
 */
internal fun String.toMinorUnitsOrNull(): Long? {
    val parts = trim().split('.', ',')
    if (parts.size > 2) return null
    val whole = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val fraction = if (parts.size == 1) {
        0L
    } else {
        parts[1].padEnd(2, '0').takeIf { it.length == 2 }?.toLongOrNull()
    }
    return fraction?.let { whole * MINOR_UNITS + it }
}

private const val MINOR_UNITS = 100L
