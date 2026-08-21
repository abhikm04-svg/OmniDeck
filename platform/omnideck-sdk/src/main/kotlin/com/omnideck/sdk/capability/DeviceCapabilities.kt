package com.omnideck.sdk.capability

import android.net.Uri
import com.omnideck.sdk.Sku
import kotlinx.coroutines.flow.Flow
import java.time.Duration

/**
 * Runtime permissions, centralised (architecture.md §9).
 *
 * Modules may not call `requestPermissions` directly — detekt and a custom Lint rule
 * forbid it. Routing every request through the broker gives one consistent rationale
 * UX, correct "don't ask again" handling, a settings deep link, an audit event per
 * grant/denial, and enforcement that the permission was actually declared in the
 * module manifest.
 */
interface PermissionBroker {

    /** Requests [permission], showing [rationale] first if the platform says we should. */
    suspend fun ensure(permission: String, rationale: Rationale): PermissionResult

    fun isGranted(permission: String): Boolean

    /** Opens the OS app-settings page — the only recovery from PERMANENTLY_DENIED. */
    fun openSettings()

    data class Rationale(
        val title: String,
        val message: String,
        /** Shown to the user *and* recorded in the audit log. Be honest here. */
        val purpose: String,
    )

    enum class PermissionResult {
        GRANTED,
        DENIED,
        PERMANENTLY_DENIED,

        /** The module did not declare this permission in its manifest. Never granted. */
        NOT_DECLARED,
    }
}

/**
 * Notifications, with one channel group per module so a user can silence a single
 * module without silencing the platform — a retention lever as much as a UX nicety.
 */
interface NotificationService {

    /** Posts to a channel within this module's group; creates the channel on demand. */
    suspend fun post(spec: NotificationSpec): Boolean

    suspend fun cancel(notificationId: Int)

    /** Cancels everything this module has posted. Used on quarantine and purge. */
    suspend fun cancelAll()

    /** Contextual `POST_NOTIFICATIONS` request — never fired at app startup. */
    suspend fun ensurePermission(rationale: PermissionBroker.Rationale): Boolean

    fun areNotificationsEnabled(): Boolean

    data class NotificationSpec(
        val id: Int,
        val channelId: String,
        val title: String,
        val body: String,
        /** Tapping the notification navigates here — routed through the Router. */
        val route: com.omnideck.sdk.Route? = null,
        val importance: Importance = Importance.DEFAULT,
        val ongoing: Boolean = false,
    )

    enum class Importance { MIN, LOW, DEFAULT, HIGH }
}

/**
 * Play Billing (architecture.md §13).
 *
 * The client is never the authority: [entitlements] reflects a server-verified
 * snapshot, not a local purchase record. Anything else is revenue leakage.
 */
interface BillingService {

    val entitlements: Flow<Set<Sku>>

    suspend fun products(skus: Set<Sku>): List<Product>

    suspend fun purchase(sku: Sku): PurchaseResult

    /** Re-syncs from the server. Called on foreground and after a restore. */
    suspend fun refresh()

    data class Product(
        val sku: Sku,
        val title: String,
        val description: String,
        val formattedPrice: String,
        val priceMinorUnits: Long,
        val currencyCode: String,
        val subscription: Boolean,
    )

    sealed interface PurchaseResult {
        data class Purchased(val sku: Sku) : PurchaseResult
        data object Cancelled : PurchaseResult
        data object Pending : PurchaseResult
        data class Failed(val code: Int, val message: String) : PurchaseResult
    }
}

/**
 * Background work. Every job is tagged with the owning module id, which is what
 * makes quarantine and purge able to cancel a module's work atomically.
 */
interface WorkScheduler {

    fun enqueue(spec: WorkSpec): String

    fun enqueuePeriodic(spec: WorkSpec, interval: Duration): String

    fun cancel(workId: String)

    /** Cancels every job this module owns. Called by the kernel, not by modules. */
    fun cancelAll()

    fun status(workId: String): Flow<WorkStatus>

    data class WorkSpec(
        val name: String,
        val worker: Class<*>,
        val input: Map<String, String> = emptyMap(),
        val requiresNetwork: Boolean = true,
        val requiresUnmetered: Boolean = false,
        val requiresCharging: Boolean = false,
        val initialDelay: Duration = Duration.ZERO,
        val expedited: Boolean = false,
    )

    enum class WorkStatus { ENQUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED }
}

/** Camera, gallery and document picking — brokered so permissions stay centralised. */
interface MediaService {
    suspend fun pickImage(allowMultiple: Boolean = false): List<Uri>
    suspend fun pickDocument(mimeTypes: List<String>): List<Uri>
    suspend fun captureImage(): Uri?

    /** Copies a picked Uri into this module's private storage and returns the local file. */
    suspend fun importToModuleStorage(uri: Uri, fileName: String): java.io.File?
}
