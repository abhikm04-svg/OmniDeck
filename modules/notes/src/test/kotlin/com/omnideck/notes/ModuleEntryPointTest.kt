package com.omnideck.notes

import androidx.compose.runtime.Composable
import com.google.common.truth.Truth.assertThat
import com.omnideck.notes.sync.NotesSyncRuntime
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.DestinationRegistry
import com.omnideck.sdk.ModuleInitResult
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.Route
import com.omnideck.sdk.RouteArgs
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.SuspendReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The module's half of the contract, exercised through `PlatformServices` alone.
 *
 * There is no Shell and no kernel on this test's classpath — that is the property the
 * Phase 2 exit gate is really about, and it is asserted structurally by
 * `checkArchitecture` and behaviourally here.
 */
@RunWith(RobolectricTestRunner::class)
class ModuleEntryPointTest {

    private val fixture = NotesTestFixture()
    private val module = ModuleEntryPoint()

    @After
    fun tearDown() {
        NotesSyncRuntime.detach()
        fixture.close()
    }

    @Test
    fun `the manifest declares every capability the module actually uses`() {
        val manifest = module.manifest

        assertThat(manifest.requiredCapabilities).containsAtLeast(
            CapabilityId.STORAGE,
            CapabilityId.TELEMETRY,
            CapabilityId.ROUTER,
        )
        // Sync is the only thing lost without these, so they are optional rather than
        // required: a device with no network still gets a working notes module.
        assertThat(manifest.optionalCapabilities).containsExactly(CapabilityId.NETWORK, CapabilityId.WORK)
    }

    @Test
    fun `the manifest is compatible with the SDK it was built against`() {
        assertThat(module.manifest.isCompatibleWith(SemVer(1, 0, 0), hostVersionCode = 1)).isTrue()
        // And explicitly not with the next major, which is the whole point of the range.
        assertThat(module.manifest.isCompatibleWith(SemVer(2, 0, 0), hostVersionCode = 1)).isFalse()
    }

    @Test
    fun `the entry route and deep links all belong to this module`() {
        val manifest = module.manifest

        assertThat(manifest.entryRoute.host).isEqualTo(manifest.id.shortId)
        manifest.deepLinks.forEach {
            assertThat(it.pattern).startsWith("omnideck://${manifest.id.shortId}/")
        }
    }

    @Test
    fun `data categories are declared, because the Play listing is generated from them`() {
        assertThat(module.manifest.dataCategories).isNotEmpty()
    }

    @Test
    fun `without a sync endpoint the module starts degraded and says why`() = runTest {
        val result = module.initialize(fixture.services())

        assertThat(result).isInstanceOf(ModuleInitResult.Degraded::class.java)
        assertThat((result as ModuleInitResult.Degraded).reason).contains("this device only")
    }

    @Test
    fun `with a sync endpoint the module starts ready and schedules its drain`() = runTest {
        val services = fixture.services()
        services.flags.set(NotesComponent.SYNC_ENDPOINT_FLAG, "https://notes.example.test")

        val result = module.initialize(services)

        assertThat(result).isEqualTo(ModuleInitResult.Ready)
        assertThat(services.work.periodic).hasSize(1)
        assertThat(NotesSyncRuntime.engine).isNotNull()
    }

    @Test
    fun `initialization is telemetered so activation can be measured`() = runTest {
        val services = fixture.services()

        module.initialize(services)

        assertThat(services.telemetry.eventNames()).contains("notes_initialized")
    }

    @Test
    fun `initialize is idempotent, because the Shell retries it`() = runTest {
        val services = fixture.services()

        module.initialize(services)
        val second = module.initialize(services)

        assertThat(second).isInstanceOf(ModuleInitResult.Degraded::class.java)
    }

    @Test
    fun `every destination the manifest promises is registered`() = runTest {
        module.initialize(fixture.services())
        val registry = RecordingRegistry()

        module.registerDestinations(registry)

        assertThat(registry.patterns).containsExactly(
            "omnideck://notes/home",
            "omnideck://notes/new",
            "omnideck://notes/note/{noteId}",
        )
        assertThat(registry.patterns).contains(module.manifest.entryRoute.uri)
        module.manifest.deepLinks.forEach { assertThat(registry.patterns).contains(it.pattern) }
    }

    @Test
    fun `suspending releases the sync engine so background work stops`() = runTest {
        val services = fixture.services()
        services.flags.set(NotesComponent.SYNC_ENDPOINT_FLAG, "https://notes.example.test")
        module.initialize(services)

        module.suspend(SuspendReason.MEMORY_PRESSURE)

        assertThat(NotesSyncRuntime.engine).isNull()
    }

    @Test
    fun `a full purge erases the module's data`() = runTest {
        module.initialize(fixture.services())
        fixture.repository.create("Sensitive", "content")

        module.purge(PurgeScope.ALL)

        assertThat(fixture.repository.observeNotes().first()).isEmpty()
    }

    @Test
    fun `a session purge keeps the user's notes`() = runTest {
        module.initialize(fixture.services())
        fixture.repository.create("Mine", "")

        module.purge(PurgeScope.SESSION)

        assertThat(fixture.repository.observeNotes().first()).hasSize(1)
    }

    @Test
    fun `purging before initialize does not throw`() = runTest {
        module.purge(PurgeScope.ALL)
    }

    @Test
    fun `storage is opened under the module's own namespace`() = runTest {
        val services = fixture.services()

        module.initialize(services)

        // The kernel namespaces the path; what the module controls is the name, and
        // asking for the same one twice would collide with another module only if the
        // isolation of ADR-005 were not structural.
        assertThat((services.storage as com.omnideck.testing.FakeStorageService).requestedDatabases)
            .containsExactly("notes")
    }

    private class RecordingRegistry : DestinationRegistry {
        val patterns = mutableListOf<String>()

        override fun destination(pattern: String, content: @Composable (RouteArgs) -> Unit) {
            patterns += pattern
        }
    }
}

/** Sanity: the route helpers the module relies on behave as its manifest assumes. */
class NotesRouteTest {

    @Test
    fun `a note deep link carries its id through the route pattern`() {
        val route = Route("omnideck://notes/note/abc-123")

        val args = ModuleEntryPoint().manifest.deepLinks.single().extract(route)

        assertThat(args).containsExactly("noteId", "abc-123")
    }
}
