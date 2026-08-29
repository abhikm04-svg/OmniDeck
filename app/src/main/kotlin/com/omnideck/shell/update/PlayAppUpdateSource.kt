package com.omnideck.shell.update

import android.content.Context
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The real thing. Kept free of policy so there is little here to get wrong untested.
 *
 * Every failure path collapses to "no update": Play is unreachable on a device
 * without it, on a sideloaded build, and on any network hiccup, none of which is
 * worth surfacing to a user and all of which would otherwise take down whatever
 * screen asked.
 */
@Singleton
class PlayAppUpdateSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val starter: UpdateFlowStarterHolder,
) : AppUpdateSource {

    private val manager: AppUpdateManager by lazy { AppUpdateManagerFactory.create(context) }

    override suspend fun available(): AppUpdateSource.AvailableUpdate? {
        val info = requestInfo() ?: return null
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return null

        return AppUpdateSource.AvailableUpdate(
            versionCode = info.availableVersionCode(),
            flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
            immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
        )
    }

    override suspend fun start(urgency: UpdateOffer.Urgency): Boolean {
        // Re-requested rather than cached from `available()`: Play's info object is
        // single-use for starting a flow, and a stale one fails silently.
        val info = requestInfo() ?: return false
        val type = when (urgency) {
            UpdateOffer.Urgency.FLEXIBLE -> AppUpdateType.FLEXIBLE
            UpdateOffer.Urgency.IMMEDIATE -> AppUpdateType.IMMEDIATE
        }
        return starter.start(manager, info, AppUpdateOptions.newBuilder(type).build())
    }

    private suspend fun requestInfo(): AppUpdateInfo? = runCatching {
        suspendCancellableCoroutine { continuation ->
            manager.appUpdateInfo
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
        }
    }.getOrNull()
}

/**
 * The Activity's half of the update flow.
 *
 * Play needs an ActivityResult launcher to show its UI, and the Shell's single
 * Activity owns that plumbing (ADR-003). Same shape as `ConfirmationLauncher` for
 * the split-install consent dialog, and attached the same way.
 */
fun interface UpdateFlowStarter {
    /** True if the flow was launched. False means there was nothing to show it from. */
    fun start(manager: AppUpdateManager, info: AppUpdateInfo, options: AppUpdateOptions): Boolean
}

/** Holds whatever Activity is currently attached, so a singleton can reach it. */
@Singleton
class UpdateFlowStarterHolder @Inject constructor() : UpdateFlowStarter {

    @Volatile
    private var delegate: UpdateFlowStarter? = null

    fun attach(starter: UpdateFlowStarter) {
        delegate = starter
    }

    override fun start(manager: AppUpdateManager, info: AppUpdateInfo, options: AppUpdateOptions): Boolean =
        delegate?.start(manager, info, options) ?: false
}
