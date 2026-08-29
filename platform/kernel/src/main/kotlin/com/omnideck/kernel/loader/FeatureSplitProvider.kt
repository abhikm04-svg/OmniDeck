package com.omnideck.kernel.loader

import com.google.android.play.core.splitinstall.model.SplitInstallErrorCode
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.InstallProgress
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.OmniModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
 *  2. `REQUIRES_USER_CONFIRMATION` must reach the UI *and* be answered, or large and
 *     metered downloads stall silently at 0% (OD-302). Play holds such a session
 *     indefinitely — it never times out — so the dialog is launched here rather than
 *     left to whichever screen happens to be collecting the progress flow.
 */
class FeatureSplitProvider(
    private val installer: SplitInstaller,
    private val io: CoroutineDispatcher,
    /** Re-read per load: SplitCompat swaps it in after an install. */
    private val classLoader: () -> ClassLoader,
) : ModuleProvider {

    override val handles = DeliveryKind.FEATURE_SPLIT

    /**
     * Play's session id per module while an install is running (OD-302).
     *
     * Cancellation arrives from the UI as a [ModuleId] — the user pressed Cancel on a
     * row — while Play only cancels by session id, and that id exists nowhere but the
     * update stream. Entries are removed on the terminal update, so a later Cancel on
     * a finished install asks Play nothing rather than cancelling whatever session
     * happens to hold that id next.
     */
    private val liveSessions = ConcurrentHashMap<ModuleId, Int>()

    override fun isInstalled(id: ModuleId): Boolean = id.splitName in installer.installedSplits()

    override fun install(id: ModuleId): Flow<InstallProgress> {
        // Already present: report success without troubling Play for a session.
        if (isInstalled(id)) return flowOf(InstallProgress.Installed)

        return flow {
            try {
                installer.install(id.splitName).collect { update ->
                    if (update.status.isTerminal) liveSessions.remove(id) else liveSessions[id] = update.sessionId

                    val progress = toProgress(update) ?: return@collect
                    emit(progress)
                    if (progress is InstallProgress.RequiresUserConfirmation) {
                        confirm(update.sessionId)
                    }
                }
            } finally {
                // Also on cancellation of the collector: a stale id here would send a
                // later Cancel to a session this module no longer owns.
                liveSessions.remove(id)
            }
        }
    }

    override fun cancelInstall(id: ModuleId) {
        liveSessions[id]?.let(installer::cancelInstall)
    }

    /**
     * Asks Play to show its consent dialog, and reports a dead install if it cannot.
     *
     * The failure branch matters more than it looks. Nothing further arrives on a
     * session whose confirmation was never shown, so without it the collector waits
     * on a flow that will not complete: the Router's `first()` on a terminal progress
     * never returns, the tile stays at 0%, and the user has nothing to retry.
     */
    private suspend fun FlowCollector<InstallProgress>.confirm(sessionId: Int) {
        if (installer.requestUserConfirmation(sessionId)) return
        emit(
            InstallProgress.Failed(
                code = CONFIRMATION_UNAVAILABLE,
                message = "OmniDeck could not ask for permission to download this module. " +
                    "Open OmniDeck and try again.",
                // The usual cause is the app being in the background when Play asked,
                // which the next attempt from a visible screen resolves.
                retryable = true,
            ),
        )
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

    companion object {
        /**
         * Not one of Play's codes — the install never got as far as asking Play
         * anything. Negative so it cannot collide with a future `SplitInstallErrorCode`,
         * and distinct from `PlaySplitInstaller`'s own -1 so telemetry can tell "Play
         * refused to start" from "we could not ask the user".
         */
        const val CONFIRMATION_UNAVAILABLE = -2
    }
}
