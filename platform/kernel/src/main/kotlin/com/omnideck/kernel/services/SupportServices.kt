package com.omnideck.kernel.services

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.capability.FeatureFlagService
import com.omnideck.sdk.capability.MediaService
import com.omnideck.sdk.capability.NotificationService
import com.omnideck.sdk.capability.PermissionBroker
import com.omnideck.sdk.capability.WorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local flag store, replaced by Remote Config + the owned flag service in Phase 4
 * (OD-124/OD-310). The interface is already the one modules will keep using, so the
 * swap is invisible to them — which is the whole point of the capability boundary.
 *
 * Note the kill-switch convention baked in here: `module.<id>.enabled` defaulting to
 * true. `ModuleLifecycleManager` reads it on every activation, so disabling a module
 * in production is a config push, not a release (QA-9).
 */
@Singleton
class InMemoryFeatureFlagService @Inject constructor() : FeatureFlagService {

    private val values = ConcurrentHashMap<String, Any>()
    private val revision = MutableStateFlow(0)

    fun put(key: String, value: Any) {
        values[key] = value
        revision.value++
    }

    override fun boolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    override fun string(key: String, default: String) = values[key] as? String ?: default
    override fun long(key: String, default: Long) = values[key] as? Long ?: default
    override fun double(key: String, default: Double) = values[key] as? Double ?: default

    override fun <T> json(key: String, default: T, decode: (String) -> T): T =
        (values[key] as? String)?.let { runCatching { decode(it) }.getOrNull() } ?: default

    override fun booleanFlow(key: String, default: Boolean): Flow<Boolean> = revision.map { boolean(key, default) }

    override suspend fun refresh(): Boolean = true
}

/**
 * Notifications, one channel *group* per module.
 *
 * The grouping matters more than it looks: it lets a user silence a single module
 * from system settings instead of silencing OmniDeck entirely. In a super-app,
 * "turn off all notifications" is a retention cliff, and per-module channels are the
 * cheapest way to avoid it.
 */
class NotificationServiceImpl(
    private val context: Context,
    private val moduleId: ModuleId,
    private val moduleDisplayName: String,
    private val permissions: PermissionBroker,
) : NotificationService {

    private val manager = NotificationManagerCompat.from(context)
    private val groupId = "module.${moduleId.value}"

    // The permission is checked via areNotificationsEnabled() and any SecurityException
    // from a race (revoked between check and post) is contained by runCatching, which is
    // what the `false` return exists to report. Lint cannot see through either.
    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun post(spec: NotificationService.NotificationSpec): Boolean {
        if (!areNotificationsEnabled()) return false
        ensureChannel(spec.channelId, spec.importance)

        val notification = NotificationCompat.Builder(context, scopedChannelId(spec.channelId))
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(spec.ongoing)
            .setAutoCancel(!spec.ongoing)
            .build()

        return runCatching { manager.notify(scopedId(spec.id), notification) }.isSuccess
    }

    override suspend fun cancel(notificationId: Int) = manager.cancel(scopedId(notificationId))

    override suspend fun cancelAll() {
        context.getSystemService<NotificationManager>()
            ?.activeNotifications
            ?.filter { it.id.hasModulePrefix() }
            ?.forEach { manager.cancel(it.id) }
    }

    // POST_NOTIFICATIONS is a compile-time String constant, so inlining it below API 33
    // is harmless — PermissionBroker no-ops for permissions the platform doesn't know.
    @android.annotation.SuppressLint("InlinedApi")
    override suspend fun ensurePermission(rationale: PermissionBroker.Rationale): Boolean = permissions.ensure(
        android.Manifest.permission.POST_NOTIFICATIONS,
        rationale,
    ) == PermissionBroker.PermissionResult.GRANTED

    override fun areNotificationsEnabled(): Boolean = manager.areNotificationsEnabled()

    private fun ensureChannel(channelId: String, importance: NotificationService.Importance) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannelGroup(NotificationChannelGroup(groupId, moduleDisplayName))
        nm.createNotificationChannel(
            NotificationChannel(
                scopedChannelId(channelId),
                channelId.replaceFirstChar(Char::titlecase),
                when (importance) {
                    NotificationService.Importance.MIN -> NotificationManager.IMPORTANCE_MIN
                    NotificationService.Importance.LOW -> NotificationManager.IMPORTANCE_LOW
                    NotificationService.Importance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
                    NotificationService.Importance.HIGH -> NotificationManager.IMPORTANCE_HIGH
                },
            ).apply { group = groupId },
        )
    }

    private fun scopedChannelId(channelId: String) = "${moduleId.value}.$channelId"

    private fun scopedId(id: Int) = moduleId.value.hashCode() * PRIME + id

    private fun Int.hasModulePrefix() = (this - (moduleId.value.hashCode() * PRIME)) in 0..MAX_MODULE_NOTIFICATIONS

    private companion object {
        const val PRIME = 31
        const val MAX_MODULE_NOTIFICATIONS = 1000
    }
}

