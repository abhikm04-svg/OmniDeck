package com.omnideck.kernel.loader

import com.google.android.play.core.splitinstall.model.SplitInstallErrorCode
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.InstallProgress
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.OmniModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Play Feature Delivery — the primary delivery channel (ADR-001).
 *
 * Talks to Play through [SplitInstaller] rather than `SplitInstallManager` directly,
 * so the decisions below — which statuses are terminal, which failures are worth
 * retrying, what a user is told — are testable without a Play-connected device.
 *
 * Two production details that are easy to miss and expensive to find late:
 *
 *  1. `SplitCompat.install()` must already have run in `Application.attachBaseContext`
 *     (see OmniDeckApplication), or freshly installed split code is unreachable until
 *     the process restarts — users see a spinner complete and then nothing happens.
 *     That is why [classLoader] is a lambda: it must be re-read *after* an install,
 *     never captured at construction.
 *  2. `REQUIRES_USER_CONFIRMATION` must reach the UI, or large and metered downloads
 *     stall silently at 0% (OD-302).
 */
class FeatureSplitProvider(
    private val installer: SplitInstaller,
    private val io: CoroutineDispatcher,
    /** Re-read per load: SplitCompat swaps it in after an install. */
    private val classLoader: () -> ClassLoader,
) : ModuleProvider {

    override val handles = DeliveryKind.FEATURE_SPLIT

    override fun isInstalled(id: ModuleId): Boolean = id.splitName in installer.installedSplits()

    override fun install(id: ModuleId): Flow<InstallProgress> {
        // Already present: report success without troubling Play for a session.
        if (isInstalled(id)) return flowOf(InstallProgress.Installed)

        return flow {
            installer.install(id.splitName).collect { update ->
                toProgress(update)?.let { emit(it) }
            }
        }
    }

    override suspend fun uninstall(id: ModuleId) {
        installer.deferredUninstall(id.splitName)
    }

    override suspend fun load(descriptor: ModuleDescriptor): OmniModule = withContext(io) {
        if (!isInstalled(descriptor.id)) {
            throw ModuleLoadException(descriptor.id, "split '${descriptor.id.splitName}' is not installed")
        }
        try {
            val klass = classLoader().loadClass(descriptor.entryPointClass)
            klass.getDeclaredConstructor().newInstance() as OmniModule
        } catch (e: ClassNotFoundException) {
            // Almost always one of: missing R8 keep rule, or SplitCompat not installed.
            throw ModuleLoadException(
                descriptor.id,
                "entry point ${descriptor.entryPointClass} not found after split install. " +
                    "Check the generated keep rule and that SplitCompat.install() ran.",
                e,
            )
        } catch (e: ReflectiveOperationException) {
            throw ModuleLoadException(descriptor.id, "entry point could not be instantiated", e)
        } catch (e: ClassCastException) {
            throw ModuleLoadException(
                descriptor.id,
                "${descriptor.entryPointClass} does not implement OmniModule. " +
                    "Check that the module depends on :platform:omnideck-sdk.",
                e,
            )
        }
    }

    /** Returns null for updates the UI has nothing to say about. */
    private fun toProgress(update: SplitSessionUpdate): InstallProgress? = when (update.status) {
        SplitStatus.PENDING -> InstallProgress.Pending
        SplitStatus.DOWNLOADING -> InstallProgress.Downloading(update.bytesDownloaded, update.totalBytes)
        SplitStatus.REQUIRES_USER_CONFIRMATION -> InstallProgress.RequiresUserConfirmation
        SplitStatus.INSTALLING -> InstallProgress.Installing
        SplitStatus.INSTALLED -> InstallProgress.Installed
        SplitStatus.CANCELED -> InstallProgress.Canceled
        SplitStatus.FAILED -> InstallProgress.Failed(
            code = update.errorCode,
            message = describe(update.errorCode),
            retryable = isRetryable(update.errorCode),
        )

        SplitStatus.UNKNOWN -> null
    }

    /**
     * User-facing text. Deliberately says what to do next where there is something to
     * do, and avoids blaming the user where there is not.
     */
    private fun describe(code: Int): String = when (code) {
        SplitInstallErrorCode.NETWORK_ERROR -> "No connection. Check your network and try again."
        SplitInstallErrorCode.INSUFFICIENT_STORAGE -> "Not enough free space to install this module."
        SplitInstallErrorCode.API_NOT_AVAILABLE -> "Google Play is unavailable on this device."
        SplitInstallErrorCode.MODULE_UNAVAILABLE -> "This module is not available for your device or account."
        SplitInstallErrorCode.INVALID_REQUEST -> "Invalid install request."
        SplitInstallErrorCode.SESSION_NOT_FOUND -> "Install session expired."
        SplitInstallErrorCode.ACCESS_DENIED -> "Install blocked while the app is in the background."
        SplitInstallErrorCode.INCOMPATIBLE_WITH_EXISTING_SESSION -> "Another install is already running."
        SplitInstallErrorCode.APP_NOT_OWNED -> "This app was not installed from Google Play."
        SplitInstallErrorCode.PLAY_STORE_NOT_FOUND -> "The Google Play Store app is not installed."
        else -> "Install failed (code $code)."
    }

    /**
     * Retryable means "the same request could plausibly succeed later unchanged".
     * Insufficient storage is not retryable: nothing changes until the user frees
     * space, so silently retrying would burn battery and data to fail identically.
     */
    private fun isRetryable(code: Int): Boolean = when (code) {
        SplitInstallErrorCode.NETWORK_ERROR,
        SplitInstallErrorCode.ACCESS_DENIED,
        SplitInstallErrorCode.SESSION_NOT_FOUND,
        SplitInstallErrorCode.INCOMPATIBLE_WITH_EXISTING_SESSION,
        -> true

        else -> false
    }
}
