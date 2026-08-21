package com.omnideck.kernel.registry

import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.ModuleId
import org.junit.Test

/**
 * Cross-module service exchange (architecture.md §10.3).
 *
 * The registry is the *only* sanctioned way one module reaches another, so its
 * refusals matter as much as its successes: a module must not be able to take over a
 * kernel capability or silently displace another module's, and a consumer must get a
 * null rather than a crash when the provider is absent — that null is what lets a
 * module be removed from the platform without breaking everything downstream.
 */
class CapabilityRegistryImplTest {

    private val notes = ModuleId("com.omnideck.notes")
    private val finance = ModuleId("com.omnideck.finance")
    private val exporter = CapabilityId("omnideck.notes.exporter")

    private interface Exporter {
        fun export(): String
    }

    private class RealExporter(private val value: String = "exported") : Exporter {
        override fun export() = value
    }

    // -- registration and resolution ---------------------------------------

    @Test
    fun `a registered capability resolves for another module`() {
        val registry = CapabilityRegistryImpl()
        registry.scopedTo(notes).register(exporter, Exporter::class.java) { RealExporter() }

        val resolved = registry.scopedTo(finance).resolve(exporter, Exporter::class.java)

        assertThat(resolved?.export()).isEqualTo("exported")
    }

    @Test
    fun `an absent capability resolves to null rather than throwing`() {
        // Graceful degradation is the whole point: consumers keep working when a
        // provider is not installed, quarantined, or simply not shipped yet.
        val registry = CapabilityRegistryImpl()

        assertThat(registry.scopedTo(finance).resolve(exporter, Exporter::class.java)).isNull()
        assertThat(registry.scopedTo(finance).isAvailable(exporter)).isFalse()
    }

    @Test
    fun `a capability requested as the wrong type resolves to null`() {
        // Guards the unchecked cast: a type mismatch must not become a
        // ClassCastException in unrelated consumer code.
        val registry = CapabilityRegistryImpl()
        registry.scopedTo(notes).register(exporter, Exporter::class.java) { RealExporter() }

        assertThat(registry.scopedTo(finance).resolve(exporter, String::class.java)).isNull()
    }

    @Test
    fun `providers are lazy until first resolved`() {
        // A capability nobody uses should cost nothing at registration time.
        val registry = CapabilityRegistryImpl()
        var constructed = 0
        registry.scopedTo(notes).register(exporter, Exporter::class.java) {
            constructed++
            RealExporter()
        }
        assertThat(constructed).isEqualTo(0)

        registry.scopedTo(finance).resolve(exporter, Exporter::class.java)

        assertThat(constructed).isEqualTo(1)
    }

    @Test
    fun `a resolved instance is reused across consumers`() {
        val registry = CapabilityRegistryImpl()
        var constructed = 0
        registry.scopedTo(notes).register(exporter, Exporter::class.java) {
            constructed++
            RealExporter()
        }

        registry.scopedTo(finance).resolve(exporter, Exporter::class.java)
        registry.scopedTo(finance).resolve(exporter, Exporter::class.java)

        assertThat(constructed).isEqualTo(1)
    }

    // -- refusals -----------------------------------------------------------

    @Test
    fun `a module cannot override a kernel capability`() {
        // Otherwise a module could shadow storage or auth for every other module.
        val registry = CapabilityRegistryImpl()

        val error = runCatching {
            registry.scopedTo(notes).register(CapabilityId.STORAGE, Exporter::class.java) { RealExporter() }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("kernel capability")
    }

    @Test
    fun `a second module cannot take over an existing capability`() {
        val registry = CapabilityRegistryImpl()
        registry.scopedTo(notes).register(exporter, Exporter::class.java) { RealExporter("notes") }

        val error = runCatching {
            registry.scopedTo(finance).register(exporter, Exporter::class.java) { RealExporter("finance") }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("already provided by")
    }

    @Test
    fun `the owning module may re-register its own capability`() {
        // Re-activation after a suspend must not be treated as a takeover attempt.
        val registry = CapabilityRegistryImpl()
        val owner = registry.scopedTo(notes)
        owner.register(exporter, Exporter::class.java) { RealExporter("first") }

        owner.register(exporter, Exporter::class.java) { RealExporter("second") }

        assertThat(registry.scopedTo(finance).resolve(exporter, Exporter::class.java)?.export())
            .isEqualTo("second")
    }

    // -- removal ------------------------------------------------------------

    @Test
    fun `removing a module withdraws its capabilities`() {
        // Quarantine calls this; a capability outliving its owner would let consumers
        // keep calling into a module the platform has disabled.
        val registry = CapabilityRegistryImpl()
        registry.scopedTo(notes).register(exporter, Exporter::class.java) { RealExporter() }

        registry.removeAll(notes)

        assertThat(registry.available()).isEmpty()
        assertThat(registry.scopedTo(finance).resolve(exporter, Exporter::class.java)).isNull()
    }

    @Test
    fun `removing one module leaves another's capabilities intact`() {
        val registry = CapabilityRegistryImpl()
        val financeCapability = CapabilityId("omnideck.finance.rates")
        registry.scopedTo(notes).register(exporter, Exporter::class.java) { RealExporter() }
        registry.scopedTo(finance).register(financeCapability, Exporter::class.java) { RealExporter() }

        registry.removeAll(notes)

        assertThat(registry.available()).containsExactly(financeCapability)
    }

    @Test
    fun `a capability re-registered after removal is constructed afresh`() {
        // The cached instance must not survive its owner being unloaded.
        val registry = CapabilityRegistryImpl()
        registry.scopedTo(notes).register(exporter, Exporter::class.java) { RealExporter("first") }
        registry.scopedTo(finance).resolve(exporter, Exporter::class.java)

        registry.removeAll(notes)
        registry.scopedTo(notes).register(exporter, Exporter::class.java) { RealExporter("second") }

        assertThat(registry.scopedTo(finance).resolve(exporter, Exporter::class.java)?.export())
            .isEqualTo("second")
    }

    // -- observability ------------------------------------------------------

    @Test
    fun `the available set tracks registration and removal`() {
        val registry = CapabilityRegistryImpl()
        assertThat(registry.available.value).isEmpty()

        registry.scopedTo(notes).register(exporter, Exporter::class.java) { RealExporter() }
        assertThat(registry.available.value).containsExactly(exporter)

        registry.removeAll(notes)
        assertThat(registry.available.value).isEmpty()
    }

    @Test
    fun `isAvailable reports registration without constructing the provider`() {
        val registry = CapabilityRegistryImpl()
        var constructed = 0
        registry.scopedTo(notes).register(exporter, Exporter::class.java) {
            constructed++
            RealExporter()
        }

        assertThat(registry.scopedTo(finance).isAvailable(exporter)).isTrue()
        assertThat(constructed).isEqualTo(0)
    }
}
