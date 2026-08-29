package com.omnideck.kernel.lifecycle

import com.omnideck.kernel.registry.CapabilityRegistryImpl
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.ModuleManifest
import com.omnideck.sdk.ModuleState

/**
 * Why a module may not initialise, and whether the user can do anything about it
 * (OD-308).
 *
 * The distinction is the whole point. A module that needs a newer host has a fix —
 * update OmniDeck — and a module whose capabilities this build does not provide has
 * none the user can perform. Collapsing both into one "unavailable" string, as this
 * did while it was a private string-returning function, is how a solvable problem
 * gets presented as a dead end.
 */
sealed interface CompatibilityFailure {

    /** Shown to the user. Written to be read by one, not by a log parser. */
    val message: String

    /**
     * The host app is older than the module requires.
     *
     * Actionable: an app update resolves it, which is what makes this worth
     * distinguishing at all (OD-309 offers the update itself).
     */
    data class HostTooOld(val requiredSdkRange: String, val hostSdkVersion: String) : CompatibilityFailure {
        override val message =
            "Needs a newer version of OmniDeck (requires SDK $requiredSdkRange, host is $hostSdkVersion)."
    }

    /**
     * This build does not provide something the module declared as required.
     *
     * Not actionable by the user: no update they can perform adds a capability the
     * installed host does not implement, so nothing here offers them one.
     */
    data class MissingCapabilities(val missing: Set<CapabilityId>) : CompatibilityFailure {
        override val message = "Unavailable capabilities: ${missing.joinToString { it.value }}."
    }
}

/**
 * The gated runtime for a module that did not pass [CompatibilityGate].
 *
 * A top-level function rather than a method: it belongs to the failure type more
 * than to the state machine, and it is the one place that decides an app update is
 * worth offering.
 */
internal fun ModuleRuntime.gatedBy(failure: CompatibilityFailure, manifest: ModuleManifest): ModuleRuntime = copy(
    state = ModuleState.GATED,
    manifest = manifest,
    reason = failure.message,
    hostUpdateWouldHelp = failure is CompatibilityFailure.HostTooOld,
)

/**
 * The compatibility gate of `architecture.md` §7.1, evaluated before a module is
 * allowed to initialise.
 *
 * Extracted from [ModuleLifecycleManager] so the rule has a name and a test of its
 * own: it is the one check that decides whether a module the user can see is a
 * module the user can use, and it is cheap to get subtly wrong — an off-by-one in a
 * version range silently hides a working module, or admits a broken one.
 */
class CompatibilityGate(private val hostInfo: HostInfo, private val capabilities: CapabilityRegistryImpl) {

    /** Null when the module may proceed. */
    fun evaluate(manifest: ModuleManifest): CompatibilityFailure? {
        if (!manifest.isCompatibleWith(hostInfo.sdkVersion, hostInfo.versionCode)) {
            return CompatibilityFailure.HostTooOld(
                requiredSdkRange = manifest.sdkRange.toString(),
                hostSdkVersion = hostInfo.sdkVersion.toString(),
            )
        }
        val missing = manifest.unsatisfiedBy(CapabilityId.KERNEL_PROVIDED + capabilities.available())
        return if (missing.isEmpty()) null else CompatibilityFailure.MissingCapabilities(missing)
    }
}
