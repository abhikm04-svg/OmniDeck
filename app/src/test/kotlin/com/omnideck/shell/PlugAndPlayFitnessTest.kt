package com.omnideck.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.omnideck.generated.GeneratedModuleRegistry
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.ModuleId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Properties

/**
 * The plug-and-play fitness test (OD-212, architecture.md §2 goal G1) at unit level.
 *
 * It answers one question mechanically: **would a module dropped into `modules/` be
 * discovered, constructible and navigable without anyone editing the Shell?** The
 * instrumented half of OD-212 proves it renders on a device; this half proves the
 * three build-time mechanisms behind it agree with each other, on every CI run, in
 * seconds.
 *
 * Nothing here names a module. Every assertion is over whatever happens to be in the
 * build, so it keeps working — and keeps meaning something — as modules come and go.
 *
 * It also has to keep meaning something in **both** delivery modes (OD-301). Run with
 * `-Pomnideck.dynamicModules=<every module>` there are no bundled modules at all, so
 * assertions phrased as "the registry is not empty" would either fail or, worse, pass
 * vacuously. They are phrased against the descriptors instead: those are in the base
 * APK either way, and the registry is expected to hold exactly the bundled subset.
 */
@RunWith(RobolectricTestRunner::class)
class PlugAndPlayFitnessTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `every module in the build ships a runtime discovery descriptor`() {
        val descriptors = descriptors()

        assertThat(descriptors).isNotEmpty()
        descriptors.forEach { (fileName, props) ->
            assertThat(props.getProperty("id")).isNotNull()
            assertThat(props.getProperty("entryPoint")).isEqualTo("${props.getProperty("id")}.ModuleEntryPoint")
            // One file per id, so asset merging across modules cannot collide.
            assertThat(fileName).isEqualTo("${props.getProperty("id")}.properties")
        }
    }

    /**
     * The descriptor of an **on-demand** module has to be in the base APK (OD-301).
     *
     * A dynamic feature's assets are packaged into its split, so a descriptor left
     * there arrives only after the download it exists to trigger — the module would
     * never be advertised, never appear in the Catalog, and never be installable. This
     * reads the merged base assets, so a descriptor that regressed into the split is
     * simply absent here.
     */
    @Test
    fun `every discovered module declares a delivery kind the kernel understands`() {
        val kinds = descriptors().map { (fileName, props) -> fileName to props.getProperty("delivery") }

        assertThat(kinds).isNotEmpty()
        kinds.forEach { (fileName, delivery) ->
            assertWithMessage("%s declares no delivery kind", fileName).that(delivery).isNotNull()
            assertWithMessage("%s declares an unknown delivery kind", fileName)
                .that(DeliveryKind.entries.map { it.name })
                .contains(delivery)
        }
    }

    /**
     * The generated registry is the *bundled* half, not the whole build.
     *
     * A split's code does not exist when the registry is generated, so it cannot be
     * in it — and must not be, because a factory there would let the Shell try to
     * construct a class the device has not downloaded. Split modules load
     * reflectively, after `SplitCompat` has patched the class loader.
     */
    @Test
    fun `the generated registry covers exactly the modules that are bundled into the base APK`() {
        val bundled = descriptorsWithDelivery(DeliveryKind.BUNDLED)
        val onDemand = descriptorsWithDelivery(DeliveryKind.FEATURE_SPLIT)

        assertThat(GeneratedModuleRegistry.factories.keys).isEqualTo(bundled)
        assertThat(GeneratedModuleRegistry.factories.keys).containsNoneIn(onDemand)
    }

    @Test
    fun `every registered factory constructs a module that agrees about its own identity`() {
        GeneratedModuleRegistry.factories.forEach { (id, factory) ->
            val module = factory()
            // The id in the descriptor, the id in the generated registry and the id
            // the module declares in its manifest are produced by three separate
            // mechanisms. A disagreement between them is how storage, telemetry and
            // entitlements silently attach to the wrong module.
            assertThat(module.manifest.id).isEqualTo(ModuleId(id))
        }
    }

    @Test
    fun `every module's entry route belongs to it and its manifest is self-consistent`() {
        GeneratedModuleRegistry.factories.values.forEach { factory ->
            val manifest = factory().manifest

            assertThat(manifest.entryRoute.host).isEqualTo(manifest.id.shortId)
            assertThat(manifest.requiredCapabilities).isNotEmpty()
            // Every module declares an owner — architecture.md §18's fitness function.
            // Without one a crash has no team to route to.
            assertThat(manifest.owner.value).isNotEmpty()
            assertThat(manifest.dataCategories).isNotEmpty()
        }
    }

    @Test
    fun `no two modules claim the same route`() {
        val patterns = GeneratedModuleRegistry.factories.values
            .flatMap { factory ->
                val manifest = factory().manifest
                manifest.deepLinks.map { it.pattern } + manifest.entryRoute.uri
            }

        assertThat(patterns).containsNoDuplicates()
    }

    /**
     * Reads the descriptors exactly the way `AssetModuleDescriptorSource` does at
     * runtime — from the merged assets, not from a build directory. A test that read
     * the generator's output instead would pass while asset merging was broken.
     */
    private fun descriptors(): List<Pair<String, Properties>> {
        val dir = "omnideck/modules"
        return context.assets.list(dir).orEmpty().map { fileName ->
            fileName to Properties().apply { context.assets.open("$dir/$fileName").use(::load) }
        }
    }

    /** Module ids whose descriptor declares [kind]. Absent means bundled, as at runtime. */
    private fun descriptorsWithDelivery(kind: DeliveryKind): Set<String> = descriptors()
        .filter { (_, props) ->
            (props.getProperty("delivery") ?: DeliveryKind.BUNDLED.name) == kind.name
        }
        .map { (_, props) -> props.getProperty("id") }
        .toSet()
}
