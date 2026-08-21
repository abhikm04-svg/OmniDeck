package com.omnideck.kernel.events

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.capability.PlatformEvent
import com.omnideck.testing.FakeTelemetryService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Module-to-module facts (architecture.md §10.3).
 *
 * Two design choices are load-bearing and both are asserted here rather than left to
 * the comment. Delivery is filtered by type, so a subscriber never has to defend
 * against events it did not ask for. And the bus is lossy on purpose: applying
 * backpressure would let one slow module stall a publisher, which in a super-app
 * means one module freezing another.
 */
class EventBusImplTest {

    private fun bus(telemetry: FakeTelemetryService = FakeTelemetryService()) = EventBusImpl(telemetry)

    private fun sessionChanged(signedIn: Boolean = true) =
        PlatformEvent.SessionChanged(signedIn = signedIn, userIdHash = null)

    @Test
    fun `a subscriber receives events of its type`() = runTest {
        val bus = bus()

        bus.subscribe(PlatformEvent.SessionChanged::class.java).test {
            bus.publish(sessionChanged())
            assertThat(awaitItem().signedIn).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a subscriber never sees events of another type`() = runTest {
        // The filter is what lets a module subscribe without defensive casting.
        val bus = bus()

        bus.subscribe(PlatformEvent.ThemeChanged::class.java).test {
            bus.publish(sessionChanged())
            bus.publish(PlatformEvent.ThemeChanged(darkMode = true, dynamicColor = false))

            assertThat(awaitItem().darkMode).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `every subscriber of a type receives the same event`() = runTest {
        val bus = bus()
        val first = bus.subscribe(PlatformEvent.SessionChanged::class.java)
        val second = bus.subscribe(PlatformEvent.SessionChanged::class.java)

        bus.publish(sessionChanged())

        // Replay means both see it even though they collect after publication.
        assertThat(first.first().signedIn).isTrue()
        assertThat(second.first().signedIn).isTrue()
    }

    @Test
    fun `a late subscriber still sees recent facts`() = runTest {
        // A module initialising after sign-in must learn the session state without
        // the Shell having to re-announce it.
        val bus = bus()
        bus.publish(sessionChanged(signedIn = true))

        val received = bus.subscribe(PlatformEvent.SessionChanged::class.java).first()

        assertThat(received.signedIn).isTrue()
    }

    @Test
    fun `replay is bounded so the bus does not grow without limit`() = runTest {
        val bus = bus()
        repeat(20) { bus.publish(PlatformEvent.LocaleChanged(languageTag = "en-$it")) }

        val replayed = mutableListOf<PlatformEvent.LocaleChanged>()
        bus.subscribe(PlatformEvent.LocaleChanged::class.java).test {
            repeat(8) { replayed += awaitItem() }
            cancelAndIgnoreRemainingEvents()
        }

        // The most recent 8, not all 20 — an unbounded replay would leak.
        assertThat(replayed.last().languageTag).isEqualTo("en-19")
        assertThat(replayed.first().languageTag).isEqualTo("en-12")
    }

    @Test
    fun `publishing with no subscribers is harmless`() {
        val bus = bus()

        bus.publish(sessionChanged())
    }

    @Test
    fun `a quarantine is broadcast so other modules can drop their integrations`() = runTest {
        val bus = bus()
        val notes = ModuleId("com.omnideck.notes")

        bus.publish(PlatformEvent.ModuleStateChanged(notes, ModuleState.QUARANTINED))

        val received = bus.subscribe(PlatformEvent.ModuleStateChanged::class.java).first()
        assertThat(received.moduleId).isEqualTo(notes)
        assertThat(received.state).isEqualTo(ModuleState.QUARANTINED)
    }

    @Test
    fun `events of different types are independently observable`() = runTest {
        val bus = bus()
        bus.publish(sessionChanged())
        bus.publish(PlatformEvent.ThemeChanged(darkMode = true, dynamicColor = true))

        val sessions = bus.subscribe(PlatformEvent.SessionChanged::class.java).first()
        val themes = bus.subscribe(PlatformEvent.ThemeChanged::class.java).first()

        assertThat(sessions.signedIn).isTrue()
        assertThat(themes.dynamicColor).isTrue()
    }
}
