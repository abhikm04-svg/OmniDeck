package com.omnideck.kernel.lifecycle

import com.google.common.truth.Truth.assertThat
import com.omnideck.kernel.loader.ModuleDescriptor
import com.omnideck.kernel.registry.CapabilityRegistryImpl
import com.omnideck.sdk.CapabilityId
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
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * The compatibility gate (OD-308, architecture.md §7.1).
 *
 * It decides whether a module the user can see is a module the user can use, and the
 * two ways it can say no are not interchangeable: one is fixed by updating the app
 * and the other cannot be fixed by the user at all. A gate that reported both the
 * same way would send someone to the Play Store to solve a problem an update does
 * not touch.
 */
class CompatibilityGateTest {

    private val capabilities = mockk<CapabilityRegistryImpl> {
        every { available() } returns emptySet()
    }

    private fun gate(hostSdk: SemVer = SemVer(1, 0, 0), hostVersionCode: Int = 10) =
        CompatibilityGate(HostInfo(hostSdk, hostVersionCode), capabilities)

    @Test
    fun `a module inside the host's range and with its capabilities present passes`() {
        assertThat(gate().evaluate(manifest())).isNull()
    }

    @Test
    fun `a module needing a newer SDK than the host is told an update would help`() {
        val failure = gate(hostSdk = SemVer(1, 0, 0))
            .evaluate(manifest(sdkRange = SemVerRange(SemVer(2, 0, 0), SemVer(3, 0, 0))))

        assertThat(failure).isInstanceOf(CompatibilityFailure.HostTooOld::class.java)
        assertThat(failure!!.message).contains("newer version of OmniDeck")
    }

    @Test
    fun `minHostVersionCode gates as firmly as the SDK range does`() {
        // Two independent axes: an SDK-compatible module can still require a host
        // build newer than the one installed, and dropping this check ships a module
        // against a host missing the fix it depends on.
        val failure = gate(hostVersionCode = 5).evaluate(manifest(minHostVersionCode = 99))

        assertThat(failure).isInstanceOf(CompatibilityFailure.HostTooOld::class.java)
    }

    @Test
    fun `a missing capability is not something the user can update their way out of`() {
        val failure = gate().evaluate(manifest(required = setOf(CapabilityId("omnideck.nonexistent"))))

        assertThat(failure).isInstanceOf(CompatibilityFailure.MissingCapabilities::class.java)
        assertThat(failure!!.message).contains("omnideck.nonexistent")
    }

    @Test
    fun `kernel-provided capabilities count as available without being registered`() {
        // They are supplied by the kernel itself, so requiring registration would gate
        // every module on the platform's own services.
        assertThat(gate().evaluate(manifest(required = setOf(CapabilityId.TELEMETRY)))).isNull()
    }

    @Test
    fun `a module gated on the host version carries the flag the status screen offers an update on`() {
        val failure = CompatibilityFailure.HostTooOld(requiredSdkRange = ">=2.0.0", hostSdkVersion = "1.0.0")

        val gated = runtime().gatedBy(failure, manifest())

        assertThat(gated.state).isEqualTo(ModuleState.GATED)
        assertThat(gated.hostUpdateWouldHelp).isTrue()
        assertThat(gated.reason).isEqualTo(failure.message)
    }

    @Test
    fun `a module gated on a capability offers no update, because none would help`() {
        val failure = CompatibilityFailure.MissingCapabilities(setOf(CapabilityId("omnideck.nonexistent")))

        assertThat(runtime().gatedBy(failure, manifest()).hostUpdateWouldHelp).isFalse()
    }

    private fun runtime() = ModuleRuntime(
        descriptor = ModuleDescriptor(
            id = ModuleId("com.omnideck.alpha"),
            entryPointClass = "com.omnideck.alpha.ModuleEntryPoint",
            delivery = DeliveryKind.BUNDLED,
        ),
        state = ModuleState.INITIALIZING,
    )

    private fun manifest(
        sdkRange: SemVerRange = SemVerRange(SemVer(1, 0, 0), SemVer(2, 0, 0)),
        minHostVersionCode: Int = 1,
        required: Set<CapabilityId> = setOf(CapabilityId.TELEMETRY),
    ) = ModuleManifest(
        id = ModuleId("com.omnideck.alpha"),
        version = SemVer(1, 0, 0),
        displayName = LocalizedString("Alpha"),
        summary = LocalizedString("A module"),
        category = ModuleCategory.PRODUCTIVITY,
        icon = IconRef.Symbol("widgets"),
        delivery = DeliveryKind.BUNDLED,
        sdkRange = sdkRange,
        minHostVersionCode = minHostVersionCode,
        entryRoute = Route("omnideck://alpha/home"),
        requiredCapabilities = required,
        dataCategories = setOf(com.omnideck.sdk.DataCategory.APP_ACTIVITY),
        owner = TeamRef("platform"),
    )
}
