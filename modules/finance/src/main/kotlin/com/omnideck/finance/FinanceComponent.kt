package com.omnideck.finance

import com.omnideck.core.Clock
import com.omnideck.core.SystemClock
import com.omnideck.finance.data.PreferencesSpendStore
import com.omnideck.finance.data.SpendRepository
import com.omnideck.finance.entitlement.FinanceEntitlements
import com.omnideck.sdk.PlatformServices
import com.omnideck.sdk.capability.Router
import com.omnideck.sdk.capability.TelemetryService

/**
 * Manual composition root (architecture.md §6.4).
 *
 * A module builds its own object graph from [PlatformServices] and nothing else. No
 * Hilt, no service locator, no `Context` smuggled out of the Shell — which is what
 * keeps the module buildable and testable against `:platform:testing` with no Shell
 * and no kernel present at all.
 */
class FinanceComponent(
    val repository: SpendRepository,
    val entitlements: FinanceEntitlements,
    val telemetry: TelemetryService,
    val router: Router,
    val clock: Clock,
) {
    companion object {
        fun build(services: PlatformServices, clock: Clock = SystemClock) = FinanceComponent(
            // "spends" rather than "settings": the file name is namespaced under this
            // module either way, but a store called settings that holds a user's
            // financial records is a trap for whoever purges it next.
            repository = SpendRepository(PreferencesSpendStore(services.storage.preferences("spends"))),
            entitlements = FinanceEntitlements(services.billing),
            telemetry = services.telemetry,
            router = services.router,
            clock = clock,
        )
    }
}
