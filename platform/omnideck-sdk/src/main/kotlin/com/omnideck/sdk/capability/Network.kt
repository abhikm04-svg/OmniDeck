package com.omnideck.sdk.capability

import kotlinx.coroutines.flow.Flow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP for modules (ADR-006).
 *
 * One `OkHttpClient` engine is owned by the kernel — connection pool, HTTP/2, cache,
 * certificate pinning, auth interceptor, telemetry `EventListener`. Each module gets
 * a *derived* client via `newBuilder()`, so sockets and TLS sessions are shared
 * (performance) while policy is not (isolation).
 */
interface NetworkService {

    /** A client derived from the shared engine, tagged with this module's id. */
    fun client(config: HttpConfig = HttpConfig()): OkHttpClient

    /** A Retrofit instance over [client], with kotlinx-serialization wired up. */
    fun retrofit(baseUrl: String, config: HttpConfig = HttpConfig()): Retrofit

    /** Current connectivity, for offline-first modules. */
    val connectivity: Flow<Connectivity>

    fun isOnline(): Boolean
}

data class HttpConfig(
    val connectTimeout: Duration = 15.seconds,
    val readTimeout: Duration = 30.seconds,
    val writeTimeout: Duration = 30.seconds,
    /** Attach the platform session token automatically. */
    val authenticated: Boolean = true,
    /** Retry idempotent requests with jittered exponential backoff. */
    val retryPolicy: RetryPolicy = RetryPolicy.Default,
    val interceptors: List<Interceptor> = emptyList(),
)

data class RetryPolicy(
    val maxAttempts: Int,
    val initialBackoff: Duration,
    val maxBackoff: Duration,
    val retryOn: Set<Int>,
) {
    companion object {
        val Default = RetryPolicy(
            maxAttempts = 3,
            initialBackoff = 1.seconds,
            maxBackoff = 20.seconds,
            retryOn = setOf(408, 429, 500, 502, 503, 504),
        )
        val None = RetryPolicy(1, 0.seconds, 0.seconds, emptySet())
    }
}

data class Connectivity(val online: Boolean, val metered: Boolean, val kind: Kind) {
    enum class Kind { NONE, WIFI, CELLULAR, ETHERNET, OTHER }
}
