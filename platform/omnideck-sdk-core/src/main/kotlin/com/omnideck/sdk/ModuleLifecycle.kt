package com.omnideck.sdk

import kotlinx.serialization.Serializable

/** Outcome of [com.omnideck.sdk.OmniModule.initialize]. */
sealed interface ModuleInitResult {
    /** Fully functional. */
    data object Ready : ModuleInitResult

    /**
     * Usable, but something is missing (an optional capability, a stale cache, no
     * network on first run). The Shell renders the module with a advisory banner
     * rather than blocking the user.
     */
    data class Degraded(val reason: String) : ModuleInitResult

    /**
     * Unusable. [retryable] distinguishes a transient failure (retry with backoff)
     * from a permanent one (quarantine after the threshold — architecture.md §7.1).
     */
    data class Failed(val error: Throwable, val retryable: Boolean = true) : ModuleInitResult
}

/** Why the Shell is suspending a module. */
enum class SuspendReason {
    BACKGROUNDED,
    MEMORY_PRESSURE,
    SESSION_ENDED,
    ENTITLEMENT_REVOKED,
    KILL_SWITCH,
    HOST_SHUTDOWN,
}

/**
 * How much of a module's data must be destroyed.
 *
 * Because storage is structurally isolated per module (ADR-005), "erase everything
 * this module knows about the user" is a deterministic, testable operation rather
 * than an archaeology exercise — which is what makes GDPR/DPDP erasure tractable.
 */
enum class PurgeScope {
    /** Caches only. Triggered by low storage. */
    CACHE,

    /** Everything tied to the current session. Triggered by sign-out. */
    SESSION,

    /** Everything. Triggered by module removal or account deletion. */
    ALL,
}

/** Runtime state of a module, mirrored from the state machine in architecture.md §7.1. */
@Serializable
enum class ModuleState {
    ADVERTISED,
    GATED,
    INSTALLING,
    INSTALLED,
    INITIALIZING,
    ACTIVE,
    DEGRADED,
    SUSPENDED,
    QUARANTINED,
    PURGING,
    FAILED,
    ;

    val isUsable: Boolean get() = this == ACTIVE || this == DEGRADED
    val isPresent: Boolean get() = ordinal >= INSTALLED.ordinal && this != PURGING
}

/** Progress of an acquisition, surfaced directly in the Catalog UI. */
sealed interface InstallProgress {
    data object Pending : InstallProgress
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : InstallProgress {
        val fraction: Float get() = if (totalBytes <= 0) 0f else bytesDownloaded.toFloat() / totalBytes
    }

    /**
     * Play requires explicit user confirmation for large or metered downloads.
     * Failing to surface this is the most common cause of "stuck at 0%" reports
     * (implementation_plan.md OD-302).
     */
    data object RequiresUserConfirmation : InstallProgress
    data object Installing : InstallProgress
    data object Installed : InstallProgress
    data object Canceled : InstallProgress
    data class Failed(val code: Int, val message: String, val retryable: Boolean) : InstallProgress
}
