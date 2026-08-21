package com.omnideck.core

import java.time.Instant
import java.time.ZoneId

/**
 * Time is injected, never read statically — the same argument as [DispatcherProvider].
 *
 * `System.currentTimeMillis()` is banned repo-wide by detekt's `ForbiddenMethodCall`
 * for this reason: anything reading the wall clock directly is untestable at the
 * boundaries that actually matter (TTL expiry, offline grace periods, token refresh,
 * backoff), and those boundaries are where the interesting bugs live.
 */
interface Clock {

    /** Milliseconds since the Unix epoch. */
    fun nowMillis(): Long

    /** The same instant, when a caller needs date arithmetic rather than a number. */
    fun now(): Instant = Instant.ofEpochMilli(nowMillis())

    /** Monotonic source for measuring elapsed time; unaffected by clock adjustments. */
    fun elapsedNanos(): Long

    /** Zone for rendering timestamps to the user. */
    fun zone(): ZoneId
}

/** Production clock. */
object SystemClock : Clock {
    @Suppress("ForbiddenMethodCall") // the one sanctioned reader of the wall clock
    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun elapsedNanos(): Long = System.nanoTime()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/**
 * Hand-wound clock for tests.
 *
 * Deliberately advances only when told to. A clock that ticks on its own reintroduces
 * exactly the timing nondeterminism this interface exists to remove.
 *
 * ```
 * val clock = MutableClock(startMillis = 0)
 * cache.put("k", "v")           // stored at t=0
 * clock.advanceBy(TTL + 1)
 * assertThat(cache.get("k")).isNull()
 * ```
 */
class MutableClock(startMillis: Long = 0L, private val zone: ZoneId = ZoneId.of("UTC")) : Clock {

    private var millis: Long = startMillis

    // Kept in step with [millis] so elapsed-time measurements agree with wall-clock
    // ones under test, unless a caller deliberately skews them via [skewMonotonic].
    private var nanos: Long = startMillis * NANOS_PER_MILLI

    override fun nowMillis(): Long = millis

    override fun elapsedNanos(): Long = nanos

    override fun zone(): ZoneId = zone

    /** Moves both wall and monotonic time forward. */
    fun advanceBy(millis: Long) = apply {
        require(millis >= 0) { "Time cannot move backwards via advanceBy; use setTo for that." }
        this.millis += millis
        this.nanos += millis * NANOS_PER_MILLI
    }

    /**
     * Jumps wall-clock time without moving monotonic time — models a user changing the
     * device clock or an NTP correction. Code that trusts the wall clock for durations
     * will misbehave here, which is the point of being able to express it.
     */
    fun setTo(millis: Long) = apply { this.millis = millis }

    /** Advances monotonic time alone, without wall-clock movement. */
    fun skewMonotonic(nanos: Long) = apply { this.nanos += nanos }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
