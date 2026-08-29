package com.omnideck.shell.update

import com.google.common.truth.Truth.assertThat
import com.omnideck.testing.FakeTelemetryService
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The update policy (OD-309).
 *
 * Everything Play-specific is behind [AppUpdateSource], so what is asserted here is
 * the decision that matters to a user: whether the app takes over the screen. Getting
 * that wrong in either direction is a real cost — an immediate update for a routine
 * release is hostile, and a flexible one for a module gated on the host version
 * leaves someone on a dead-end screen with a button that appears to do nothing.
 */
class HostUpdaterTest {

    private val telemetry = FakeTelemetryService()

    private class FakeSource(
        private val update: AppUpdateSource.AvailableUpdate? = null,
        private val startSucceeds: Boolean = true,
    ) : AppUpdateSource {
        val started = mutableListOf<UpdateOffer.Urgency>()

        override suspend fun available() = update

        override suspend fun start(urgency: UpdateOffer.Urgency): Boolean {
            started += urgency
            return startSucceeds
        }
    }

    private fun update(flexible: Boolean = true, immediate: Boolean = true) =
        AppUpdateSource.AvailableUpdate(versionCode = 42, flexibleAllowed = flexible, immediateAllowed = immediate)

    private fun updater(source: AppUpdateSource) = HostUpdater(source, telemetry)

    @Test
    fun `no update available is not an offer`() = runTest {
        assertThat(updater(FakeSource()).check(blocking = false)).isEqualTo(UpdateOffer.None)
    }

    @Test
    fun `a routine check never takes over the screen`() = runTest {
        val offer = updater(FakeSource(update())).check(blocking = false)

        assertThat(offer).isEqualTo(UpdateOffer.Available(UpdateOffer.Urgency.FLEXIBLE, 42))
    }

    @Test
    fun `a module gated on the host version gets the blocking flow`() = runTest {
        // The user is looking at a screen that says the app is too old for something
        // they just tried to open. A background download lets them carry on doing
        // nothing.
        val offer = updater(FakeSource(update())).check(blocking = true)

        assertThat(offer).isEqualTo(UpdateOffer.Available(UpdateOffer.Urgency.IMMEDIATE, 42))
    }

    @Test
    fun `when Play will not allow the blocking flow, the other one is still offered`() = runTest {
        // A flexible update resolves the version gate too, just without holding the
        // screen. Reporting nothing because the preferred flow was refused leaves the
        // user with a dead end and no button at all.
        val offer = updater(FakeSource(update(immediate = false))).check(blocking = true)

        assertThat(offer).isEqualTo(UpdateOffer.Available(UpdateOffer.Urgency.FLEXIBLE, 42))
    }

    @Test
    fun `an update Play will not start either way is not offered`() = runTest {
        val offer = updater(FakeSource(update(flexible = false, immediate = false))).check(blocking = true)

        assertThat(offer).isEqualTo(UpdateOffer.None)
    }

    @Test
    fun `starting uses the urgency that was decided, not the one Play prefers`() = runTest {
        val source = FakeSource(update())
        val updater = updater(source)

        updater.start(UpdateOffer.Available(UpdateOffer.Urgency.IMMEDIATE, 42))

        assertThat(source.started).containsExactly(UpdateOffer.Urgency.IMMEDIATE)
    }

    @Test
    fun `a flow Play refuses to show is reported as not started`() = runTest {
        // The caller says so rather than leaving a button that appears to do nothing.
        val updater = updater(FakeSource(update(), startSucceeds = false))

        val started = updater.start(UpdateOffer.Available(UpdateOffer.Urgency.FLEXIBLE, 42))

        assertThat(started).isFalse()
    }

    @Test
    fun `an offer is reported to telemetry with its urgency`() = runTest {
        updater(FakeSource(update())).check(blocking = true)

        val event = telemetry.events.single { it.name == "host_update_available" }
        assertThat(event.attributes).containsEntry("urgency", "IMMEDIATE")
    }
}
