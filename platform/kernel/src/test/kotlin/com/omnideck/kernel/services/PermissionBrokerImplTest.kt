package com.omnideck.kernel.services

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.capability.PermissionBroker
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The elevation control from architecture.md §12.1: a module may only ever request a
 * permission it declared in its manifest. Everything else is refused and audited.
 *
 * Both halves matter. Enforcement without the audit trail means a denied escalation
 * attempt leaves no trace, and the audit is what makes "which module asked for the
 * camera, and why" answerable after the fact.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionBrokerImplTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val notes = ModuleId("com.omnideck.notes")

    private data class Audit(
        val permission: String,
        val result: PermissionBroker.PermissionResult,
        val purpose: String,
    )

    private val audits = mutableListOf<Audit>()

    private fun broker(
        declared: Set<String>,
        requestResult: PermissionBroker.PermissionResult = PermissionBroker.PermissionResult.GRANTED,
    ) = PermissionBrokerImpl(
        context = context,
        moduleId = notes,
        declared = declared,
        requester = { _, _ -> requestResult },
        onAudit = { permission, result, purpose -> audits += Audit(permission, result, purpose) },
    )

    private fun rationale(purpose: String = "scanning") = PermissionBroker.Rationale(
        title = "Camera",
        message = "Needed to scan documents",
        purpose = purpose,
    )

    private fun grant(permission: String) {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(permission)
    }

    // -- declaration enforcement -------------------------------------------

    @Test
    fun `an undeclared permission is refused without ever prompting`() = runTest {
        var prompted = false
        val broker = PermissionBrokerImpl(
            context = context,
            moduleId = notes,
            declared = emptySet(),
            requester = { _, _ ->
                prompted = true
                PermissionBroker.PermissionResult.GRANTED
            },
            onAudit = { p, r, purpose -> audits += Audit(p, r, purpose) },
        )

        val result = broker.ensure(Manifest.permission.CAMERA, rationale())

        assertThat(result).isEqualTo(PermissionBroker.PermissionResult.NOT_DECLARED)
        // The user is never shown a dialog for something the module never declared.
        assertThat(prompted).isFalse()
    }

    @Test
    fun `a refused escalation attempt is audited`() = runTest {
        broker(declared = emptySet()).ensure(Manifest.permission.CAMERA, rationale("exfiltrate"))

        assertThat(audits).containsExactly(
            Audit(Manifest.permission.CAMERA, PermissionBroker.PermissionResult.NOT_DECLARED, "exfiltrate"),
        )
    }

    @Test
    fun `a declared permission is requested and its outcome returned`() = runTest {
        val broker = broker(
            declared = setOf(Manifest.permission.CAMERA),
            requestResult = PermissionBroker.PermissionResult.DENIED,
        )

        val result = broker.ensure(Manifest.permission.CAMERA, rationale())

        assertThat(result).isEqualTo(PermissionBroker.PermissionResult.DENIED)
    }

    @Test
    fun `a permanent denial is passed through rather than retried`() = runTest {
        val broker = broker(
            declared = setOf(Manifest.permission.CAMERA),
            requestResult = PermissionBroker.PermissionResult.PERMANENTLY_DENIED,
        )

        assertThat(broker.ensure(Manifest.permission.CAMERA, rationale()))
            .isEqualTo(PermissionBroker.PermissionResult.PERMANENTLY_DENIED)
    }

    // -- already-granted short circuit --------------------------------------

    @Test
    fun `an already-granted permission returns without prompting again`() = runTest {
        grant(Manifest.permission.CAMERA)
        var prompted = false
        val broker = PermissionBrokerImpl(
            context = context,
            moduleId = notes,
            declared = setOf(Manifest.permission.CAMERA),
            requester = { _, _ ->
                prompted = true
                PermissionBroker.PermissionResult.GRANTED
            },
            onAudit = { p, r, purpose -> audits += Audit(p, r, purpose) },
        )

        val result = broker.ensure(Manifest.permission.CAMERA, rationale())

        assertThat(result).isEqualTo(PermissionBroker.PermissionResult.GRANTED)
        assertThat(prompted).isFalse()
    }

    @Test
    fun `isGranted reflects the platform state`() {
        val broker = broker(declared = setOf(Manifest.permission.CAMERA))
        assertThat(broker.isGranted(Manifest.permission.CAMERA)).isFalse()

        grant(Manifest.permission.CAMERA)

        assertThat(broker.isGranted(Manifest.permission.CAMERA)).isTrue()
    }

    // -- audit --------------------------------------------------------------

    @Test
    fun `a granted request is audited with its purpose`() = runTest {
        broker(declared = setOf(Manifest.permission.CAMERA))
            .ensure(Manifest.permission.CAMERA, rationale("document scanning"))

        assertThat(audits.single().purpose).isEqualTo("document scanning")
        assertThat(audits.single().result).isEqualTo(PermissionBroker.PermissionResult.GRANTED)
    }

    @Test
    fun `each declared permission is audited separately`() = runTest {
        val broker = broker(
            declared = setOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
        )

        broker.ensure(Manifest.permission.CAMERA, rationale())
        broker.ensure(Manifest.permission.RECORD_AUDIO, rationale())

        assertThat(audits.map { it.permission })
            .containsExactly(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    // -- settings deep link -------------------------------------------------

    @Test
    fun `openSettings launches the app's own details page`() {
        // The only recovery from a permanent denial, so it must target this package
        // rather than the generic settings root.
        broker(declared = emptySet()).openSettings()

        val launched = shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).nextStartedActivity

        assertThat(launched.action).isEqualTo(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(launched.data.toString()).contains(context.packageName)
    }
}