/**
 * WorkManager wrapper. Every request is tagged with the module id, which is what
 * makes [cancelAll] — used by quarantine and purge — atomic and complete.
 */
class WorkSchedulerImpl(context: Context, private val moduleId: ModuleId) : WorkScheduler {

    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val tag = "omnideck.module.${moduleId.value}"

    override fun enqueue(spec: WorkScheduler.WorkSpec): String {
        val request = OneTimeWorkRequest.Builder(spec.workerClass())
            .setConstraints(spec.constraints())
            .setInputData(spec.data())
            .setInitialDelay(spec.initialDelay.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(tag)
            .addTag(spec.name)
            .build()
        workManager.enqueue(request)
        return request.id.toString()
    }

    override fun enqueuePeriodic(spec: WorkScheduler.WorkSpec, interval: Duration): String {
        val request = PeriodicWorkRequest.Builder(
            spec.workerClass(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS,
        )
            .setConstraints(spec.constraints())
            .setInputData(spec.data())
            .addTag(tag)
            .addTag(spec.name)
            .build()
        // Plain enqueue() would stack a duplicate schedule every time a module
        // re-registers its periodic work (e.g. on each activation), so the same job
        // ends up running N times per interval. The unique name is module-scoped so
        // two modules may use the same spec name without colliding.
        workManager.enqueueUniquePeriodicWork(
            "$tag.${spec.name}",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        return request.id.toString()
    }

    override fun cancel(workId: String) {
        workManager.cancelWorkById(java.util.UUID.fromString(workId))
    }

    override fun cancelAll() {
        workManager.cancelAllWorkByTag(tag)
    }

    override fun status(workId: String): Flow<WorkScheduler.WorkStatus> = flowOf(
        WorkScheduler.WorkStatus.ENQUEUED,
    )

    @Suppress("UNCHECKED_CAST")
    private fun WorkScheduler.WorkSpec.workerClass() = worker as Class<out ListenableWorker>

    private fun WorkScheduler.WorkSpec.constraints() = Constraints.Builder()
        .setRequiredNetworkType(
            when {
                requiresUnmetered -> NetworkType.UNMETERED
                requiresNetwork -> NetworkType.CONNECTED
                else -> NetworkType.NOT_REQUIRED
            },
        )
        .setRequiresCharging(requiresCharging)
        .build()

    private fun WorkScheduler.WorkSpec.data() = Data.Builder()
        .putAll(input as Map<String, Any?>)
        .putString("omnideck.moduleId", moduleId.value)
        .build()
}

/** Media picking lands in Phase 6 (OD-611 dependencies); the contract is already fixed. */
class UnavailableMediaService(private val moduleId: ModuleId) : MediaService {
    private fun unavailable(): Nothing =
        throw UnsupportedOperationException("MediaService is not implemented yet (module $moduleId).")

    override suspend fun pickImage(allowMultiple: Boolean): List<Uri> = unavailable()
    override suspend fun pickDocument(mimeTypes: List<String>): List<Uri> = unavailable()
    override suspend fun captureImage(): Uri? = unavailable()
    override suspend fun importToModuleStorage(uri: Uri, fileName: String) = unavailable()
}

/**
 * Runtime permissions.
 *
 * Enforces the manifest contract before touching the OS: a module that did not
 * declare a permission gets [PermissionBroker.PermissionResult.NOT_DECLARED] and an
 * audit event, never a system dialog. Declaring intent up front is what makes a
 * capability review meaningful.
 */
class PermissionBrokerImpl(
    private val context: Context,
    private val moduleId: ModuleId,
    private val declared: Set<String>,
    private val requester: PermissionRequester,
    private val onAudit: (String, PermissionBroker.PermissionResult, String) -> Unit,
) : PermissionBroker {

    override suspend fun ensure(
        permission: String,
        rationale: PermissionBroker.Rationale,
    ): PermissionBroker.PermissionResult {
        if (permission !in declared) {
            onAudit(permission, PermissionBroker.PermissionResult.NOT_DECLARED, rationale.purpose)
            return PermissionBroker.PermissionResult.NOT_DECLARED
        }
        if (isGranted(permission)) return PermissionBroker.PermissionResult.GRANTED

        val result = requester.request(permission, rationale)
        onAudit(permission, result, rationale.purpose)
        return result
    }

    override fun isGranted(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun openSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/** Implemented by the Shell's Activity, which owns the ActivityResult plumbing. */
fun interface PermissionRequester {
    suspend fun request(permission: String, rationale: PermissionBroker.Rationale): PermissionBroker.PermissionResult
}
