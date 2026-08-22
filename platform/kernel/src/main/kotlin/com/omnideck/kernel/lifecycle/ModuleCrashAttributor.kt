package com.omnideck.kernel.lifecycle

import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.TeamRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Works out which module a crash belongs to (QA-6, architecture.md §12.6, OD-208).
 *
 * Attribution is the prerequisite for everything downstream: per-module error
 * budgets, routing an incident to `manifest.owner`, and the quarantine counter
 * meaning anything at all. Without it every crash in a super-app is "the app
 * crashed", which no team owns.
 *
 * It matches stack frames against the namespaces of *discovered* modules rather than
 * a fixed package prefix. The prefix approach that preceded it looked for
 * `com.omnideck.module.`, which no module has ever used — module ids are
 * `com.omnideck.<name>` — so it attributed every crash to the Shell.
 */
@Singleton
class ModuleCrashAttributor @Inject constructor(private val lifecycle: ModuleLifecycleManager) {

    /**
     * The module owning the topmost frame that belongs to one, or null for the Shell.
     *
     * Topmost, not deepest: a module that calls into a platform helper which throws
     * is still the module that made the call, and the helper is shared by everyone.
     * Causes are walked too, because a module's exception is routinely wrapped by a
     * coroutine or a Room transaction before it reaches the handler.
     */
    fun attribute(throwable: Throwable): ModuleId? {
        val namespaces = lifecycle.modules.value.keys
        if (namespaces.isEmpty()) return null

        var current: Throwable? = throwable
        val seen = mutableSetOf<Throwable>()

        while (current != null && seen.add(current)) {
            current.stackTrace.forEach { frame ->
                namespaces.firstOrNull { frame.className.startsWith("${it.value}.") }?.let { return it }
            }
            current = current.cause
        }
        return null
    }

    /** The team an incident should be raised against. Null until the module's manifest is known. */
    fun ownerOf(moduleId: ModuleId): TeamRef? = lifecycle.modules.value[moduleId]?.manifest?.owner

    /**
     * A stable, low-cardinality label for the crash, safe to use as a telemetry
     * attribute and as a Crashlytics custom key.
     */
    fun label(throwable: Throwable): String = attribute(throwable)?.value ?: SHELL

    private companion object {
        const val SHELL = "shell"
    }
}
