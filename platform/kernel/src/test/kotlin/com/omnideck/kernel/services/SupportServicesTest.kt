package com.omnideck.kernel.services

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.capability.NotificationService
import com.omnideck.sdk.capability.WorkScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Notifications and background work, both of which are per-module by design.
 *
 * The recurring property: a module tags everything it creates with its own id, so the
 * platform can silence, cancel or attribute one module without touching another.
 * Losing that tagging is invisible until quarantine fails to stop a module's work, or
 * a user muting one module silences the whole app.
 */
@RunWith(RobolectricTestRunner::class)
class SupportServicesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val notes = ModuleId("com.omnideck.notes")
    private val finance = ModuleId("com.omnideck.finance")

    @Before
    fun initialiseWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    // -- flags --------------------------------------------------------------

    @Test
    fun `a flag returns its default until set`() {
        val flags = InMemoryFeatureFlagService()

        assertThat(flags.boolean("missing", default = true)).isTrue()
        flags.put("missing", false)
        assertThat(flags.boolean("missing", default = true)).isFalse()
    }

    @Test
    fun `the kill switch convention defaults to enabled`() {
        // ModuleLifecycleManager reads module.<id>.enabled on every activation, so an
        // unset flag must mean "available" — otherwise a config outage disables the app.
        val flags = InMemoryFeatureFlagService()

        assertThat(flags.boolean("module.${notes.value}.enabled", default = true)).isTrue()
    }

    @Test
    fun `a flag of the wrong type falls back to the default`() {
        val flags = InMemoryFeatureFlagService().apply { put("count", "not-a-number") }

        assertThat(flags.long("count", default = 7L)).isEqualTo(7L)
    }

    @Test
    fun `json decoding failure falls back rather than throwing`() {
        // A malformed remote payload must not crash a module.
        val flags = InMemoryFeatureFlagService().apply { put("config", "{{{") }

        val decoded = flags.json("config", default = "fallback") { error("bad json") }

        assertThat(decoded).isEqualTo("fallback")
    }

    // -- notifications ------------------------------------------------------

    private fun notifications(id: ModuleId, displayName: String = "Notes") =
        NotificationServiceImpl(context, id, displayName, com.omnideck.testing.FakePermissionBroker())

    private fun spec(id: Int = 1, channel: String = "general") = NotificationService.NotificationSpec(
        id = id,
        channelId = channel,
        title = "Title",
        body = "Body",
    )

    @Test
    fun `posting creates a channel group named for the module`() = runTest {
        // The grouping is what lets a user silence one module from system settings
        // instead of silencing OmniDeck entirely — a retention cliff in a super-app.
        notifications(notes).post(spec())

        val nm = context.getSystemService(NotificationManager::class.java)
        assertThat(nm.notificationChannelGroups.map { it.id })
            .contains("module.${notes.value}")
    }

    @Test
    fun `channels are namespaced so two modules can both use 'general'`() = runTest {
        notifications(notes).post(spec(channel = "general"))
        notifications(finance, "Finance").post(spec(channel = "general"))

        val nm = context.getSystemService(NotificationManager::class.java)
        val ids = nm.notificationChannels.map { it.id }
        assertThat(ids).containsAtLeast("${notes.value}.general", "${finance.value}.general")
    }

    @Test
    fun `importance is mapped through to the channel`() = runTest {
        notifications(notes).post(
            spec(channel = "urgent").copy(importance = NotificationService.Importance.HIGH),
        )

        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.notificationChannels.single { it.id == "${notes.value}.urgent" }
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
    }

    @Test
    fun `a posted notification is visible`() = runTest {
        val posted = notifications(notes).post(spec(id = 42))

        assertThat(posted).isTrue()
        assertThat(shadowOf(context.getSystemService(NotificationManager::class.java)).size())
            .isAtLeast(1)
    }

    @Test
    fun `two modules using the same notification id do not collide`() = runTest {
        // Ids are module-scoped, so a module cannot replace another's notification.
        notifications(notes).post(spec(id = 1))
        notifications(finance, "Finance").post(spec(id = 1))

        assertThat(shadowOf(context.getSystemService(NotificationManager::class.java)).size())
            .isEqualTo(2)
    }

    @Test
    fun `cancel removes only that module's notification`() = runTest {
        val notesNotifications = notifications(notes)
        notesNotifications.post(spec(id = 1))
        notifications(finance, "Finance").post(spec(id = 1))

        notesNotifications.cancel(1)

        assertThat(shadowOf(context.getSystemService(NotificationManager::class.java)).size())
            .isEqualTo(1)
    }

    // -- work ---------------------------------------------------------------

    private fun workSpec(name: String) = WorkScheduler.WorkSpec(
        name = name,
        worker = androidx.work.Worker::class.java,
    )

    @Test
    fun `enqueued work is tagged with the module id`() {
        // Quarantine cancels by tag, so untagged work would survive a module being
        // disabled — the failure mode the tag exists to prevent.
        WorkSchedulerImpl(context, notes).enqueue(workSpec("sync"))

        val tagged = WorkManager.getInstance(context)
            .getWorkInfosByTag("omnideck.module.${notes.value}")
            .get()

        assertThat(tagged).hasSize(1)
    }

    @Test
    fun `work is also tagged with its own name`() {
        WorkSchedulerImpl(context, notes).enqueue(workSpec("sync"))

        assertThat(WorkManager.getInstance(context).getWorkInfosByTag("sync").get()).hasSize(1)
    }

    @Test
    fun `enqueue returns a distinct id per request`() {
        val scheduler = WorkSchedulerImpl(context, notes)

        val first = scheduler.enqueue(workSpec("a"))
        val second = scheduler.enqueue(workSpec("b"))

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `cancelAll cancels this module's work and leaves another module's alone`() {
        val notesScheduler = WorkSchedulerImpl(context, notes)
        val financeScheduler = WorkSchedulerImpl(context, finance)
        notesScheduler.enqueue(workSpec("notes-sync"))
        financeScheduler.enqueue(workSpec("finance-sync"))

        notesScheduler.cancelAll()

        val wm = WorkManager.getInstance(context)
        assertThat(wm.getWorkInfosByTag("omnideck.module.${finance.value}").get()).hasSize(1)
    }

    @Test
    fun `periodic work is enqueued uniquely so re-registration does not stack duplicates`() {
        // Plain enqueue() would add a second schedule every time a module
        // re-registers, running the same job N times per interval.
        val scheduler = WorkSchedulerImpl(context, notes)

        scheduler.enqueuePeriodic(workSpec("refresh"), Duration.ofHours(1))
        scheduler.enqueuePeriodic(workSpec("refresh"), Duration.ofHours(1))

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("omnideck.module.${notes.value}.refresh")
            .get()
        assertThat(infos).hasSize(1)
    }

    @Test
    fun `two modules may schedule periodic work under the same spec name`() {
        WorkSchedulerImpl(context, notes).enqueuePeriodic(workSpec("refresh"), Duration.ofHours(1))
        WorkSchedulerImpl(context, finance).enqueuePeriodic(workSpec("refresh"), Duration.ofHours(1))

        val wm = WorkManager.getInstance(context)
        assertThat(wm.getWorkInfosForUniqueWork("omnideck.module.${notes.value}.refresh").get()).hasSize(1)
        assertThat(wm.getWorkInfosForUniqueWork("omnideck.module.${finance.value}.refresh").get()).hasSize(1)
    }

    @Test
    fun `cancelling by id removes that work`() {
        val scheduler = WorkSchedulerImpl(context, notes)
        val id = scheduler.enqueue(workSpec("sync"))

        scheduler.cancel(id)

        val info = WorkManager.getInstance(context).getWorkInfoById(java.util.UUID.fromString(id)).get()
        assertThat(info?.state).isEqualTo(androidx.work.WorkInfo.State.CANCELLED)
    }
}
