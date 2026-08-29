package com.omnideck.finance

import com.omnideck.finance.ui.FinanceHomeRoute
import com.omnideck.finance.ui.InsightsRoute
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.DataCategory
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.DestinationRegistry
import com.omnideck.sdk.IconRef
import com.omnideck.sdk.LocalizedString
import com.omnideck.sdk.ModuleCategory
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleInitResult
import com.omnideck.sdk.ModuleManifest
import com.omnideck.sdk.OmniModule
import com.omnideck.sdk.PlatformServices
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.Route
import com.omnideck.sdk.RoutePattern
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.SemVerRange
import com.omnideck.sdk.SuspendReason
import com.omnideck.sdk.TeamRef

/**
 * Finance — the second OmniDeck module (OD-311).
 *
 * Its job in the plan is to be the *second* one: a contract validated by a single
 * implementation is a contract shaped around that implementation. So it deliberately
 * takes a different path through the SDK than Notes does — a preferences DataStore
 * instead of Room, billing instead of sync, an optional capability whose absence
 * degrades one screen rather than the module.
 *
 * It was added with `./gradlew newModule -Pid=finance` and grew from there. Nothing
 * under `app/` or `platform/kernel` was touched to make it appear, which is the
 * claim OD-320 checks against this diff — and the first honest test of it, because
 * Notes landed in the same commit as the Shell that hosts it.
 */
class ModuleEntryPoint : OmniModule {

    // Not `lateinit`: initialize() may be retried after a failure, and a half-built
    // component from the previous attempt must not be reachable in between.
    @Volatile
    private var component: FinanceComponent? = null

    override val manifest = ModuleManifest(
        id = ModuleId("com.omnideck.finance"),
        version = SemVer(0, 1, 0),
        displayName = LocalizedString(default = "Finance"),
        summary = LocalizedString(default = "Track what you spend. Breakdowns with Finance Pro."),
        category = ModuleCategory.FINANCE,
        icon = IconRef.Symbol("account_balance_wallet"),
        delivery = DeliveryKind.BUNDLED,
        sdkRange = SemVerRange.parse(">=1.0.0 <2.0.0"),
        minHostVersionCode = 1,
        entryRoute = Route("omnideck://finance/home"),
        // Linkable from a notification or the Catalog's detail page without the
        // module having been loaded in this process yet.
        deepLinks = listOf(RoutePattern("omnideck://finance/insights")),
        requiredCapabilities = setOf(CapabilityId.STORAGE, CapabilityId.TELEMETRY, CapabilityId.ROUTER),
        // Optional on purpose: a build with no billing behind it still records
        // spending. It loses the paid breakdown, which is a feature, not the module.
        optionalCapabilities = setOf(CapabilityId.BILLING),
        // The module asks for no runtime permissions at all. Stated rather than
        // omitted: the Catalog's disclosure distinguishes "asks for nothing" from
        // "nobody has looked", and this is the former.
        androidPermissions = emptySet(),
        // FINANCIAL_INFO is not optional here. The Play Data Safety declaration is
        // generated from this set, so leaving it off would make the store listing a
        // false statement about an app that stores what a person spends.
        dataCategories = setOf(DataCategory.FINANCIAL_INFO, DataCategory.APP_ACTIVITY),
        estimatedDownloadBytes = ESTIMATED_DOWNLOAD_BYTES,
        supportsOffline = true,
        owner = TeamRef("finance-squad"),
    )

    /**
     * Cheap by contract: the DataStore is opened lazily on first read, and nothing
     * here touches the network or the disk, so the 500 ms budget (architecture.md
     * §16) is met with room to spare.
     */
    override suspend fun initialize(services: PlatformServices): ModuleInitResult = try {
        val built = FinanceComponent.build(services)
        component = built
        built.telemetry.event("finance_initialized")
        ModuleInitResult.Ready
    } catch (e: IllegalStateException) {
        // Storage unavailable (no space, an unreadable store). Retryable: the Shell
        // tries again, and repeated failures quarantine the module rather than
        // leaving it half-alive.
        ModuleInitResult.Failed(e, retryable = true)
    }

    override fun registerDestinations(registry: DestinationRegistry) {
        registry.destination("omnideck://finance/home") {
            FinanceHomeRoute(requireComponent())
        }
        registry.destination("omnideck://finance/insights") {
            InsightsRoute(requireComponent())
        }
    }

    override suspend fun suspend(reason: SuspendReason) {
        // Nothing running in the background to stop: no sync engine, no scheduled
        // work. Stated rather than left to the reader of an empty override.
    }

    override suspend fun purge(scope: PurgeScope) {
        component?.repository?.wipe(scope)
        if (scope == PurgeScope.ALL) component = null
    }

    private fun requireComponent(): FinanceComponent = checkNotNull(component) {
        "Finance destinations were rendered before initialize() completed. " +
            "The Shell activates a module before routing to it, so this is a platform bug."
    }

    private companion object {
        const val ESTIMATED_DOWNLOAD_BYTES = 900_000L
    }
}
