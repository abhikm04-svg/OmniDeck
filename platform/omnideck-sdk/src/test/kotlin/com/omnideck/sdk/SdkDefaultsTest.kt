package com.omnideck.sdk

import androidx.compose.runtime.Composable
import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.capability.BillingService
import com.omnideck.sdk.capability.NotificationService
import com.omnideck.sdk.capability.PermissionBroker
import com.omnideck.sdk.capability.WorkScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration

/**
 * The spec types a module fills in, and the interface defaults it inherits.
 *
 * These are the parts of the contract a module author touches directly, so their
 * defaults are effectively platform policy: every module gets them without choosing,
 * and changing one silently changes behaviour across the estate.
 */
class SdkDefaultsTest {

    // -- work ---------------------------------------------------------------

    @Test
    fun `work requires network by default and nothing else`() {
        // Network-by-default matches what background work almost always needs;
        // charging and unmetered are opt-in because they can delay work for hours.
        val spec = WorkScheduler.WorkSpec(name = "sync", worker = Any::class.java)

        assertThat(spec.requiresNetwork).isTrue()
        assertThat(spec.requiresUnmetered).isFalse()
        assertThat(spec.requiresCharging).isFalse()
        assertThat(spec.expedited).isFalse()
        assertThat(spec.initialDelay).isEqualTo(Duration.ZERO)
        assertThat(spec.input).isEmpty()
    }

    @Test
    fun `work constraints can be tightened per spec`() {
        val spec = WorkScheduler.WorkSpec(
            name = "backup",
            worker = Any::class.java,
            requiresUnmetered = true,
            requiresCharging = true,
            initialDelay = Duration.ofMinutes(15),
            input = mapOf("folder" to "photos"),
        )

        assertThat(spec.requiresUnmetered).isTrue()
        assertThat(spec.requiresCharging).isTrue()
        assertThat(spec.initialDelay).isEqualTo(Duration.ofMinutes(15))
        assertThat(spec.input).containsExactly("folder", "photos")
    }

    @Test
    fun `work status covers the states a module can observe`() {
        assertThat(WorkScheduler.WorkStatus.entries).containsAtLeast(
            WorkScheduler.WorkStatus.ENQUEUED,
            WorkScheduler.WorkStatus.RUNNING,
            WorkScheduler.WorkStatus.SUCCEEDED,
            WorkScheduler.WorkStatus.FAILED,
            WorkScheduler.WorkStatus.CANCELLED,
        )
    }

    // -- notifications ------------------------------------------------------

    @Test
    fun `a notification defaults to non-ongoing at default importance`() {
        // Ongoing notifications cannot be dismissed, so that must be opted into.
        val spec = NotificationService.NotificationSpec(
            id = 1,
            channelId = "general",
            title = "Title",
            body = "Body",
        )

        assertThat(spec.ongoing).isFalse()
        assertThat(spec.importance).isEqualTo(NotificationService.Importance.DEFAULT)
        assertThat(spec.route).isNull()
    }

    @Test
    fun `a notification can carry a route so tapping it navigates`() {
        val spec = NotificationService.NotificationSpec(
            id = 1,
            channelId = "general",
            title = "Title",
            body = "Body",
            route = Route("omnideck://notes/detail/7"),
        )

        assertThat(spec.route?.host).isEqualTo("notes")
    }

    @Test
    fun `notification importance runs from min to high`() {
        assertThat(NotificationService.Importance.entries).containsExactly(
            NotificationService.Importance.MIN,
            NotificationService.Importance.LOW,
            NotificationService.Importance.DEFAULT,
            NotificationService.Importance.HIGH,
        ).inOrder()
    }

    // -- permissions --------------------------------------------------------

    @Test
    fun `a rationale carries the purpose used in the audit trail`() {
        val rationale = PermissionBroker.Rationale(
            title = "Camera",
            message = "Needed to scan documents",
            purpose = "document scanning",
        )

        assertThat(rationale.purpose).isEqualTo("document scanning")
    }

    @Test
    fun `permission results distinguish the four outcomes a module must handle`() {
        // NOT_DECLARED is the platform refusing, not the user — a module that treats
        // it as a denial would prompt the user for something it never declared.
        assertThat(PermissionBroker.PermissionResult.entries).containsExactly(
            PermissionBroker.PermissionResult.GRANTED,
            PermissionBroker.PermissionResult.DENIED,
            PermissionBroker.PermissionResult.PERMANENTLY_DENIED,
            PermissionBroker.PermissionResult.NOT_DECLARED,
        )
    }

    // -- billing ------------------------------------------------------------

    @Test
    fun `a product carries both the formatted price and its minor units`() {
        // Formatted for display, minor units for arithmetic — a module doing maths on
        // the formatted string would break in every locale but its own.
        val product = BillingService.Product(
            sku = Sku("pro"),
            title = "Pro",
            description = "Everything",
            formattedPrice = "$4.99",
            priceMinorUnits = 499,
            currencyCode = "USD",
            subscription = true,
        )

        assertThat(product.priceMinorUnits).isEqualTo(499)
        assertThat(product.formattedPrice).isEqualTo("$4.99")
        assertThat(product.subscription).isTrue()
    }

    // -- OmniModule defaults ------------------------------------------------

    /** The smallest legal module: only the two required members. */
    private class MinimalModule : OmniModule {
        override val manifest: ModuleManifest get() = error("not needed")
        override suspend fun initialize(services: PlatformServices) = ModuleInitResult.Ready
        override fun registerDestinations(registry: DestinationRegistry) = Unit
    }

    @Test
    fun `a module need not implement capabilities, suspend or purge`() {
        // Every optional member has a default, so the smallest module is two
        // overrides. If a default were removed, existing modules would stop compiling.
        val module = MinimalModule()

        runTest {
            module.registerCapabilities(NoopCapabilityRegistry)
            module.suspend(SuspendReason.MEMORY_PRESSURE)
            module.purge(PurgeScope.ALL)
        }
    }

    private object NoopCapabilityRegistry : CapabilityRegistry {
        override fun <T : Any> register(id: CapabilityId, type: Class<T>, provider: () -> T) = Unit
        override fun <T : Any> resolve(id: CapabilityId, type: Class<T>): T? = null
        override fun isAvailable(id: CapabilityId) = false
    }

    // -- DestinationRegistry defaults ---------------------------------------

    private class RecordingRegistry : DestinationRegistry {
        val patterns = mutableListOf<String>()
        var degradedRegistered = false

        override fun destination(pattern: String, content: @Composable (RouteArgs) -> Unit) {
            patterns += pattern
        }

        override fun degradedFallback(content: @Composable (reason: String) -> Unit) {
            degradedRegistered = true
        }
    }

    @Test
    fun `a destination whose content ignores its arguments needs no parameter list`() {
        val registry = RecordingRegistry()

        // The shape every module actually writes. It used to be ambiguous against an
        // argument-free overload, which is why that overload no longer exists
        // (OD-209 found it on the first destination ever registered).
        registry.destination("omnideck://notes/home") { }

        assertThat(registry.patterns).containsExactly("omnideck://notes/home")
    }

    @Test
    fun `degradedFallback is optional and defaults to doing nothing`() {
        // A module that offers no fallback gets the Shell's generic one.
        val default = object : DestinationRegistry {
            override fun destination(pattern: String, content: @Composable (RouteArgs) -> Unit) = Unit
        }

        default.degradedFallback { }
    }

    @Test
    fun `a registry may override degradedFallback`() {
        val registry = RecordingRegistry()

        registry.degradedFallback { }

        assertThat(registry.degradedRegistered).isTrue()
    }
}
