package com.omnideck.shell.navigation

import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.Route

/**
 * The Shell's own destinations.
 *
 * They sit in the same URI space and the same route table as every module's, under a
 * reserved short id. That is not tidiness: it means a notification, an App Link or a
 * module can link to Settings or the Privacy Centre with the same one line it uses to
 * link anywhere else, and the Shell needs no second navigation mechanism for itself
 * (architecture.md §10.1).
 */
object ShellRoutes {

    /**
     * Reserved: no feature module may own `omnideck://shell/...`, because
     * `MutableDestinationRegistry` only lets a module register under its own short id
     * and no module may take this one as its own.
     */
    val MODULE_ID = ModuleId("com.omnideck.shell")

    const val SETTINGS = "omnideck://shell/settings"
    const val PRIVACY = "omnideck://shell/privacy"

    /** Acquire and remove modules (OD-305). */
    const val CATALOG = "omnideck://shell/catalog"

    /**
     * One module's size, permissions and data disclosure (OD-305).
     *
     * A distinct pattern rather than a query parameter on [CATALOG], so it is
     * linkable on its own — a "what does this module ask for?" link in a
     * notification or a support article resolves to exactly this page.
     */
    const val CATALOG_DETAIL_PATTERN = "omnideck://shell/catalog/{moduleId}"

    /** Status and recovery for one module: quarantined, gated, failed to install. */
    const val MODULE_STATUS_PATTERN = "omnideck://shell/module/{moduleId}"

    fun settings(): Route = Route(SETTINGS)

    fun privacy(): Route = Route(PRIVACY)

    fun catalog(): Route = Route(CATALOG)

    fun catalogDetail(id: ModuleId): Route = Route("omnideck://shell/catalog/${id.value}")

    fun moduleStatus(id: ModuleId): Route = Route("omnideck://shell/module/${id.value}")
}
