package com.omnideck.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Dispatchers are injected, never referenced statically.
 *
 * detekt's `InjectDispatcher` rule enforces this repo-wide: a test that cannot
 * substitute the dispatcher is a flaky test, and at 20+ modules flaky tests are how
 * a build pipeline stops being trusted.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
    val unconfined: CoroutineDispatcher
}

object DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main.immediate
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
}
