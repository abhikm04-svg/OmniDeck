package com.omnideck.core

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.Test

class OutcomeTest {

    @Test
    fun `success carries its value`() {
        val outcome: Outcome<Int> = Outcome.Success(42)

        assertThat(outcome.isSuccess).isTrue()
        assertThat(outcome.getOrNull()).isEqualTo(42)
        assertThat(outcome.getOrDefault(0)).isEqualTo(42)
    }

    @Test
    fun `failure reports no value and falls back to the default`() {
        val outcome: Outcome<Int> = Outcome.Failure(OmniError.Offline)

        assertThat(outcome.isSuccess).isFalse()
        assertThat(outcome.getOrNull()).isNull()
        assertThat(outcome.getOrDefault(7)).isEqualTo(7)
    }

    /**
     * Regression: `getOrDefault` used to be an interface member needing
     * `@UnsafeVariance`, which made every call on a Failure throw
     * ClassCastException at runtime — on the error path, where callers depend on it.
     */
    @Test
    fun `getOrDefault on a failure returns the default instead of throwing`() {
        val outcome: Outcome<Int> = Outcome.Failure(OmniError.Offline)

        assertThat(outcome.getOrDefault(99)).isEqualTo(99)
    }

    @Test
    fun `getOrDefault distinguishes a null success value from a failure`() {
        // An elvis-based implementation collapses these two; branching on the
        // variant keeps "succeeded with no value" distinct from "failed".
        val nullSuccess: Outcome<String?> = Outcome.Success(null)
        val failure: Outcome<String?> = Outcome.Failure(OmniError.NotFound)

        assertThat(nullSuccess.getOrDefault("fallback")).isNull()
        assertThat(failure.getOrDefault("fallback")).isEqualTo("fallback")
    }

    @Test
    fun `map transforms a success`() {
        val mapped = Outcome.Success(2).map { it * 3 }

        assertThat(mapped.getOrNull()).isEqualTo(6)
    }

    @Test
    fun `map leaves a failure untouched and does not run the transform`() {
        var ran = false
        val original: Outcome<Int> = Outcome.Failure(OmniError.Timeout)

        val mapped = original.map {
            ran = true
            it * 3
        }

        assertThat(ran).isFalse()
        assertThat(mapped).isSameInstanceAs(original)
    }

    @Test
    fun `flatMap chains successes`() {
        val chained = Outcome.Success(4).flatMap { Outcome.Success(it + 1) }

        assertThat(chained.getOrNull()).isEqualTo(5)
    }

    @Test
    fun `flatMap short-circuits on the first failure`() {
        var ran = false
        val original: Outcome<Int> = Outcome.Failure(OmniError.NotFound)

        val chained = original.flatMap {
            ran = true
            Outcome.Success(it)
        }

        assertThat(ran).isFalse()
        assertThat(chained).isSameInstanceAs(original)
    }

    @Test
    fun `onFailure runs only for failures and returns the receiver`() {
        val seen = mutableListOf<OmniError>()

        val success = Outcome.Success(1).onFailure { seen += it.error }
        val failure = Outcome.Failure(OmniError.Unauthorized).onFailure { seen += it.error }

        assertThat(seen).containsExactly(OmniError.Unauthorized)
        assertThat(success.getOrNull()).isEqualTo(1)
        assertThat(failure.isSuccess).isFalse()
    }

    @Test
    fun `outcomeOf wraps a returned value`() {
        val outcome = outcomeOf { "ok" }

        assertThat(outcome.getOrNull()).isEqualTo("ok")
    }

    @Test
    fun `outcomeOf converts a thrown exception into a failure that keeps the cause`() {
        val boom = IllegalStateException("boom")

        val outcome = outcomeOf { throw boom }

        val failure = outcome as Outcome.Failure
        assertThat(failure.cause).isSameInstanceAs(boom)
        assertThat(failure.error).isEqualTo(OmniError.Unknown("boom"))
    }

    @Test
    fun `outcomeOf falls back to the class name when an exception has no message`() {
        val outcome = outcomeOf { throw IllegalArgumentException() }

        assertThat((outcome as Outcome.Failure).error)
            .isEqualTo(OmniError.Unknown("IllegalArgumentException"))
    }

    /**
     * The one case that must not be swallowed. Catching Throwable and turning
     * cancellation into a Failure value breaks structured concurrency: the coroutine
     * carries on doing work its caller has already abandoned.
     */
    @Test
    fun `outcomeOf lets cancellation propagate instead of capturing it`() {
        val cancellation = CancellationException("cancelled")

        val thrown = runCatching { outcomeOf { throw cancellation } }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancellation)
    }
}
