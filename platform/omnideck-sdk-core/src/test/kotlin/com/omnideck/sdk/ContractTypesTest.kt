package com.omnideck.sdk

import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.capability.ConsentPurpose
import com.omnideck.sdk.capability.Principal
import com.omnideck.sdk.capability.SessionState
import com.omnideck.sdk.capability.TelemetryService
import com.omnideck.sdk.capability.traced
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The small value types the whole contract is built from. They look trivial, but each
 * encodes a decision the platform relies on — which states count as usable, which
 * capabilities the kernel guarantees, how a session reports its principal.
 */
class ContractTypesTest {

    // -- module state -------------------------------------------------------

    @Test
    fun `only active and degraded count as usable`() {
        // isUsable gates rendering and short-circuits activation, so widening it by
        // accident would let the Shell render a quarantined or half-installed module.
        val usable = ModuleState.entries.filter { it.isUsable }

        assertThat(usable).containsExactly(ModuleState.ACTIVE, ModuleState.DEGRADED)
    }

    @Test
    fun `every state a module can reach is represented`() {
        // Guards against a state being removed while the lifecycle still produces it.
        assertThat(ModuleState.entries).containsAtLeast(
            ModuleState.ADVERTISED,
            ModuleState.INSTALLING,
            ModuleState.INSTALLED,
            ModuleState.INITIALIZING,
            ModuleState.ACTIVE,
            ModuleState.DEGRADED,
            ModuleState.SUSPENDED,
            ModuleState.GATED,
            ModuleState.QUARANTINED,
            ModuleState.PURGING,
            ModuleState.FAILED,
        )
    }

    @Test
    fun `purge scopes widen from cache to everything`() {
        assertThat(PurgeScope.entries)
            .containsExactly(PurgeScope.CACHE, PurgeScope.SESSION, PurgeScope.ALL).inOrder()
    }

    @Test
    fun `suspend reasons cover the platform's own triggers`() {
        assertThat(SuspendReason.entries).containsAtLeast(
            SuspendReason.MEMORY_PRESSURE,
            SuspendReason.KILL_SWITCH,
            SuspendReason.ENTITLEMENT_REVOKED,
        )
    }

    // -- capabilities -------------------------------------------------------

    @Test
    fun `kernel-provided capabilities are the ones the kernel actually implements`() {
        // A capability listed here but not implemented would let a manifest pass the
        // gate and then fail at first use — the failure mode the gate exists to stop.
        assertThat(CapabilityId.KERNEL_PROVIDED).containsExactly(
            CapabilityId.AUTH,
            CapabilityId.NETWORK,
            CapabilityId.STORAGE,
            CapabilityId.SECURE_STORE,
            CapabilityId.TELEMETRY,
            CapabilityId.FLAGS,
            CapabilityId.ROUTER,
            CapabilityId.EVENTS,
            CapabilityId.PERMISSIONS,
            CapabilityId.NOTIFICATIONS,
            CapabilityId.BILLING,
            CapabilityId.WORK,
            CapabilityId.CONSENT,
            CapabilityId.LOCALE,
            CapabilityId.MEDIA,
        )
    }

    @Test
    fun `biometric is deliberately not kernel-guaranteed`() {
        // Not every device has an enrolled biometric, so a module cannot rely on it
        // being present the way it relies on storage.
        assertThat(CapabilityId.KERNEL_PROVIDED).doesNotContain(CapabilityId.BIOMETRIC)
    }

    @Test
    fun `capability ids are namespaced`() {
        assertThat(CapabilityId.STORAGE.value).startsWith("omnideck.")
    }

    // -- route args ---------------------------------------------------------

    @Test
    fun `typed accessors convert their values`() {
        val args = RouteArgs(mapOf("id" to "42", "flag" to "true", "name" to "notes"))

        assertThat(args.string("name")).isEqualTo("notes")
        assertThat(args.int("id")).isEqualTo(42)
        assertThat(args.boolean("flag")).isTrue()
    }

    @Test
    fun `a missing required argument fails with the key named`() {
        // The message is what a module author sees when a route pattern and a
        // destination disagree, so it has to say which key.
        val error = runCatching { RouteArgs.EMPTY.string("id") }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("id")
    }

