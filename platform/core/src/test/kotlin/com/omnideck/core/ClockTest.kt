package com.omnideck.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

class ClockTest {

    @Test
    fun `mutable clock does not advance on its own`() {
        val clock = MutableClock(startMillis = 1_000)

        val first = clock.nowMillis()
        repeat(1_000) { clock.nowMillis() }

        assertThat(clock.nowMillis()).isEqualTo(first)
    }

    @Test
    fun `advanceBy moves wall and monotonic time together`() {
        val clock = MutableClock(startMillis = 0)
        val startNanos = clock.elapsedNanos()

        clock.advanceBy(1_500)

        assertThat(clock.nowMillis()).isEqualTo(1_500)
        assertThat(clock.elapsedNanos() - startNanos).isEqualTo(1_500_000_000L)
    }

    @Test
    fun `now reflects the advanced instant`() {
        val clock = MutableClock(startMillis = 0)

        clock.advanceBy(60_000)

        assertThat(clock.now().toEpochMilli()).isEqualTo(60_000)
    }

    @Test
    fun `advanceBy rejects negative movement`() {
        val clock = MutableClock()

        val error = runCatching { clock.advanceBy(-1) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * The reason [Clock] exposes both a wall clock and a monotonic source: a device
     * clock correction moves one and not the other. Anything measuring a duration from
     * `nowMillis` deltas is wrong here, and this test pins that they can diverge.
     */
    @Test
    fun `setTo jumps wall time without moving monotonic time`() {
        val clock = MutableClock(startMillis = 10_000)
        val nanosBefore = clock.elapsedNanos()

        clock.setTo(0)

        assertThat(clock.nowMillis()).isEqualTo(0)
        assertThat(clock.elapsedNanos()).isEqualTo(nanosBefore)
    }

    @Test
    fun `skewMonotonic advances elapsed time without moving wall time`() {
        val clock = MutableClock(startMillis = 5_000)

        clock.skewMonotonic(2_000_000_000L)

        assertThat(clock.nowMillis()).isEqualTo(5_000)
        assertThat(clock.elapsedNanos()).isEqualTo(5_000 * 1_000_000L + 2_000_000_000L)
    }

    @Test
    fun `mutable clock defaults to UTC so tests do not depend on machine zone`() {
        assertThat(MutableClock().zone()).isEqualTo(ZoneId.of("UTC"))
    }

    @Test
    fun `system clock reports a plausible current time`() {
        val before = System.currentTimeMillis()

        val reported = SystemClock.nowMillis()

        // Bounds rather than equality: the point is that it reads the real clock,
        // not that it hits an exact millisecond.
        assertThat(reported).isAtLeast(before)
        assertThat(reported).isAtMost(System.currentTimeMillis())
    }

    @Test
    fun `system clock monotonic source moves forward`() {
        val first = SystemClock.elapsedNanos()
        val second = SystemClock.elapsedNanos()

        assertThat(second).isAtLeast(first)
    }
}
