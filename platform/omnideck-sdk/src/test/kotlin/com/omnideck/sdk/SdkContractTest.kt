package com.omnideck.sdk

import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.capability.Connectivity
import com.omnideck.sdk.capability.HttpConfig
import com.omnideck.sdk.capability.RetryPolicy
import com.omnideck.sdk.capability.SecureStore
import com.omnideck.sdk.capability.getString
import com.omnideck.sdk.capability.putString
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * The Android half of the contract is almost entirely interfaces, so what is left to
 * test is small but load-bearing: the defaults every module inherits without asking,
 * and the convenience overloads it will actually call.
 *
 * A default changing silently is the failure mode here — every module picks it up on
 * its next build, and nothing in the module's own tests would notice.
 */
class SdkContractTest {

    // -- network defaults ---------------------------------------------------

    @Test
    fun `default http config is authenticated with sane timeouts`() {
        // Authenticated by default is deliberate: a module that forgets to opt in
        // should still carry the session, not silently make anonymous calls.
        val config = HttpConfig()

        assertThat(config.authenticated).isTrue()
        assertThat(config.connectTimeout).isEqualTo(15.seconds)
        assertThat(config.readTimeout).isEqualTo(30.seconds)
        assertThat(config.writeTimeout).isEqualTo(30.seconds)
        assertThat(config.interceptors).isEmpty()
    }

    @Test
    fun `the default retry policy retries only transient status codes`() {
        // Retrying a 400 or 404 is wasted battery and data: the same request will
        // fail identically. Only overload and gateway failures are worth repeating.
        val retryOn = RetryPolicy.Default.retryOn

        assertThat(retryOn).containsExactly(408, 429, 500, 502, 503, 504)
        assertThat(retryOn).doesNotContain(400)
        assertThat(retryOn).doesNotContain(401)
        assertThat(retryOn).doesNotContain(404)
    }

    @Test
    fun `the default retry policy is bounded and backs off`() {
        val policy = RetryPolicy.Default

        assertThat(policy.maxAttempts).isEqualTo(3)
        assertThat(policy.initialBackoff).isLessThan(policy.maxBackoff)
    }

    @Test
    fun `the None policy makes exactly one attempt`() {
        // For non-idempotent calls, where a retry could double-charge.
        assertThat(RetryPolicy.None.maxAttempts).isEqualTo(1)
        assertThat(RetryPolicy.None.retryOn).isEmpty()
    }

    @Test
    fun `a config can override individual fields without losing the rest`() {
        val config = HttpConfig(authenticated = false)

        assertThat(config.authenticated).isFalse()
        assertThat(config.retryPolicy).isEqualTo(RetryPolicy.Default)
    }

    // -- connectivity -------------------------------------------------------

    @Test
    fun `connectivity distinguishes offline from metered`() {
        // Offline-first modules branch on both: metered means "defer the big sync",
        // offline means "queue it".
        val offline = Connectivity(online = false, metered = false, kind = Connectivity.Kind.NONE)
        val cellular = Connectivity(online = true, metered = true, kind = Connectivity.Kind.CELLULAR)

        assertThat(offline.online).isFalse()
        assertThat(cellular.online).isTrue()
        assertThat(cellular.metered).isTrue()
        assertThat(Connectivity.Kind.entries).containsAtLeast(
            Connectivity.Kind.NONE,
            Connectivity.Kind.WIFI,
            Connectivity.Kind.CELLULAR,
        )
    }

    // -- capability refusal -------------------------------------------------

    @Test
    fun `an undeclared capability refusal names the module, the capability and the fix`() {
        // A module author hits this at runtime with no other context, so the message
        // has to be self-contained.
        val error = CapabilityNotGrantedException(
            ModuleId("com.omnideck.notes"),
            CapabilityId.STORAGE,
        )

        assertThat(error).hasMessageThat().contains("com.omnideck.notes")
        assertThat(error).hasMessageThat().contains("omnideck.storage")
        assertThat(error).hasMessageThat().contains("requiredCapabilities")
    }

    @Test
    fun `capability refusal is a SecurityException`() {
        // Type matters: it is a boundary violation, not a programming slip, and
        // catch-all error handling should not quietly swallow it as recoverable.
        val error = CapabilityNotGrantedException(ModuleId("com.omnideck.notes"), CapabilityId.AUTH)

        assertThat(error).isInstanceOf(SecurityException::class.java)
        assertThat(error.capabilityId).isEqualTo(CapabilityId.AUTH)
    }

    // -- secure store conveniences -----------------------------------------

    private class InMemorySecureStore : SecureStore {
        private val values = ConcurrentHashMap<String, ByteArray>()
        override suspend fun put(alias: String, value: ByteArray) {
            values[alias] = value
        }

        override suspend fun get(alias: String): ByteArray? = values[alias]
        override suspend fun delete(alias: String) {
            values.remove(alias)
        }

        override suspend fun contains(alias: String) = values.containsKey(alias)
        override suspend fun putBiometricGated(alias: String, value: ByteArray, timeoutSeconds: Int) = false
        override suspend fun getBiometricGated(
            alias: String,
            promptTitle: String,
            promptSubtitle: String?,
        ): ByteArray? = null
    }

    @Test
    fun `string helpers round-trip through the byte API`() {
        val store = InMemorySecureStore()

        runTest {
            store.putString("token", "s3cret")

            assertThat(store.getString("token")).isEqualTo("s3cret")
        }
    }

    @Test
    fun `reading an absent string yields null rather than an empty string`() {
        // Empty and absent are different: one is a stored blank, the other is a
        // missing credential, and only the second should trigger a sign-in.
        runTest {
            assertThat(InMemorySecureStore().getString("never-written")).isNull()
        }
    }

    @Test
    fun `string helpers use UTF-8 so non-ASCII survives`() {
        val store = InMemorySecureStore()

        runTest {
            store.putString("name", "Ωmega — 東京")

            assertThat(store.getString("name")).isEqualTo("Ωmega — 東京")
        }
    }

    // -- capability registry conveniences ----------------------------------

    private class RecordingRegistry : CapabilityRegistry {
        val registered = mutableMapOf<CapabilityId, Class<*>>()
        private val instances = mutableMapOf<CapabilityId, Any>()

        override fun <T : Any> register(id: CapabilityId, type: Class<T>, provider: () -> T) {
            registered[id] = type
            instances[id] = provider()
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> resolve(id: CapabilityId, type: Class<T>): T? =
            instances[id]?.takeIf { type.isInstance(it) } as T?

        override fun isAvailable(id: CapabilityId) = instances.containsKey(id)
    }

    @Test
    fun `the reified register overload infers the type`() {
        // The overload module authors actually use; if inference broke, every call
        // site would need the Class literal back.
        val registry = RecordingRegistry()
        val id = CapabilityId("omnideck.notes.exporter")

        registry.register(id) { "exporter" }

        assertThat(registry.registered[id]).isEqualTo(String::class.java)
    }

    @Test
    fun `the reified resolve overload returns the typed instance`() {
        val registry = RecordingRegistry()
        val id = CapabilityId("omnideck.notes.exporter")
        registry.register(id) { "exporter" }

        val resolved: String? = registry.resolve(id)

        assertThat(resolved).isEqualTo("exporter")
    }

    @Test
    fun `resolving as the wrong type yields null rather than throwing`() {
        val registry = RecordingRegistry()
        val id = CapabilityId("omnideck.notes.exporter")
        registry.register(id) { "exporter" }

        val resolved: Int? = registry.resolve(id)

        assertThat(resolved).isNull()
    }
}
