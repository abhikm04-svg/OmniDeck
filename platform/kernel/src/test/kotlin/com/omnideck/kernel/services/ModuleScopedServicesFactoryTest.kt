package com.omnideck.kernel.services

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.omnideck.core.DispatcherProvider
import com.omnideck.kernel.lifecycle.manifest
import com.omnideck.kernel.lifecycle.moduleId
import com.omnideck.kernel.registry.CapabilityRegistryImpl
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.CapabilityNotGrantedException
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.SemVer
import com.omnideck.testing.FakeAuthService
import com.omnideck.testing.FakeBillingService
import com.omnideck.testing.FakeConsentService
import com.omnideck.testing.FakeEventBus
import com.omnideck.testing.FakeFeatureFlagService
import com.omnideck.testing.FakeRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.inject.Provider

/**
 * The zero-trust boundary of architecture.md §6.3 and §12.2.
 *
 * The claim is structural: a module cannot reach a capability it did not declare,
 * because it never receives the object that would let it. These tests exist to stop
 * that degrading into a convention — an accidental `get()` that skips `gated()` would
 * silently hand every module the full kernel.
 */
@RunWith(RobolectricTestRunner::class)
class ModuleScopedServicesFactoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun initialiseWorkManager() {
        // The Shell strips WorkManagerInitializer from the manifest and initialises
        // WorkManager itself, so nothing stands it up under Robolectric and any
        // capability constructing a WorkSchedulerImpl would throw. The test helper is
        // re-entrant across Robolectric's per-test Application; calling
        // WorkManager.initialize directly is not, and leaks an "already initialized"
        // exception into the next test.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val unconfined = Dispatchers.Unconfined
    }

    private val permissionRequester = PermissionRequester { _, _ ->
        com.omnideck.sdk.capability.PermissionBroker.PermissionResult.GRANTED
    }

    private fun factory() = ModuleScopedServicesFactory(
        context = context,
        dispatchers = dispatchers,
        telemetryHub = TelemetryHub(),
        networkEngine = NetworkEngine(context),
        capabilities = CapabilityRegistryImpl(),
        flags = FakeFeatureFlagService(),
        events = FakeEventBus(),
        consent = FakeConsentService(),
        auth = FakeAuthService(),
        billing = FakeBillingService(),
        routerProvider = Provider { FakeRouter() },
        permissionRequester = Provider { permissionRequester },
        hostSdkVersion = SemVer(1, 0, 0),
    )

    // -- capability gating --------------------------------------------------

    @Test
    fun `a declared capability is handed over`() {
        val services = factory().create(manifest(required = setOf(CapabilityId.STORAGE)))

        assertThat(services.storage).isNotNull()
    }

    @Test
    fun `an undeclared capability is refused`() {
        val services = factory().create(manifest(required = setOf(CapabilityId.TELEMETRY)))

        val error = runCatching { services.storage }.exceptionOrNull()

        assertThat(error).isInstanceOf(CapabilityNotGrantedException::class.java)
    }

    @Test
    fun `an optional capability counts as declared`() {
        val services = factory().create(
            manifest(required = setOf(CapabilityId.TELEMETRY))
                .copy(optionalCapabilities = setOf(CapabilityId.NETWORK)),
        )

        assertThat(services.network).isNotNull()
    }

    @Test
    fun `every gated capability refuses when undeclared`() {
        // Sweeps the whole surface: one accessor built without gated() would be an
        // undetectable hole in the boundary.
        val services = factory().create(manifest(required = setOf(CapabilityId.TELEMETRY)))

        val refusals = listOf<Pair<String, () -> Any?>>(
            "storage" to { services.storage },
            "secureStore" to { services.secureStore },
            "network" to { services.network },
            "work" to { services.work },
            "permissions" to { services.permissions },
            "notifications" to { services.notifications },
            "media" to { services.media },
            "auth" to { services.auth },
            "billing" to { services.billing },
        ).map { (name, access) ->
            name to runCatching { access() }.exceptionOrNull()
        }

        val notRefused = refusals.filterNot { it.second is CapabilityNotGrantedException }.map { it.first }
        assertThat(notRefused).isEmpty()
    }

    @Test
    fun `every declared capability is actually built, not merely permitted`() {
        // The mirror of the refusal sweep above, and the half that catches the
        // opposite failure: an accessor that passes the gate and then blows up while
        // constructing the service reads to a module as a working capability right up
        // until first use, which is the worst moment to find out.
        //
        // secureStore is absent on purpose — the Android Keystore has no Robolectric
        // implementation, so building it here would fail for a reason that has nothing
        // to do with the boundary. It is covered by SecureStoreImplTest on a device.
        val services = factory().create(manifest(required = CapabilityId.KERNEL_PROVIDED))

        val built = listOf<Pair<String, () -> Any?>>(
            "storage" to { services.storage },
            "network" to { services.network },
            "work" to { services.work },
            "permissions" to { services.permissions },
            "notifications" to { services.notifications },
            "media" to { services.media },
            "auth" to { services.auth },
            "billing" to { services.billing },
        ).map { (name, access) -> name to runCatching(access) }

        val broken = built.filter { it.second.isFailure }.map { "${it.first}: ${it.second.exceptionOrNull()}" }
        assertThat(broken).isEmpty()
        assertThat(built.map { it.second.getOrNull() }).doesNotContain(null)
    }

    @Test
    fun `ungated capabilities are always available`() {
        // Telemetry, flags, events, consent, locale and router are unconditional: a
        // module that cannot report a crash or read a kill switch is worse than one
        // with slightly broader reach.
        val services = factory().create(manifest(required = setOf(CapabilityId.TELEMETRY)))

        assertThat(services.telemetry).isNotNull()
        assertThat(services.flags).isNotNull()
        assertThat(services.events).isNotNull()
        assertThat(services.consent).isNotNull()
        assertThat(services.locale).isNotNull()
        assertThat(services.router).isNotNull()
    }

    @Test
    fun `grants are re-checked on every access, not cached from load time`() {
        // A module's grants can change server-side between activations; a check that
        // runs once is a check that is eventually wrong.
        val services = factory().create(manifest(required = setOf(CapabilityId.TELEMETRY)))

        repeat(3) {
            assertThat(runCatching { services.storage }.exceptionOrNull())
                .isInstanceOf(CapabilityNotGrantedException::class.java)
        }
    }

    // -- per-module scoping -------------------------------------------------

    @Test
    fun `each module gets its own services instance`() {
        val factory = factory()

        val notes = factory.create(manifest(id = moduleId("com.omnideck.notes")))
        val finance = factory.create(manifest(id = moduleId("com.omnideck.finance")))

        assertThat(notes).isNotSameInstanceAs(finance)
        assertThat(notes.moduleId).isEqualTo(moduleId("com.omnideck.notes"))
        assertThat(finance.moduleId).isEqualTo(moduleId("com.omnideck.finance"))
    }

    @Test
    fun `the same module gets a cached instance`() {
        val factory = factory()
        val m = manifest()

        assertThat(factory.create(m)).isSameInstanceAs(factory.create(m))
    }

    @Test
    fun `services report the host SDK version`() {
        val services = factory().create(manifest())

        assertThat(services.sdkVersion).isEqualTo(SemVer(1, 0, 0))
    }

    @Test
    fun `storage handed to a module is namespaced to it`() {
        val factory = factory()
        val notes = factory.create(
            manifest(id = moduleId("com.omnideck.notes"), required = setOf(CapabilityId.STORAGE)),
        )
        val finance = factory.create(
            manifest(id = moduleId("com.omnideck.finance"), required = setOf(CapabilityId.STORAGE)),
        )

        assertThat(notes.storage.filesDir().canonicalPath)
            .isNotEqualTo(finance.storage.filesDir().canonicalPath)
    }

    // -- purge --------------------------------------------------------------

    @Test
    fun `purging ALL erases the module's storage and drops its cached services`() = runTest {
        val factory = factory()
        val m = manifest(required = setOf(CapabilityId.STORAGE))
        val services = factory.create(m)
        services.storage.filesDir().resolve("data").writeText("x")

        factory.purge(m.id, PurgeScope.ALL)

        assertThat(factory.create(m)).isNotSameInstanceAs(services)
        assertThat(services.storage.usageBytes()).isEqualTo(0)
    }

    @Test
    fun `purging CACHE keeps the module's services instance`() = runTest {
        val factory = factory()
        val m = manifest(required = setOf(CapabilityId.STORAGE))
        val services = factory.create(m)

        factory.purge(m.id, PurgeScope.CACHE)

        assertThat(factory.create(m)).isSameInstanceAs(services)
    }

    @Test
    fun `purge works for a module that never requested storage`() = runTest {
        // Erasure must not depend on the module having touched its storage.
        val factory = factory()

        factory.purge(moduleId("com.omnideck.neverran"), PurgeScope.ALL)
    }

    @Test
    fun `cancelWork is safe for a module with no scheduled work`() {
        factory().cancelWork(moduleId("com.omnideck.idle"))
    }
}
