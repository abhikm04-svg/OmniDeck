package com.omnideck.shell.navigation

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.omnideck.kernel.lifecycle.ModuleRuntime
import com.omnideck.kernel.loader.ModuleDescriptor
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.DataCategory
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.IconRef
import com.omnideck.sdk.LocalizedString
import com.omnideck.sdk.ModuleCategory
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleManifest
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.Route
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.SemVerRange
import com.omnideck.sdk.TeamRef
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Launcher shortcuts (OD-314).
 *
 * Two properties are worth the test. The Shell names no module — the list is whatever
 * was discovered, which is why these are dynamic and not a `shortcuts.xml`. And only
 * modules a tap would actually reach are offered: the launcher caches shortcuts, so
 * one pointing at a quarantined or not-yet-downloaded module strands the user on an
 * error screen launched from their home screen.
 */
@RunWith(RobolectricTestRunner::class)
class ModuleShortcutsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shortcuts = ModuleShortcuts(context)

    private fun published() = ShortcutManagerCompat.getDynamicShortcuts(context)

    @Test
    fun `a usable module is offered, and points at its own entry route`() {
        shortcuts.publish(listOf(runtime("alpha", ModuleState.ACTIVE)))

        val shortcut = published().single()
        assertThat(shortcut.shortLabel.toString()).isEqualTo("Alpha")
        assertThat(shortcut.intent.data.toString()).isEqualTo("omnideck://alpha/home")
    }

    @Test
    fun `a module that would land the user on an error screen is not offered`() {
        shortcuts.publish(
            listOf(
                runtime("gated", ModuleState.GATED),
                runtime("quarantined", ModuleState.QUARANTINED),
                runtime("advertised", ModuleState.ADVERTISED),
                runtime("failed", ModuleState.FAILED),
            ),
        )

        assertThat(published()).isEmpty()
    }

    @Test
    fun `a downloaded module is offered even before it has initialised`() {
        // The tap goes through the Router, which activates it on the way.
        shortcuts.publish(listOf(runtime("alpha", ModuleState.INSTALLED)))

        assertThat(published()).hasSize(1)
    }

    @Test
    fun `a module whose manifest has never been read is skipped rather than guessed at`() {
        shortcuts.publish(listOf(runtime("alpha", ModuleState.INSTALLED, manifest = null)))

        assertThat(published()).isEmpty()
    }

    @Test
    fun `republishing drops a module that has since stopped being usable`() {
        // setDynamicShortcuts rather than addDynamicShortcuts: the launcher keeps what
        // it was given, so an added shortcut would outlive the state that justified it.
        shortcuts.publish(listOf(runtime("alpha", ModuleState.ACTIVE)))

        shortcuts.publish(listOf(runtime("alpha", ModuleState.QUARANTINED)))

        assertThat(published()).isEmpty()
    }

    @Test
    fun `the order does not depend on the order modules happened to be discovered in`() {
        shortcuts.publish(
            listOf(
                runtime("zebra", ModuleState.ACTIVE),
                runtime("alpha", ModuleState.ACTIVE),
            ),
        )

        assertThat(published().map { it.shortLabel.toString() }).containsExactly("Alpha", "Zebra").inOrder()
    }

    private fun runtime(shortId: String, state: ModuleState, manifest: ModuleManifest? = manifest(shortId)) =
        ModuleRuntime(
            descriptor = ModuleDescriptor(
                id = ModuleId("com.omnideck.$shortId"),
                entryPointClass = "com.omnideck.$shortId.ModuleEntryPoint",
                delivery = DeliveryKind.BUNDLED,
            ),
            state = state,
            manifest = manifest,
        )

    private fun manifest(shortId: String) = ModuleManifest(
        id = ModuleId("com.omnideck.$shortId"),
        version = SemVer(1, 0, 0),
        displayName = LocalizedString(shortId.replaceFirstChar(Char::titlecase)),
        summary = LocalizedString("A module"),
        category = ModuleCategory.PRODUCTIVITY,
        icon = IconRef.Symbol("widgets"),
        delivery = DeliveryKind.BUNDLED,
        sdkRange = SemVerRange(SemVer(1, 0, 0), SemVer(2, 0, 0)),
        minHostVersionCode = 1,
        entryRoute = Route("omnideck://$shortId/home"),
        requiredCapabilities = setOf(CapabilityId.TELEMETRY),
        dataCategories = setOf(DataCategory.APP_ACTIVITY),
        owner = TeamRef("platform"),
    )
}
