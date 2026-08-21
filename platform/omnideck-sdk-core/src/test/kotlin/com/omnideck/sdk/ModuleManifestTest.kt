package com.omnideck.sdk

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The manifest is the module's declaration of what it is and what it needs, and it is
 * shared verbatim with the backend Registry. Its `init` checks are the earliest point
 * a malformed module can be rejected — before it is published, let alone loaded — so
 * they are worth pinning precisely.
 */
class ModuleManifestTest {

    private fun manifest(
        id: ModuleId = ModuleId("com.omnideck.notes"),
        entryRoute: Route = Route("omnideck://notes/home"),
        required: Set<CapabilityId> = setOf(CapabilityId.STORAGE),
        sdkRange: SemVerRange = SemVerRange(SemVer(1, 0, 0), SemVer(2, 0, 0)),
        minHostVersionCode: Int = 1,
    ) = ModuleManifest(
        id = id,
        version = SemVer(1, 0, 0),
        displayName = LocalizedString("Notes"),
        summary = LocalizedString("Take notes"),
        category = ModuleCategory.PRODUCTIVITY,
        icon = IconRef.Symbol("note"),
        delivery = DeliveryKind.BUNDLED,
        sdkRange = sdkRange,
        minHostVersionCode = minHostVersionCode,
        entryRoute = entryRoute,
        requiredCapabilities = required,
        dataCategories = setOf(DataCategory.APP_ACTIVITY),
        owner = TeamRef("platform"),
    )

    // -- validation ---------------------------------------------------------

    @Test
    fun `a well-formed manifest is accepted`() {
        assertThat(manifest().id.shortId).isEqualTo("notes")
    }

    @Test
    fun `the entry route must belong to the declaring module`() {
        // Otherwise a module could claim another's entry point in its own manifest.
        val error = runCatching {
            manifest(entryRoute = Route("omnideck://payments/checkout"))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("must match module shortId")
    }

    @Test
    fun `a module must declare at least one capability`() {
        val error = runCatching { manifest(required = emptySet()) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("does not need the platform")
    }

    // -- compatibility ------------------------------------------------------

    @Test
    fun `a host inside the sdk range is compatible`() {
        val m = manifest(sdkRange = SemVerRange(SemVer(1, 0, 0), SemVer(2, 0, 0)))

        assertThat(m.isCompatibleWith(SemVer(1, 5, 0), hostVersionCode = 1)).isTrue()
    }

    @Test
    fun `a host below the range floor is incompatible`() {
        val m = manifest(sdkRange = SemVerRange(SemVer(2, 0, 0), null))

        assertThat(m.isCompatibleWith(SemVer(1, 9, 9), hostVersionCode = 1)).isFalse()
    }

    @Test
    fun `a host at the exclusive ceiling is incompatible`() {
        // The boundary that makes a major bump actually gate old modules.
        val m = manifest(sdkRange = SemVerRange(SemVer(1, 0, 0), SemVer(2, 0, 0)))

        assertThat(m.isCompatibleWith(SemVer(2, 0, 0), hostVersionCode = 1)).isFalse()
    }

    @Test
    fun `an older host version code is incompatible even within the sdk range`() {
        // Both gates apply: the SDK may be right while the app binary is too old to
        // contain a resource or permission the module needs.
        val m = manifest(minHostVersionCode = 50)

        assertThat(m.isCompatibleWith(SemVer(1, 0, 0), hostVersionCode = 49)).isFalse()
        assertThat(m.isCompatibleWith(SemVer(1, 0, 0), hostVersionCode = 50)).isTrue()
    }

    // -- capability satisfaction -------------------------------------------

    @Test
    fun `unsatisfiedBy reports only what is missing`() {
        val m = manifest(required = setOf(CapabilityId.STORAGE, CapabilityId.NETWORK))

        assertThat(m.unsatisfiedBy(setOf(CapabilityId.STORAGE)))
            .containsExactly(CapabilityId.NETWORK)
    }

    @Test
    fun `a fully satisfied manifest reports nothing missing`() {
        val m = manifest(required = setOf(CapabilityId.STORAGE))

        assertThat(m.unsatisfiedBy(CapabilityId.KERNEL_PROVIDED)).isEmpty()
    }

    @Test
    fun `optional capabilities are never reported as missing`() {
        // A module must start without them; that is what makes them optional.
        val m = manifest(required = setOf(CapabilityId.STORAGE))
            .copy(optionalCapabilities = setOf(CapabilityId("omnideck.absent")))

        assertThat(m.unsatisfiedBy(setOf(CapabilityId.STORAGE))).isEmpty()
    }

    // -- identifiers --------------------------------------------------------

    @Test
    fun `module ids must be reverse-DNS`() {
        listOf("notes", "com.notes", "Com.Omnideck.Notes", "com.omnideck.note-s", "")
            .forEach { raw ->
                assertThat(runCatching { ModuleId(raw) }.exceptionOrNull())
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
    }

    @Test
    fun `short id and split name derive from the last segment`() {
        val id = ModuleId("com.omnideck.finance")

        assertThat(id.shortId).isEqualTo("finance")
        assertThat(id.splitName).isEqualTo("finance")
    }

    @Test
    fun `split names replace characters Play does not allow`() {
        // Play restricts split names to letters, digits and underscore.
        assertThat(ModuleId("com.omnideck.my_module").splitName).isEqualTo("my_module")
    }

    // -- localisation -------------------------------------------------------

    @Test
    fun `a localised string falls back through language then default`() {
        val text = LocalizedString("Notes", mapOf("fr" to "Notes FR", "de-DE" to "Notizen"))

        assertThat(text.resolve("de-DE")).isEqualTo("Notizen")
        // Region-less fallback: fr-CA has no entry, but fr does.
        assertThat(text.resolve("fr-CA")).isEqualTo("Notes FR")
        assertThat(text.resolve("ja")).isEqualTo("Notes")
    }

    // -- serialization ------------------------------------------------------

    @Test
    fun `a manifest survives a JSON round trip`() {
        // The Registry stores exactly this; a lossy round trip would mean the server
        // and the device disagree about what a module declared.
        val original = manifest()

        val restored = Json.decodeFromString(
            ModuleManifest.serializer(),
            Json.encodeToString(ModuleManifest.serializer(), original),
        )

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `deserialization re-runs validation`() {
        // A manifest that reached the Registry malformed must still be rejected here,
        // rather than being trusted because it arrived over the wire.
        val json = Json.encodeToString(ModuleManifest.serializer(), manifest())
            .replace("omnideck://notes/home", "omnideck://payments/home")

        val error = runCatching {
            Json.decodeFromString(ModuleManifest.serializer(), json)
        }.exceptionOrNull()

        assertThat(error).isNotNull()
    }
}
