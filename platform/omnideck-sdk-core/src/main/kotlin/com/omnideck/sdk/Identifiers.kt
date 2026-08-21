package com.omnideck.sdk

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * A module's permanent identity. Reverse-DNS, and **immutable forever** — it keys
 * on-device storage, telemetry attribution, entitlements and the Play split name.
 * Changing it is equivalent to deleting a module and creating a new one.
 */
@JvmInline
@Serializable
value class ModuleId(val value: String) {
    init {
        require(PATTERN.matches(value)) {
            "ModuleId must be reverse-DNS (e.g. com.omnideck.finance), was '$value'"
        }
    }

    /**
     * Play Feature Delivery split names are restricted to letters, digits and
     * underscore, so the id is transformed rather than used verbatim.
     */
    val splitName: String get() = value.substringAfterLast('.').replace('-', '_')

    /** Short id used in routes: `com.omnideck.finance` -> `finance`. */
    val shortId: String get() = value.substringAfterLast('.')

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("""[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){2,}""")
    }
}

/** Identifies a platform or module-contributed capability (architecture.md §10.3). */
@JvmInline
@Serializable
value class CapabilityId(val value: String) {
    override fun toString(): String = value

    @Suppress("TooManyFunctions")
    companion object {
        // Kernel-provided capabilities.
        val AUTH = CapabilityId("omnideck.auth")
        val NETWORK = CapabilityId("omnideck.network")
        val STORAGE = CapabilityId("omnideck.storage")
        val SECURE_STORE = CapabilityId("omnideck.secure_store")
        val TELEMETRY = CapabilityId("omnideck.telemetry")
        val FLAGS = CapabilityId("omnideck.flags")
        val ROUTER = CapabilityId("omnideck.router")
        val EVENTS = CapabilityId("omnideck.events")
        val PERMISSIONS = CapabilityId("omnideck.permissions")
        val NOTIFICATIONS = CapabilityId("omnideck.notifications")
        val BILLING = CapabilityId("omnideck.billing")
        val WORK = CapabilityId("omnideck.work")
        val CONSENT = CapabilityId("omnideck.consent")
        val LOCALE = CapabilityId("omnideck.locale")
        val MEDIA = CapabilityId("omnideck.media")
        val BIOMETRIC = CapabilityId("omnideck.biometric")

        /** Every capability the kernel itself guarantees. */
        val KERNEL_PROVIDED: Set<CapabilityId> = setOf(
            AUTH, NETWORK, STORAGE, SECURE_STORE, TELEMETRY, FLAGS, ROUTER, EVENTS,
            PERMISSIONS, NOTIFICATIONS, BILLING, WORK, CONSENT, LOCALE, MEDIA,
        )
    }
}

/** Owning team — drives crash routing, alerting, CODEOWNERS and error budgets. */
@JvmInline
@Serializable
value class TeamRef(val value: String) {
    override fun toString(): String = value
}

/** A Play Billing product id, or an enterprise licence sku. */
@JvmInline
@Serializable
value class Sku(val value: String) {
    override fun toString(): String = value
}

/** Correlates a navigation request with its result across process death (§10.2). */
@JvmInline
@Serializable
value class CorrelationId(val value: String) {
    override fun toString(): String = value
}
