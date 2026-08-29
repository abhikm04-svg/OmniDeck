package com.omnideck.finance.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnideck.core.Clock
import com.omnideck.finance.data.Spend
import com.omnideck.finance.data.SpendCategory
import com.omnideck.finance.data.SpendRepository
import com.omnideck.sdk.Route
import com.omnideck.sdk.capability.Router
import com.omnideck.sdk.capability.TelemetryService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FinanceHomeState(
    val spends: List<Spend> = emptyList(),
    val totalMinorUnits: Long = 0,
    val loaded: Boolean = false,
)

class FinanceHomeViewModel(
    private val repository: SpendRepository,
    private val router: Router,
    private val telemetry: TelemetryService,
    private val clock: Clock,
) : ViewModel() {

    val state: StateFlow<FinanceHomeState> = repository.spends
        .map { spends ->
            FinanceHomeState(
                spends = spends,
                totalMinorUnits = spends.sumOf(Spend::minorUnits),
                loaded = true,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), FinanceHomeState())

    fun add(description: String, amountMinorUnits: Long, category: SpendCategory) {
        if (description.isBlank() || amountMinorUnits <= 0) return
        viewModelScope.launch {
            repository.add(
                Spend(
                    id = "${clock.nowMillis()}-${description.hashCode()}",
                    description = description.trim(),
                    minorUnits = amountMinorUnits,
                    category = category,
                    recordedAtMs = clock.nowMillis(),
                ),
            )
            // No amount and no description in telemetry — this is a module that
            // handles financial records, and the event exists to count usage, not to
            // reconstruct someone's spending on a dashboard (architecture.md §12.5).
            telemetry.event("finance_spend_added", mapOf("category" to category.name))
        }
    }

    fun remove(id: String) = viewModelScope.launch { repository.remove(id) }

    fun openInsights() = viewModelScope.launch {
        // Through the Router rather than a local navigation call: the same URI works
        // from a notification, the Catalog or another module, and this way there is
        // only one path to test.
        router.navigate(Route("omnideck://finance/insights"))
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}
