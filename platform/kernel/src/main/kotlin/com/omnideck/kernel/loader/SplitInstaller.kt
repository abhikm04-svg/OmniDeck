package com.omnideck.kernel.loader

import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * A narrow seam over Play Feature Delivery.
 *
 * Play's `SplitInstallManager` ships no test double, is final, and reports progress
 * through a listener plus a `Task`. Depending on it directly would make everything
 * downstream — status mapping, error classification, retry policy — reachable only
 * from a Play-connected device.
 *
 * So the Play API is confined to [PlaySplitInstaller], which is deliberately dumb:
 * it registers a listener, filters to its own session, and translates. Every decision
 * worth getting right lives in [FeatureSplitProvider] on the other side of this
 * interface, where a fake can drive it.
 */
interface SplitInstaller {

    /** Split names already present on device. */
    fun installedSplits(): Set<String>

    /** Emits progress for one install, completing after a terminal update. */
    fun install(splitName: String): Flow<SplitSessionUpdate>

    /**
     * Asks Play to reclaim a split. Deferred by contract: Play removes it
     * opportunistically, so nothing may promise the user immediate space savings.
     */
    fun deferredUninstall(splitName: String)
}

/** Delivery-agnostic view of one session update. */
data class SplitSessionUpdate(
    val status: SplitStatus,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    /** Play's `SplitInstallErrorCode`, meaningful only when [status] is [SplitStatus.FAILED]. */
    val errorCode: Int = 0,
)

enum class SplitStatus {
    PENDING,
    DOWNLOADING,

    /**
     * Play requires explicit consent for large or metered downloads. Dropping this
     * is the usual cause of "stuck at 0%" reports (OD-302).
     */
    REQUIRES_USER_CONFIRMATION,
    INSTALLING,
    INSTALLED,
    CANCELED,
    FAILED,

    /** A status this platform version does not model; ignored rather than guessed at. */
    UNKNOWN,
}

/** The real thing. Kept free of policy so there is little here to get wrong untested. */
class PlaySplitInstaller(private val manager: SplitInstallManager) : SplitInstaller {

    override fun installedSplits(): Set<String> = manager.installedModules

    override fun install(splitName: String): Flow<SplitSessionUpdate> = callbackFlow {
        // Updates for other sessions arrive on the same listener, so anything emitted
        // before startInstall reports an id cannot be attributed and is dropped.
        var sessionId = UNASSIGNED_SESSION

        val listener = SplitInstallStateUpdatedListener { state: SplitInstallSessionState ->
            if (state.sessionId() != sessionId) return@SplitInstallStateUpdatedListener

            val update = SplitSessionUpdate(
                status = state.status().toSplitStatus(),
                bytesDownloaded = state.bytesDownloaded(),
                totalBytes = state.totalBytesToDownload(),
                errorCode = state.errorCode(),
            )
            trySend(update)
            if (update.status.isTerminal) close()
        }

        manager.registerListener(listener)

        manager.startInstall(SplitInstallRequest.newBuilder().addModule(splitName).build())
            .addOnSuccessListener { sessionId = it }
            .addOnFailureListener { error ->
                trySend(
                    SplitSessionUpdate(
                        status = SplitStatus.FAILED,
                        errorCode = START_INSTALL_FAILED,
                    ),
                )
                close(error)
            }

        awaitClose { manager.unregisterListener(listener) }
    }

    override fun deferredUninstall(splitName: String) {
        manager.deferredUninstall(listOf(splitName))
    }

    private fun Int.toSplitStatus(): SplitStatus = when (this) {
        SplitInstallSessionStatus.PENDING -> SplitStatus.PENDING
        SplitInstallSessionStatus.DOWNLOADING -> SplitStatus.DOWNLOADING
        SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> SplitStatus.REQUIRES_USER_CONFIRMATION
        SplitInstallSessionStatus.INSTALLING -> SplitStatus.INSTALLING
        SplitInstallSessionStatus.INSTALLED -> SplitStatus.INSTALLED
        SplitInstallSessionStatus.CANCELED -> SplitStatus.CANCELED
        SplitInstallSessionStatus.FAILED -> SplitStatus.FAILED
        else -> SplitStatus.UNKNOWN
    }

    private companion object {
        const val UNASSIGNED_SESSION = -1

        /** Not a Play error code: startInstall itself never got as far as a session. */
        const val START_INSTALL_FAILED = -1
    }
}

internal val SplitStatus.isTerminal: Boolean
    get() = this == SplitStatus.INSTALLED || this == SplitStatus.CANCELED || this == SplitStatus.FAILED
