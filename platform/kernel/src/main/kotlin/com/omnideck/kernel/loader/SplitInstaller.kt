package com.omnideck.kernel.loader

import android.content.IntentSender
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * Shows Play's consent dialog for a session waiting on it (OD-302).
     *
     * Play holds a large or metered download in
     * [SplitStatus.REQUIRES_USER_CONFIRMATION] until this dialog is answered — it
     * does not time out, retry or fail. Reporting the state to the UI without asking
     * is therefore not "surfacing" it; it is the 0% stall with a nicer label.
     *
     * Returns false when there is nothing to show — an unknown session, a session
     * Play gave no resolution intent for, or no UI attached to show it from. The
     * caller must treat that as a dead install rather than keep waiting, because
     * nothing further will arrive on the session.
     */
    suspend fun requestUserConfirmation(sessionId: Int): Boolean
}

/**
 * Launches an OS-owned consent dialog on the kernel's behalf.
 *
 * Implemented by the Shell's single Activity, which owns the ActivityResult
 * plumbing; the kernel holds no Activity reference (ADR-003), and Play's dialog is
 * an `IntentSender` the system renders, so this seam carries no Play type and no
 * Android UI type beyond it.
 */
fun interface ConfirmationLauncher {
    /** True if the dialog was shown and the user accepted. */
    suspend fun launch(intentSender: IntentSender): Boolean
}

/** Delivery-agnostic view of one session update. */
data class SplitSessionUpdate(
    val status: SplitStatus,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    /** Play's `SplitInstallErrorCode`, meaningful only when [status] is [SplitStatus.FAILED]. */
    val errorCode: Int = 0,
    /**
     * Play's session id. Needed to answer the confirmation prompt on the *right*
     * session: a listener sees every session in the process, and consenting to the
     * wrong one authorises a download the user never asked for.
     */
    val sessionId: Int = 0,
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
class PlaySplitInstaller(private val manager: SplitInstallManager, private val confirmation: ConfirmationLauncher) :
    SplitInstaller {

    /**
     * The latest state seen per session, because Play's consent dialog can only be
     * launched from the session state that requested it and the listener is the only
     * place that state exists. Entries are dropped as sessions reach a terminal
     * status, so this cannot grow with the length of a user's session.
     */
    private val sessionStates = ConcurrentHashMap<Int, SplitInstallSessionState>()

    override fun installedSplits(): Set<String> = manager.installedModules

    override fun install(splitName: String): Flow<SplitSessionUpdate> = callbackFlow {
        // Updates for other sessions arrive on the same listener, so anything emitted
        // before startInstall reports an id cannot be attributed and is dropped.
        var sessionId = UNASSIGNED_SESSION

        val listener = SplitInstallStateUpdatedListener { state: SplitInstallSessionState ->
            // Recorded before the filter: requestUserConfirmation looks a session up
            // by id, and a state dropped here is a consent prompt that can never be
            // shown afterwards.
            val status = state.status().toSplitStatus()
            if (status.isTerminal) {
                sessionStates.remove(state.sessionId())
            } else {
                sessionStates[state.sessionId()] = state
            }

            if (state.sessionId() != sessionId) return@SplitInstallStateUpdatedListener

            val update = SplitSessionUpdate(
                status = status,
                bytesDownloaded = state.bytesDownloaded(),
                totalBytes = state.totalBytesToDownload(),
                errorCode = state.errorCode(),
                sessionId = state.sessionId(),
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

    /**
     * Launches the resolution intent Play attached to the waiting session.
     *
     * This is what `startConfirmationDialogForResult` does internally. Going through
     * the `IntentSender` directly is deliberate: it lets the Shell answer with an
     * ActivityResult contract rather than the `onActivityResult` request-code path
     * that overload requires, and it keeps this class the only file in the repo that
     * knows Play's types.
     */
    @Suppress("DEPRECATION")
    override suspend fun requestUserConfirmation(sessionId: Int): Boolean {
        val intentSender = sessionStates[sessionId]?.resolutionIntent()?.intentSender ?: return false
        return confirmation.launch(intentSender)
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
