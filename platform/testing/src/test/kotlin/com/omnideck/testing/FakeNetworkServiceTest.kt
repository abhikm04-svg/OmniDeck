package com.omnideck.testing

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.capability.Connectivity
import com.omnideck.sdk.capability.HttpConfig
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class FakeNetworkServiceTest {

    @Test
    fun `client applies the configured timeouts`() {
        val network = FakeNetworkService()

        val client = network.client(
            HttpConfig(
                connectTimeout = 5.seconds,
                readTimeout = 7.seconds,
                writeTimeout = 9.seconds,
            ),
        )

        assertThat(client.connectTimeoutMillis).isEqualTo(TimeUnit.SECONDS.toMillis(5).toInt())
        assertThat(client.readTimeoutMillis).isEqualTo(TimeUnit.SECONDS.toMillis(7).toInt())
        assertThat(client.writeTimeoutMillis).isEqualTo(TimeUnit.SECONDS.toMillis(9).toInt())
    }

    @Test
    fun `client installs caller interceptors`() {
        // The reason this fake returns a real OkHttpClient: a module's interceptors
        // must actually run, not merely be recorded as requested.
        val network = FakeNetworkService()
        val marker = Interceptor { chain -> chain.proceed(chain.request()) }

        val client = network.client(HttpConfig(interceptors = listOf(marker)))

        assertThat(client.interceptors).contains(marker)
    }

    @Test
    fun `requested configs are recorded in call order`() {
        val network = FakeNetworkService()

        network.client(HttpConfig(readTimeout = 1.seconds))
        network.client(HttpConfig(readTimeout = 2.seconds))

        assertThat(network.requestedConfigs.map { it.readTimeout })
            .containsExactly(1.seconds, 2.seconds).inOrder()
    }

    @Test
    fun `retrofit uses the supplied base url`() {
        val network = FakeNetworkService()

        val retrofit = network.retrofit("https://example.test/v1/")

        assertThat(retrofit.baseUrl().toString()).isEqualTo("https://example.test/v1/")
        assertThat(network.requestedBaseUrls).containsExactly("https://example.test/v1/")
    }

    @Test
    fun `retrofit falls back to the constructor base url when given a blank one`() {
        val network = FakeNetworkService(baseUrl = "https://fallback.test/")

        val retrofit = network.retrofit("")

        assertThat(retrofit.baseUrl().toString()).isEqualTo("https://fallback.test/")
    }

    @Test
    fun `issued clients are retained for inspection`() {
        val network = FakeNetworkService()

        network.client()
        network.retrofit("https://example.test/")

        // Two: one direct, one built inside retrofit().
        assertThat(network.issuedClients).hasSize(2)
    }

    // -- connectivity control ----------------------------------------------

    @Test
    fun `defaults to online over unmetered wifi`() {
        val network = FakeNetworkService()

        assertThat(network.isOnline()).isTrue()
    }

    @Test
    fun `setOffline flips isOnline`() {
        val network = FakeNetworkService()

        network.setOffline()

        assertThat(network.isOnline()).isFalse()
    }

    @Test
    fun `setOnline can model a metered cellular connection`() {
        val network = FakeNetworkService()

        network.setOnline(metered = true, kind = Connectivity.Kind.CELLULAR)

        assertThat(network.isOnline()).isTrue()
        assertThat(network.requestedConfigs).isEmpty()
    }

    @Test
    fun `connectivity flow emits current state then transitions`() = runTest {
        val network = FakeNetworkService()

        network.connectivity.test {
            assertThat(awaitItem().online).isTrue()

            network.setOffline()
            val offline = awaitItem()
            assertThat(offline.online).isFalse()
            assertThat(offline.kind).isEqualTo(Connectivity.Kind.NONE)

            network.setOnline(metered = true, kind = Connectivity.Kind.CELLULAR)
            val metered = awaitItem()
            assertThat(metered.online).isTrue()
            assertThat(metered.metered).isTrue()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connectivity honours a custom initial state`() {
        val network = FakeNetworkService(
            initialConnectivity = Connectivity(false, metered = false, kind = Connectivity.Kind.NONE),
        )

        assertThat(network.isOnline()).isFalse()
    }
}
