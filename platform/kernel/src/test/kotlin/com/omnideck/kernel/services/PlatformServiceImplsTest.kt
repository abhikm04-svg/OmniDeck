package com.omnideck.kernel.services

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.capability.ConsentPurpose
import com.omnideck.sdk.capability.HttpConfig
import com.omnideck.sdk.capability.LocaleService
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class PlatformServiceImplsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val notes = ModuleId("com.omnideck.notes")

    // -- network ------------------------------------------------------------

    private fun network(id: ModuleId = notes) = NetworkServiceImpl(NetworkEngine(context), id) { "token" }

    @Test
    fun `a derived client applies the module's timeouts`() {
        val client = network().client(
            HttpConfig(connectTimeout = 5.seconds, readTimeout = 7.seconds, writeTimeout = 9.seconds),
        )

        assertThat(client.connectTimeoutMillis).isEqualTo(TimeUnit.SECONDS.toMillis(5).toInt())
        assertThat(client.readTimeoutMillis).isEqualTo(TimeUnit.SECONDS.toMillis(7).toInt())
        assertThat(client.writeTimeoutMillis).isEqualTo(TimeUnit.SECONDS.toMillis(9).toInt())
    }

    @Test
    fun `derived clients share the engine's connection pool`() {
        // ADR-006: one engine, many derived clients — shared sockets and TLS sessions
        // without shared policy. Building a fresh client per module would lose that.
        val engine = NetworkEngine(context)
        val notesClient = NetworkServiceImpl(engine, notes) { null }.client()
        val financeClient = NetworkServiceImpl(engine, ModuleId("com.omnideck.finance")) { null }.client()

        assertThat(notesClient.connectionPool).isSameInstanceAs(financeClient.connectionPool)
        assertThat(notesClient).isNotSameInstanceAs(financeClient)
    }

    @Test
    fun `every request carries the module attribution header`() {
        // Server-side per-module rate limiting and attribution depend on it, so this
        // is checked by running the interceptor rather than by inspecting the list.
        val client = network().client()
        val interceptor = client.interceptors.first()

        val request = Request.Builder().url("https://example.test/").build()
        val seen = interceptor.intercept(RecordingChain(request))

        assertThat(seen.request.header("X-OmniDeck-Module")).isEqualTo(notes.value)
    }

    @Test
    fun `a module's own interceptors are installed alongside attribution`() {
        val marker = Interceptor { chain -> chain.proceed(chain.request()) }

        val client = network().client(HttpConfig(interceptors = listOf(marker)))

        assertThat(client.interceptors).contains(marker)
    }

    @Test
    fun `retrofit is built over the derived client for the given base url`() {
        val retrofit = network().retrofit("https://api.example.test/v1/")

        assertThat(retrofit.baseUrl().toString()).isEqualTo("https://api.example.test/v1/")
    }

    @Test
    fun `connectivity reports a snapshot without a live network`() {
        // Robolectric has no active network; the contract is that this answers rather
        // than throwing, so offline-first modules can start up.
        val engine = NetworkEngine(context)

        val snapshot = engine.snapshot()

        assertThat(snapshot.kind).isNotNull()
    }

    /** Minimal chain that returns the request it was handed, so interceptors can run. */
    private class RecordingChain(private val request: Request) : Interceptor.Chain {
        override fun request() = request
        override fun proceed(request: Request): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()

        override fun connection() = null
        override fun call() = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }

    // -- locale -------------------------------------------------------------

    @Test
    fun `locale reports the configured language tag`() {
        val locale = LocaleServiceImpl(context)

        assertThat(locale.languageTag).isNotEmpty()
    }

    @Test
    fun `currency is formatted from minor units`() {
        // Money is passed as minor units precisely so no caller does float maths on it.
        Locale.setDefault(Locale.US)
        val locale = LocaleServiceImpl(context)

        val formatted = locale.formatCurrency(minorUnits = 123_456, currencyCode = "USD")

        assertThat(formatted).contains("1,234.56")
    }

    @Test
    fun `a left-to-right locale is not reported as RTL`() {
        Locale.setDefault(Locale.US)

        assertThat(LocaleServiceImpl(context).isRtl).isFalse()
    }

    @Test
    fun `dates are formatted for each style without throwing`() {
        val locale = LocaleServiceImpl(context)
        val epoch = 1_700_000_000_000L

        LocaleService.DateStyle.entries.forEach { style ->
            assertThat(locale.formatDate(epoch, style)).isNotEmpty()
        }
    }

    // -- consent ------------------------------------------------------------

    @Test
    fun `consent starts with essential granted and nothing else`() {
        // Anything beyond ESSENTIAL requires the user to say so first.
        val consent = ConsentServiceImpl()

        assertThat(consent.isGranted(ConsentPurpose.ESSENTIAL)).isTrue()
        assertThat(consent.isGranted(ConsentPurpose.PRODUCT_ANALYTICS)).isFalse()
        assertThat(consent.isGranted(ConsentPurpose.MARKETING)).isFalse()
    }

    @Test
    fun `requesting a purpose does not grant it before the Privacy Centre exists`() = runTest {
        // Phase 4 (OD-411) drives this from real UI. Until then it must refuse rather
        // than quietly self-approve, which would be a consent violation.
        val consent = ConsentServiceImpl()

        val granted = consent.request(ConsentPurpose.MARKETING)

        assertThat(granted).isFalse()
        assertThat(consent.isGranted(ConsentPurpose.MARKETING)).isFalse()
    }
}
