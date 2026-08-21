package com.omnideck.core

/**
 * Explicit success/failure without exceptions crossing layer boundaries.
 *
 * Named [Outcome] rather than `Result` so it never collides with `kotlin.Result`,
 * whose restrictions (no suspend return, no generic reuse) bite in practice.
 */
sealed interface Outcome<out T> {

    data class Success<T>(val value: T) : Outcome<T>

    data class Failure(val error: OmniError, val cause: Throwable? = null) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value
}

/**
 * The value on success, [default] on failure.
 *
 * An extension rather than a member, deliberately. As a member it needed
 * `@UnsafeVariance` to typecheck against `out T`, and that annotation only silenced
 * the compiler: [Outcome.Failure] is an `Outcome<Nothing>`, so the inherited member
 * specialised `T` to `Nothing` and the compiler inserted a cast of the returned value
 * to `Void`. Every `getOrDefault` call on a failure threw ClassCastException — on the
 * error path, which is exactly where callers rely on it.
 *
 * As an extension, `T` is bound at the call site from the declared type, so no such
 * cast exists. It also branches on the variant rather than using `?:`, so a
 * `Success(null)` correctly yields null instead of being mistaken for a failure.
 */
fun <T> Outcome<T>.getOrDefault(default: T): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> default
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onFailure(action: (Outcome.Failure) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(this)
    return this
}

/** Domain error taxonomy. Kept small on purpose — a long enum is a design smell. */
sealed interface OmniError {
    data object Offline : OmniError
    data object Timeout : OmniError
    data class Http(val code: Int, val body: String?) : OmniError
    data object Unauthorized : OmniError
    data object Forbidden : OmniError
    data object NotFound : OmniError
    data class Serialization(val detail: String) : OmniError
    data class Storage(val detail: String) : OmniError
    data class Unknown(val detail: String) : OmniError
}

/** Runs [block], mapping thrown exceptions into an [Outcome.Failure]. */
@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
inline fun <T> outcomeOf(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (t: Throwable) {
    // Coroutine cancellation must keep propagating, not be swallowed into a
    // Failure value — catching Throwable and re-throwing this one case is the
    // standard coroutines idiom, not a genuine "too generic" catch.
    if (t is kotlinx.coroutines.CancellationException) throw t
    Outcome.Failure(OmniError.Unknown(t.message ?: t::class.java.simpleName), t)
}
