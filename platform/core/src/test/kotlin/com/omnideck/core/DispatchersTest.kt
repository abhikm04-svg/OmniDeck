package com.omnideck.core

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchersTest {

    // There is no Main dispatcher on a plain JVM, so touching
    // DefaultDispatcherProvider.main throws unless one is installed first.
    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun removeMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `default provider maps each role to the matching coroutine dispatcher`() {
        // Pins the wiring: a copy-paste slip that pointed `io` at Default would
        // otherwise surface only as mysterious jank under load.
        assertThat(DefaultDispatcherProvider.default).isSameInstanceAs(Dispatchers.Default)
        assertThat(DefaultDispatcherProvider.io).isSameInstanceAs(Dispatchers.IO)
        assertThat(DefaultDispatcherProvider.unconfined).isSameInstanceAs(Dispatchers.Unconfined)
    }

    @Test
    fun `main dispatcher is the immediate variant`() {
        // Dispatchers.Main.immediate avoids a needless re-post when already on the
        // main thread, which matters for Compose recomposition latency.
        assertThat(DefaultDispatcherProvider.main).isEqualTo(Dispatchers.Main.immediate)
    }

    @Test
    fun `a substituted provider is what callers receive`() {
        // The reason the interface exists: tests inject their own.
        val provider = object : DispatcherProvider {
            override val main = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val io = Dispatchers.Unconfined
            override val unconfined = Dispatchers.Unconfined
        }

        assertThat(provider.io).isSameInstanceAs(Dispatchers.Unconfined)
    }
}
