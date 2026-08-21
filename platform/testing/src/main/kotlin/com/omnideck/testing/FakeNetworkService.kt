package com.omnideck.testing

import com.omnideck.sdk.capability.Connectivity
import com.omnideck.sdk.capability.HttpConfig
import com.omnideck.sdk.capability.NetworkService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * [NetworkService] fake that hands back real OkHttp/Retrofit instances pointed wherever
 * the test wants — typically a `MockWebServer`.
 *
 * Returning real clients rather than mocks matters: a module's interceptors, timeouts
 * and serialization all run for real, so the test covers the wiring rather than
 * asserting that a mock was called.
 *
 * Connectivity is directly controllable, which is the point for offline-first modules —
 * [setOffline] and [setConnectivity] let a test drive the transitions that are awkward
 * to produce on a real device.
 *
 * ```
 * val server = MockWebServer()
 * val network = FakeNetworkService(baseUrl = server.url("/").toString())
 * network.setOffline()
 * // ...assert the module queues work instead of failing...
 * network.setConnectivity(Connectivity(true, metered = true, Connectivity.Kind.CELLULAR))
 * ```
 */
class FakeNetworkService(
    /** Base URL handed to [retrofit] when a caller does not override it. */
    private val baseUrl: String = "http://localhost/",
    initialConnectivity: Connectivity = Connectivity(
        online = true,
        metered = false,
        kind = Connectivity.Kind.WIFI,
    ),
) : NetworkService {

    private val connectivityState = MutableStateFlow(initialConnectivity)

    /** Every [HttpConfig] a module asked for, in call order. */
    val requestedConfigs = mutableListOf<HttpConfig>()

    /** Every base URL passed to [retrofit], in call order. */
    val requestedBaseUrls = mutableListOf<String>()

    /** Clients handed out, so a test can inspect the timeouts a module chose. */
    val issuedClients = mutableListOf<OkHttpClient>()

    override fun client(config: HttpConfig): OkHttpClient {
        requestedConfigs += config
        return OkHttpClient.Builder()
            .connectTimeout(config.connectTimeout.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeout.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeout.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .apply { config.interceptors.forEach(::addInterceptor) }
            .build()
            .also { issuedClients += it }
    }

    override fun retrofit(baseUrl: String, config: HttpConfig): Retrofit {
        requestedBaseUrls += baseUrl
        return Retrofit.Builder()
            .baseUrl(baseUrl.ifBlank { this.baseUrl })
            .client(client(config))
            .build()
    }

    override val connectivity: Flow<Connectivity> = connectivityState.asStateFlow()

    override fun isOnline(): Boolean = connectivityState.value.online

    // -- test controls ------------------------------------------------------

    fun setConnectivity(connectivity: Connectivity) {
        connectivityState.value = connectivity
    }

    fun setOffline() {
        connectivityState.value = Connectivity(false, metered = false, kind = Connectivity.Kind.NONE)
    }

    fun setOnline(metered: Boolean = false, kind: Connectivity.Kind = Connectivity.Kind.WIFI) {
        connectivityState.value = Connectivity(true, metered = metered, kind = kind)
    }
}
