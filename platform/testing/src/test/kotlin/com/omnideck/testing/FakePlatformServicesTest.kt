package com.omnideck.testing

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.Route
import com.omnideck.sdk.capability.NavResult
import com.omnideck.sdk.capability.PlatformEvent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The harness a module author actually touches. If constructing this needs ceremony,
 * or if a default throws, module tests stop getting written — which is the adoption
 * risk `:platform:testing` exists to remove.
 */
class FakePlatformServicesTest {

    @Test
    fun `constructs with no arguments and every capability usable`() = runTest {
        val services = FakePlatformServices()

        // Each of these threw UnsupportedOperationException before the fakes landed.
        assertThat(services.storage.filesDir().isDirectory).isTrue()
        assertThat(services.network.isOnline()).isTrue()
        assertThat(services.media.pickImage(false)).isEmpty()

        // And the rest respond without setup.
        assertThat(services.flags.boolean("x", true)).isTrue()
        assertThat(services.locale.languageTag).isEqualTo("en-US")
        assertThat(services.secureStore.get("nothing")).isNull()
    }

    @Test
    fun `exposes the module identity it was given`() {
        val services = FakePlatformServices(moduleId = ModuleId("com.omnideck.notes"))

        assertThat(services.moduleId).isEqualTo(ModuleId("com.omnideck.notes"))
    }

    @Test
    fun `substituted fakes are the ones handed to the module`() {
        val storage = FakeStorageService()
        val network = FakeNetworkService(baseUrl = "https://custom.test/")

        val services = FakePlatformServices(storage = storage, network = network)

        assertThat(services.storage).isSameInstanceAs(storage)
        assertThat(services.network).isSameInstanceAs(network)
    }

    // -- cross-module capabilities -----------------------------------------

    @Test
    fun `an unregistered capability resolves to null`() {
        val services = FakePlatformServices()

        assertThat(services.capability(CapabilityId.MEDIA, String::class.java)).isNull()
    }

    @Test
    fun `a provided capability resolves by id and type`() {
        val services = FakePlatformServices()
            .provideCapability(CapabilityId("omnideck.test.exporter"), "exporter-instance")

        val resolved = services.capability(CapabilityId("omnideck.test.exporter"), String::class.java)

        assertThat(resolved).isEqualTo("exporter-instance")
    }

    @Test
    fun `a capability of the wrong type resolves to null rather than crashing`() {
        // Guards the unchecked cast: a type mismatch must be a null, not a
        // ClassCastException surfacing somewhere unrelated.
        val services = FakePlatformServices()
            .provideCapability(CapabilityId("omnideck.test.exporter"), "a string")

        assertThat(services.capability(CapabilityId("omnideck.test.exporter"), Int::class.java)).isNull()
    }

    // -- recording surfaces -------------------------------------------------

    @Test
    fun `telemetry records events, metrics, breadcrumbs and errors`() {
        val services = FakePlatformServices()
        val boom = IllegalStateException("boom")

        services.telemetry.event("notes_opened", mapOf("source" to "tile"))
        services.telemetry.metric("sync_ms", 12.0)
        services.telemetry.breadcrumb("syncing")
        services.telemetry.recordError(boom)

        assertThat(services.telemetry.eventNames()).containsExactly("notes_opened")
        assertThat(services.telemetry.metrics.single().attributes["value"]).isEqualTo(12.0)
        assertThat(services.telemetry.breadcrumbs).containsExactly("syncing")
        assertThat(services.telemetry.errors).containsExactly(boom)
    }

    @Test
    fun `a span records exceptions against telemetry`() {
        val services = FakePlatformServices()
        val boom = IllegalStateException("inside span")

        services.telemetry.startSpan("sync").use { it.recordException(boom) }

        assertThat(services.telemetry.spans).containsExactly("sync")
        assertThat(services.telemetry.errors).containsExactly(boom)
    }

    @Test
    fun `router records navigation and returns a default result`() = runTest {
        val services = FakePlatformServices()
        val route = Route("omnideck://notes/home")

        val result = services.router.navigate(route)

        assertThat(result).isEqualTo(NavResult.Navigated(route))
        assertThat(services.router.lastRoute()).isEqualTo(route)
    }

    @Test
    fun `router navigation outcome is scriptable`() = runTest {
        val services = FakePlatformServices()
        services.router.nextResult = NavResult.Unavailable(ModuleId("com.omnideck.finance"), "quarantined")

        val result = services.router.navigate(Route("omnideck://finance/home"))

        assertThat(result).isEqualTo(NavResult.Unavailable(ModuleId("com.omnideck.finance"), "quarantined"))
    }

    @Test
    fun `event bus delivers to subscribers filtered by type`() = runTest {
        val services = FakePlatformServices()

        services.events.subscribe(PlatformEvent.SessionChanged::class.java).test {
            services.events.publish(PlatformEvent.SessionChanged(signedIn = true, userIdHash = null))
            assertThat(awaitItem().signedIn).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `published events are recorded for assertions`() {
        val services = FakePlatformServices()

        services.events.publish(PlatformEvent.SessionChanged(signedIn = false, userIdHash = null))

        assertThat(services.events.published).hasSize(1)
    }

    // -- reset --------------------------------------------------------------

    @Test
    fun `reset clears recorded interactions so a fixture can be reused`() = runTest {
        val services = FakePlatformServices()
        services.telemetry.event("e")
        services.router.navigate(Route("omnideck://notes/home"))
        services.work.enqueue(
            com.omnideck.sdk.capability.WorkScheduler.WorkSpec(name = "sync", worker = Any::class.java),
        )
        services.permissions.ensure("android.permission.CAMERA", rationale())
        services.provideCapability(CapabilityId("omnideck.test.x"), "v")

        services.reset()

        assertThat(services.telemetry.events).isEmpty()
        assertThat(services.router.navigations).isEmpty()
        assertThat(services.work.enqueued).isEmpty()
        assertThat(services.permissions.requested).isEmpty()
        assertThat(services.capability(CapabilityId("omnideck.test.x"), String::class.java)).isNull()
    }

    private fun rationale() = com.omnideck.sdk.capability.PermissionBroker.Rationale(
        title = "Camera",
        message = "Needed to scan",
        purpose = "scanning",
    )
}
