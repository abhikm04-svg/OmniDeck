package com.omnideck.shell.navigation

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.omnideck.kernel.lifecycle.ModuleRuntime
import com.omnideck.sdk.ModuleState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Long-press shortcuts on the launcher icon (OD-314).
 *
 * **Dynamic, never a `shortcuts.xml`.** A static shortcut resource would have to name
 * modules — their ids, labels and routes — in a file the Shell owns, which is exactly
 * the coupling goal G1 forbids and `ShellIsolationFitnessTest` fails the build over.
 * These are published at runtime from whatever the lifecycle manager discovered, so a
 * module added tomorrow gets a shortcut with no edit here.
 *
 * Only usable modules are offered. A shortcut into a module that is quarantined, gated
 * or not yet downloaded lands the user on an error screen from their home screen,
 * which is a worse first impression than having no shortcut at all — and the launcher
 * caches these, so a stale one outlives the state that produced it.
 */
@Singleton
class ModuleShortcuts @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun publish(runtimes: Collection<ModuleRuntime>) {
        val shortcuts = runtimes
            .filter { it.state.worthAShortcut && it.manifest != null }
            // Stable ordering so the launcher's list does not reshuffle between
            // launches for reasons the user cannot see.
            .sortedBy { it.manifest?.displayName?.default?.lowercase().orEmpty() }
            .take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(MAX_SHORTCUTS))
            .mapNotNull(::toShortcut)

        // Replaces rather than adds: a module that was removed or quarantined since
        // the last publish must lose its shortcut, and addDynamicShortcuts would
        // leave it there.
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private fun toShortcut(runtime: ModuleRuntime): ShortcutInfoCompat? {
        val manifest = runtime.manifest ?: return null
        val label = manifest.displayName.default

        // The same URI a notification or an App Link would carry, so every external
        // door into a module converges on ExternalRoutes and then the Router.
        val intent = Intent(Intent.ACTION_VIEW, manifest.entryRoute.uri.toUri())
            .setPackage(context.packageName)

        return ShortcutInfoCompat.Builder(context, "module.${manifest.id.value}")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_menu_view))
            .setIntent(intent)
            .build()
    }

    private companion object {
        /**
         * Launchers commonly show four or five; past that the list is truncated by
         * whoever is displaying it, so publishing more just wastes the quota that a
         * future pinned shortcut would want.
         */
        const val MAX_SHORTCUTS = 4
    }
}

/**
 * Deliberately not [ModuleState.isUsable], which is narrower.
 *
 * A downloaded-but-not-yet-initialised module is a fine shortcut target: the tap goes
 * through the Router, which activates it on the way. What must not be offered is a
 * module the user would arrive at an error screen from.
 */
private val ModuleState.worthAShortcut: Boolean
    get() = this == ModuleState.ACTIVE || this == ModuleState.DEGRADED || this == ModuleState.INSTALLED
