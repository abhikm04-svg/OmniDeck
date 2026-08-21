package com.omnideck.kernel.loader

import android.content.Context
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallErrorCode
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.InstallProgress
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.OmniModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * Play Feature Delivery — the primary delivery channel (ADR-001).
 *
 * Three production details that are easy to miss and expensive to discover late:
 *
 *  1. `SplitCompat.install()` must already have run in `Application.attachBaseContext`
 *     (see OmniDeckApplication), or freshly installed split code is not reachable
 *     until the process restarts — users see a spinner and then nothing.
 *  2. [SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION] must be surfaced, or
 *     large/metered downloads silently stall at 0% (OD-302).
 *  3. Uninstall is *deferred*: Play reclaims the space opportunistically, so the UI
 *     must never promise immediate space savings.
 */
class FeatureSplitProvider(
    private val context: Context,
    private val splitInstallManager: SplitInstallManager,
    private val io: CoroutineDispatcher,
) : ModuleProvider {

    override val handles = DeliveryKind.FEATURE_SPLIT

    override fun isInstalled(id: ModuleId): Boolean = splitInstallManager.installedModules.contains(id.splitName)

    override fun install(id: ModuleId): Flow<InstallProgress> = callbackFlow {
        if (isInstalled(id)) {
            trySend(InstallProgress.Installed)
            close()
            return@callbackFlow
        }

        var sessionId = -1

        val listener = SplitInstallStateUpdatedListener { state: SplitInstallSessionState ->
            if (state.sessionId() != sessionId) return@SplitInstallStateUpdatedListener

            when (state.status()) {
                SplitInstallSessionStatus.PENDING ->
                    trySend(InstallProgress.Pending)

                SplitInstallSessionStatus.DOWNLOADING ->
                    trySend(InstallProgress.Downloading(state.bytesDownloaded(), state.totalBytesToDownload()))

                SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION ->
                    trySend(InstallProgress.RequiresUserConfirmation)

                SplitInstallSessionStatus.INSTALLING ->
                    trySend(InstallProgress.Installing)

                SplitInstallSessionStatus.INSTALLED -> {
                    trySend(InstallProgress.Installed)
                    close()
                }

                SplitInstallSessionStatus.CANCELED -> {
                    trySend(InstallProgress.Canceled)
                    close()
                }

                SplitInstallSessionStatus.FAILED -> {
                    trySend(
                        InstallProgress.Failed(
                            code = state.errorCode(),
                            message = describe(state.errorCode()),
                            retryable = isRetryable(state.errorCode()),
                        ),
                    )
                    close()
                }

                else -> Unit
            }
        }

        splitInstallManager.registerListener(listener)

        splitInstallManager
            .startInstall(SplitInstallRequest.newBuilder().addModule(id.splitName).build())
            .addOnSuccessListener { sessionId = it }
            .addOnFailureListener { error ->
                trySend(
                    InstallProgress.Failed(
                        code = -1,
                        message = error.message ?: "startInstall failed",
                        retryable = true,
                    ),
                )
                close()
            }

        awaitClose { splitInstallManager.unregisterListener(listener) }
    }

    override suspend fun uninstall(id: ModuleId) {
        splitInstallManager.deferredUninstall(listOf(id.splitName))
    }

    override suspend fun load(descriptor: ModuleDescriptor): OmniModule = withContext(io) {
        if (!isInstalled(descriptor.id)) {
            throw ModuleLoadException(descriptor.id, "split '${descriptor.id.splitName}' is not installed")
        }
        try {
            val klass = context.classLoader.loadClass(descriptor.entryPointClass)
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
        }
    }

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

    private fun isRetryable(code: Int): Boolean = when (code) {
        SplitInstallErrorCode.NETWORK_ERROR,
        SplitInstallErrorCode.ACCESS_DENIED,
        SplitInstallErrorCode.SESSION_NOT_FOUND,
        SplitInstallErrorCode.INCOMPATIBLE_WITH_EXISTING_SESSION,
        -> true

        else -> false
    }
}
