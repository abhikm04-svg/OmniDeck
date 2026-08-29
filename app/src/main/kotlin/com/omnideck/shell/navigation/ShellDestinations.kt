package com.omnideck.shell.navigation

import com.omnideck.kernel.router.MutableDestinationRegistry
import com.omnideck.sdk.ModuleId
import com.omnideck.shell.catalog.CatalogDetailRoute
import com.omnideck.shell.catalog.CatalogRoute
import com.omnideck.shell.privacy.PrivacyCentreRoute
import com.omnideck.shell.settings.SettingsRoute
import com.omnideck.shell.status.ModuleStatusRoute
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers the Shell's own screens into the shared route table (OD-207, OD-208).
 *
 * The Shell registers through exactly the same `DestinationRegistry` a module uses,
 * under the reserved `shell` short id. Two things follow from that, both deliberate:
 * Settings and the Privacy Centre are linkable from a module or a notification like
 * anything else, and the Shell is held to the same ownership rule as every module —
 * the registry rejects a pattern outside its own host regardless of who is asking.
 */
@Singleton
class ShellDestinations @Inject constructor() {

    // The registry rejects a duplicate pattern outright, and a ViewModel is rebuilt
    // whenever its store owner is, so registration has to be once per process.
    private val registered = AtomicBoolean(false)

    fun registerInto(registry: MutableDestinationRegistry) {
        if (!registered.compareAndSet(false, true)) return

        val shell = registry.scopedTo(ShellRoutes.MODULE_ID)
        shell.destination(ShellRoutes.SETTINGS) { SettingsRoute() }
        shell.destination(ShellRoutes.PRIVACY) { PrivacyCentreRoute() }
        shell.destination(ShellRoutes.CATALOG) { CatalogRoute() }
        shell.destination(ShellRoutes.CATALOG_DETAIL_PATTERN) { args ->
            CatalogDetailRoute(ModuleId(args.string("moduleId")))
        }
        shell.destination(ShellRoutes.MODULE_STATUS_PATTERN) { args ->
            ModuleStatusRoute(ModuleId(args.string("moduleId")))
        }
    }
}