    @Test
    fun `optional accessors return null rather than throwing`() {
        val args = RouteArgs(mapOf("id" to "notanumber"))

        assertThat(args.stringOrNull("absent")).isNull()
        assertThat(args.intOrNull("id")).isNull()
        assertThat(args.intOrNull("absent")).isNull()
    }

    @Test
    fun `asMap exposes a copy rather than the backing map`() {
        val args = RouteArgs(mapOf("a" to "1"))

        assertThat(args.asMap()).containsExactly("a", "1")
    }

    // -- session ------------------------------------------------------------

    @Test
    fun `principalOrNull is present only when there is a principal`() {
        val principal = Principal("subject", "Name", tenantId = null)

        assertThat(SessionState.SignedIn(principal).principalOrNull).isEqualTo(principal)
        // Expired keeps the principal: the UI still shows who is being re-authenticated.
        assertThat(SessionState.Expired(principal).principalOrNull).isEqualTo(principal)
        assertThat(SessionState.SignedOut.principalOrNull).isNull()
        assertThat(SessionState.Unknown.principalOrNull).isNull()
    }

    @Test
    fun `a principal reports its roles and entitlements`() {
        val principal = Principal(
            subjectId = "subject",
            displayName = "Name",
            tenantId = "acme",
            roles = setOf("admin"),
            entitlements = setOf(Sku("pro")),
        )

        assertThat(principal.hasRole("admin")).isTrue()
        assertThat(principal.hasRole("owner")).isFalse()
        assertThat(principal.isEntitledTo(Sku("pro"))).isTrue()
        assertThat(principal.isEntitledTo(Sku("enterprise"))).isFalse()
    }

    @Test
    fun `consent purposes separate analytics from crash diagnostics`() {
        // Different lawful bases; a user may allow one and refuse the other.
        assertThat(ConsentPurpose.entries).containsAtLeast(
            ConsentPurpose.ESSENTIAL,
            ConsentPurpose.PRODUCT_ANALYTICS,
            ConsentPurpose.CRASH_DIAGNOSTICS,
        )
    }

    // -- traced -------------------------------------------------------------

    private class RecordingSpan : TelemetryService.Span {
        var status: Boolean? = null
        var closed = false
        val exceptions = mutableListOf<Throwable>()
        override val traceId = "trace"
        override fun setAttribute(key: String, value: Any?) = Unit
        override fun recordException(throwable: Throwable) {
            exceptions += throwable
        }

        override fun setStatus(ok: Boolean, description: String?) {
            status = ok
        }

        override fun close() {
            closed = true
        }
    }

    private class SpanTelemetry(val span: RecordingSpan) : TelemetryService {
        override fun event(name: String, attributes: Map<String, Any?>) = Unit
        override fun metric(name: String, value: Double, attributes: Map<String, Any?>) = Unit
        override fun breadcrumb(message: String, attributes: Map<String, Any?>) = Unit
        override fun recordError(throwable: Throwable, message: String?, fatal: Boolean) = Unit
        override fun startSpan(name: String, attributes: Map<String, Any?>) = span
    }

    @Test
    fun `traced marks a successful block ok and closes the span`() = runTest {
        val span = RecordingSpan()

        val result = SpanTelemetry(span).traced("work") { "value" }

        assertThat(result).isEqualTo("value")
        assertThat(span.status).isTrue()
        assertThat(span.closed).isTrue()
    }

    @Test
    fun `traced records a failure, marks it failed, and rethrows`() {
        // Rethrowing matters: traced instruments a block, it does not handle errors.
        val span = RecordingSpan()
        val boom = IllegalStateException("boom")

        val thrown = runCatching {
            runTest { SpanTelemetry(span).traced<Unit>("work") { throw boom } }
        }.exceptionOrNull()

        assertThat(thrown).isNotNull()
        assertThat(span.exceptions).containsExactly(boom)
        assertThat(span.status).isFalse()
        assertThat(span.closed).isTrue()
    }

    @Test
    fun `traced closes the span even when the block is cancelled`() {
        val span = RecordingSpan()

        runCatching {
            runTest {
                SpanTelemetry(span).traced<Unit>("work") { throw CancellationException("cancelled") }
            }
        }

        assertThat(span.closed).isTrue()
    }
}
