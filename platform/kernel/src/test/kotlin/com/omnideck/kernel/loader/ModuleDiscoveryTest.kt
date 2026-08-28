package com.omnideck.kernel.loader

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.DestinationRegistry
import com.omnideck.sdk.InstallProgress
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleInitResult
import com.omnideck.sdk.ModuleManifest
import com.omnideck.sdk.OmniModule
import com.omnideck.sdk.PlatformServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Discovery and bundled loading — the path that makes goal G1 true.
 *
 * A module is added by creating a directory; the convention plugin writes a
 * descriptor into assets, and this reads it back. Nothing here may require an edit to
 * Shell source, which is why discovery is asset-driven rather than code-generated
 * against a known list.
 */
@RunWith(RobolectricTestRunner::class)
class ModuleDiscoveryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // -- descriptors from assets -------------------------------------------

    @Test
    fun `an empty assets directory yields no modules rather than failing`() = runTest {
        // A Shell with no modules installed is a valid state, not an error.
        val source = AssetModuleDescriptorSource(context, Dispatchers.Unconfined)

        assertThat(source.descriptors()).isEmpty()
    }

    // -- the descriptor contract (OD-301) -----------------------------------
    //
    // The delivery kind decides which ModuleProvider handles a module, and the
    // bundled provider reports every module installed. Reading it wrong therefore
    // does not fail here — it fails on a device, as a missing class, in a release
    // build. These are cheap and they are the only place that decision is checked.

    private fun descriptorProps(vararg pairs: Pair<String, String>) = java.util.Properties().apply {
        pairs.forEach { (key, value) -> setProperty(key, value) }
    }

    @Test
    fun `the delivery kind the build wrote is the one the kernel reads`() {
        val descriptor = moduleDescriptorFrom(
            descriptorProps("id" to "com.omnideck.notes", "delivery" to "FEATURE_SPLIT"),
        )

        assertThat(descriptor.delivery).isEqualTo(DeliveryKind.FEATURE_SPLIT)
        assertThat(descriptor.id).isEqualTo(ModuleId("com.omnideck.notes"))
    }

    @Test
    fun `a descriptor with no delivery line is treated as bundled`() {
        // Descriptors generated before Phase 3 have no delivery line, and a module in
        // the base APK is exactly what they described.
        val descriptor = moduleDescriptorFrom(descriptorProps("id" to "com.omnideck.notes"))

        assertThat(descriptor.delivery).isEqualTo(DeliveryKind.BUNDLED)
    }

    @Test
    fun `an unrecognised delivery kind falls back to bundled rather than failing discovery`() {
        // Written by a newer build than this Shell. Bundled is the only safe reading:
        // it is the one delivery kind that needs no capability this Shell might lack,
        // and dropping the module entirely would make it invisible with no explanation.
        val descriptor = moduleDescriptorFrom(
            descriptorProps("id" to "com.omnideck.notes", "delivery" to "QUANTUM_ENTANGLEMENT"),
        )

        assertThat(descriptor.delivery).isEqualTo(DeliveryKind.BUNDLED)
    }

    @Test
    fun `the entry point defaults to the module's own namespace`() {
        val descriptor = moduleDescriptorFrom(descriptorProps("id" to "com.omnideck.notes"))

        assertThat(descriptor.entryPointClass).isEqualTo("com.omnideck.notes.ModuleEntryPoint")
    }

    // -- bundled loading ----------------------------------------------------

    class StubEntryPoint : OmniModule {
        override val manifest: ModuleManifest get() = error("not needed for loading")
        override suspend fun initialize(services: PlatformServices) = ModuleInitResult.Ready
        override fun registerDestinations(registry: DestinationRegistry) = Unit
    }

    class NotAModule

    class NoPublicConstructor private constructor() {
        companion object {
            fun create() = NoPublicConstructor()
        }
    }

    private val provider = BundledModuleProvider(Dispatchers.Unconfined)

    private fun descriptor(klass: Class<*>) = ModuleDescriptor(
        id = ModuleId("com.omnideck.notes"),
        entryPointClass = klass.name,
        delivery = DeliveryKind.BUNDLED,
    )

    @Test
    fun `bundled modules are always installed and need no download`() = runTest {
        val id = ModuleId("com.omnideck.notes")

        assertThat(provider.handles).isEqualTo(DeliveryKind.BUNDLED)
        assertThat(provider.isInstalled(id)).isTrue()
        assertThat(provider.install(id).first()).isEqualTo(InstallProgress.Installed)
    }

    @Test
    fun `uninstalling a bundled module is a no-op`() = runTest {
        // It ships inside the base APK; there is nothing Play could reclaim.
        provider.uninstall(ModuleId("com.omnideck.notes"))

        assertThat(provider.isInstalled(ModuleId("com.omnideck.notes"))).isTrue()
    }

    @Test
    fun `the entry point is instantiated reflectively`() = runTest {
        val module = provider.load(descriptor(StubEntryPoint::class.java))

        assertThat(module).isInstanceOf(StubEntryPoint::class.java)
    }

    @Test
    fun `a class that is not an OmniModule is rejected with a pointed message`() = runTest {
        // The usual cause is a module that forgot to depend on the SDK, so the
        // message names that rather than just reporting a cast failure.
        val error = runCatching { provider.load(descriptor(NotAModule::class.java)) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("does not implement OmniModule")
        assertThat(error).hasMessageThat().contains("omnideck-sdk")
    }

    @Test
    fun `a missing entry point class fails loudly`() = runTest {
        val descriptor = ModuleDescriptor(
            id = ModuleId("com.omnideck.notes"),
            entryPointClass = "com.omnideck.nope.Missing",
            delivery = DeliveryKind.BUNDLED,
        )

        val error = runCatching { provider.load(descriptor) }.exceptionOrNull()

        assertThat(error).isInstanceOf(ClassNotFoundException::class.java)
    }

    @Test
    fun `an entry point without a public no-arg constructor fails`() = runTest {
        // The contract requires one; the convention plugin's keep rule assumes it.
        val error = runCatching {
            provider.load(descriptor(NoPublicConstructor::class.java))
        }.exceptionOrNull()

        assertThat(error).isNotNull()
    }
}
