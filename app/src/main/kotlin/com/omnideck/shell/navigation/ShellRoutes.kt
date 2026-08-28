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

    /** Acquire and remove modules (OD-303). */
    const val CATALOG = "omnideck://shell/catalog"

    /** Status and recovery for one module: quarantined, gated, failed to install. */
    const val MODULE_STATUS_PATTERN = "omnideck://shell/module/{moduleId}"

    fun settings(): Route = Route(SETTINGS)

    fun privacy(): Route = Route(PRIVACY)

    fun catalog(): Route = Route(CATALOG)

    fun moduleStatus(id: ModuleId): Route = Route("omnideck://shell/module/${id.value}")
}
