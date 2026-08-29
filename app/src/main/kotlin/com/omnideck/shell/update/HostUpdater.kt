package com.omnideck.shell.update

import com.omnideck.sdk.capability.TelemetryService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the Shell knows about an available update to itself (OD-309).
 *
 * [Urgency] is the whole reason this is a type rather than a boolean. Play offers two
 * flows and they are not interchangeable: a *flexible* update downloads in the
 * background and lets the user carry on; an *immediate* one takes over the screen
 * until it finishes. Immediate for a routine update is hostile. Flexible when a
 * module the user just tapped needs a newer host leaves them on a screen that says
 * "update the app" with nothing on it that updates the app.
 */
sealed interface UpdateOffer {

    data object None : UpdateOffer

    data class Available(val urgency: Urgency, val versionCode: Int) : UpdateOffer

    enum class Urgency {
        /** Routine. Downloads in the background; the user keeps working. */
        FLEXIBLE,

        /**
         * Something the user is trying to do needs it — a module gated on
         * `minHostVersionCode` or `sdkRange` (OD-308). Blocking is justified because
         * the alternative is a dead end.
         */
        IMMEDIATE,
    }
}

/**
 * A narrow seam over Play's `AppUpdateManager`.
 *
 * Exists for the same reason [com.omnideck.kernel.loader.SplitInstaller] does:
 * Play's manager is final, ships no double, and answers through `Task` listeners, so
 * depending on it directly would put every decision below behind a Play-connected
 * device. The policy — which urgency, what to do when Play refuses it, what a user
 * is told — lives in [HostUpdater] on this side of the seam, where a fake can drive
 * it.
 */
interface AppUpdateSource {

    /** Null when there is no update, or when Play could not be asked. */
    suspend fun available(): AvailableUpdate?

    /** True if Play's UI was launched. */
    suspend fun start(urgency: UpdateOffer.Urgency): Boolean

    /**
     * Play can refuse a flow type — an immediate update is not always on offer — and
     * starting one it did not allow fails with nothing to show the user, so both are
     * reported up front rather than discovered at the call site.
     */
    data class AvailableUpdate(val versionCode: Int, val flexibleAllowed: Boolean, val immediateAllowed: Boolean)
}

/**
 * Play In-App Updates (OD-309).
 *
 * Lives in `:app` rather than the kernel: it updates *the host*, which is a fact
 * about the application and not a capability a module is granted. Nothing here is
 * reachable through `PlatformServices` — a module cannot ask the Shell to restart
 * itself.
 */
@Singleton
class HostUpdater @Inject constructor(private val source: AppUpdateSource, private val telemetry: TelemetryService) {

    /**
     * @param blocking true when the user is looking at something that cannot proceed
     *   without the update — a module gated on the host version (OD-308).
     */
    suspend fun check(blocking: Boolean): UpdateOffer {
        val update = source.available() ?: return UpdateOffer.None
        val urgency = when {
            blocking && update.immediateAllowed -> UpdateOffer.Urgency.IMMEDIATE
            // Falls back rather than reporting nothing: a flexible update still
            // resolves a version gate, just without holding the screen. Refusing to
            // offer anything because the preferred flow was unavailable would leave
            // the user with a dead end and no button.
            update.flexibleAllowed -> UpdateOffer.Urgency.FLEXIBLE
            else -> return UpdateOffer.None
        }

        telemetry.event(
            "host_update_available",
            mapOf("urgency" to urgency.name, "version.code" to update.versionCode),
        )
        return UpdateOffer.Available(urgency, update.versionCode)
    }

    /** False when Play would not show its UI; the caller says so rather than appearing to do nothing. */
    suspend fun start(offer: UpdateOffer.Available): Boolean {
        telemetry.event("host_update_started", mapOf("urgency" to offer.urgency.name))
        return source.start(offer.urgency)
    }
}
