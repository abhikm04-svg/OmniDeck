package com.omnideck.shell.catalog

import com.google.common.truth.Truth.assertThat
import com.omnideck.kernel.lifecycle.ModuleLifecycleManager
import com.omnideck.kernel.lifecycle.ModuleRuntime
import com.omnideck.kernel.loader.ModuleDescriptor
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.DataCategory
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.IconRef
import com.omnideck.sdk.InstallProgress
import com.omnideck.sdk.LocalizedString
import com.omnideck.sdk.ModuleCategory
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleManifest
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.Route
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.SemVerRange
import com.omnideck.sdk.TeamRef
import com.omnideck.testing.FakeTelemetryService
import io.mockk.Deregisterable
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.registerInstanceFactory
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The acquisition surface of on-demand delivery (OD-305).
 *
 * Two things here are load-bearing. An install that stops at "downloaded" leaves a
 * module inert, which looks to a user exactly like an install that failed; and a
 * removal that reclaims the split without erasing the module's data leaves personal
 * data on the device after the user asked for it to go (ADR-005).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {

    private val runtimes = MutableStateFlow<Map<ModuleId, ModuleRuntime>>(emptyMap())
    private val lifecycle = mockk<ModuleLifecycleManager>(relaxed = true) {
        every { modules } returns runtimes
    }
    private val telemetry = FakeTelemetryService()

    /**
     * MockK builds a placeholder value for every `any()` argument by calling the
     * type's constructor, and [ModuleId] is a value class that rejects anything not
     * reverse-DNS — so an unregistered `any<ModuleId>()` fails in the matcher, before
     * the assertion it belongs to is ever evaluated. Registering a well-formed id is
     * what lets a verification say "no module at all", rather than having to name one.
     */
    private lateinit var moduleIds: Deregisterable

    @Before
    fun setUp() {
        moduleIds = registerInstanceFactory { ModuleId("com.omnideck.placeholder") }
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        moduleIds.deregister()
        Dispatchers.resetMain()
    }

    @Test
    fun `the catalog is whatever was discovered, in alphabetical order`() = runTest {
        runtimes.value = mapOf(
            id("zebra") to runtime("zebra", ModuleState.ACTIVE),
            id("alpha") to runtime("alpha", ModuleState.ADVERTISED),
        )

        val entries = viewModel().entries.value

        assertThat(entries.map { it.title }).containsExactly("Alpha", "Zebra").inOrder()
    }

    @Test
    fun `an on-demand module that was never installed is named from its id, not invented`() = runTest {
        // Display name, summary and size live in the module's own manifest, which is
        // code inside the split. Before the download the Shell genuinely does not have
        // them — the honest fallback is the id, and the Catalog says so.
        runtimes.value = mapOf(
            id("alpha") to runtime("alpha", ModuleState.ADVERTISED, DeliveryKind.FEATURE_SPLIT, manifest = null),
        )

        val entry = viewModel().entries.value.single()

        assertThat(entry.title).isEqualTo("Alpha")
        assertThat(entry.detailsAreProvisional).isTrue()
        assertThat(entry.downloadBytes).isEqualTo(0)
        assertThat(entry.isBundled).isFalse()
    }

    @Test
    fun `installing brings the module all the way up, not just onto the device`() = runTest {
        runtimes.value = mapOf(id("alpha") to runtime("alpha", ModuleState.ADVERTISED))
        every { lifecycle.install(id("alpha")) } returns flowOf(InstallProgress.Installed)
        every { lifecycle.stateOf(id("alpha")) } returns ModuleState.INSTALLED

        viewModel().onInstall(id("alpha"))

        coVerify { lifecycle.activate(id("alpha")) }
    }

    @Test
    fun `a failed download does not try to start the module`() = runTest {
        // The lifecycle manager puts a failed install back to ADVERTISED. Activating
        // from there would ask a provider to load a class that is not on the device.
        runtimes.value = mapOf(id("alpha") to runtime("alpha", ModuleState.ADVERTISED))
        every { lifecycle.install(id("alpha")) } returns
            flowOf(InstallProgress.Failed(code = 1, message = "No connection.", retryable = true))
        every { lifecycle.stateOf(id("alpha")) } returns ModuleState.ADVERTISED

        viewModel().onInstall(id("alpha"))

        coVerify(exactly = 0) { lifecycle.activate(any()) }
    }

    // -- cancellation (OD-302) ----------------------------------------------

    @Test
    fun `cancelling reaches the install session rather than dropping the collector`() = runTest {
        // Abandoning the coroutine would stop this process listening while Play kept
        // downloading in its own — the user's data, spent after they pressed Cancel.
        runtimes.value = mapOf(id("alpha") to runtime("alpha", ModuleState.INSTALLING))

        viewModel().onCancel(id("alpha"))

        verify { lifecycle.cancelInstall(id("alpha")) }
    }

    @Test
    fun `only an on-demand download in flight offers a cancel`() = runTest {
        // Initialising runs in this process and is over in milliseconds; purging must
        // not be interruptible half-way through erasing a module's data.
        runtimes.value = mapOf(
            id("alpha") to runtime("alpha", ModuleState.INSTALLING, DeliveryKind.FEATURE_SPLIT),
            id("bravo") to runtime("bravo", ModuleState.INITIALIZING, DeliveryKind.FEATURE_SPLIT),
            id("charlie") to runtime("charlie", ModuleState.PURGING, DeliveryKind.FEATURE_SPLIT),
            id("delta") to runtime("delta", ModuleState.INSTALLING, DeliveryKind.BUNDLED),
        )

        val cancellable = viewModel().entries.value.filter { it.isCancellable }

        assertThat(cancellable.map { it.title }).containsExactly("Alpha")
    }

    // -- search and categories (OD-305) -------------------------------------

    @Test
    fun `search matches the id as well as the name, because that is all an uninstalled module has`() = runTest {
        runtimes.value = mapOf(
            id("alpha") to runtime("alpha", ModuleState.ACTIVE),
            id("zebra") to runtime("zebra", ModuleState.ADVERTISED, manifest = null),
        )
        val viewModel = viewModel()

        viewModel.onQueryChange("zeb")

        assertThat(viewModel.state.value.entries.map { it.id }).containsExactly(id("zebra"))
    }

    @Test
    fun `the category filter offers only categories this build actually has`() = runTest {
        // Offering the whole enum would put chips in front of the user that empty the
        // list, and would mean the Shell knew a fixed set of module categories.
        runtimes.value = mapOf(
            id("alpha") to runtime("alpha", ModuleState.ACTIVE, category = ModuleCategory.FINANCE),
            id("bravo") to runtime("bravo", ModuleState.ACTIVE, category = ModuleCategory.FINANCE),
        )

        assertThat(viewModel().state.value.categories).containsExactly(ModuleCategory.FINANCE)
    }

    @Test
    fun `filtering narrows the list without losing the module the detail page addresses`() = runTest {
        // The detail screen reads `entries`, not `state`: a deep link to a module must
        // not depend on what the user last typed into the search box.
        runtimes.value = mapOf(
            id("alpha") to runtime("alpha", ModuleState.ACTIVE, category = ModuleCategory.FINANCE),
            id("zebra") to runtime("zebra", ModuleState.ACTIVE, category = ModuleCategory.MEDIA),
        )
        val viewModel = viewModel()

        viewModel.onCategorySelected(ModuleCategory.MEDIA)

        assertThat(viewModel.state.value.entries.map { it.id }).containsExactly(id("zebra"))
        assertThat(viewModel.entries.value).hasSize(2)
    }

    @Test
    fun `an empty result from a filter is not reported as an empty build`() = runTest {
        runtimes.value = mapOf(id("alpha") to runtime("alpha", ModuleState.ACTIVE))
        val viewModel = viewModel()

        viewModel.onQueryChange("nothing matches this")

        val state = viewModel.state.value
        assertThat(state.entries).isEmpty()
        assertThat(state.isFiltered).isTrue()
        assertThat(state.totalCount).isEqualTo(1)
    }

    @Test
    fun `removing a module erases its data, not only its download`() = runTest {
        // Play's uninstall is deferred and reclaims space whenever it likes. If that
        // were all removal did, the module's database would outlive the user's
        // decision to remove it.
        runtimes.value = mapOf(id("alpha") to runtime("alpha", ModuleState.ACTIVE))

        viewModel().onRemove(id("alpha"))

        coVerify { lifecycle.purge(id("alpha"), PurgeScope.ALL) }
    }

    @Test
    fun `a busy module reports the progress the provider gave, so the bar is real`() = runTest {
        runtimes.value = mapOf(
            id("alpha") to runtime("alpha", ModuleState.INSTALLING).copy(installProgress = 0.42f),
        )

        val entry = viewModel().entries.value.single()

        assertThat(entry.isBusy).isTrue()
        assertThat(entry.installProgress).isEqualTo(0.42f)
    }

    private fun viewModel() = CatalogViewModel(lifecycle = lifecycle, telemetry = telemetry)

    private fun id(shortId: String) = ModuleId("com.omnideck.$shortId")

    private fun runtime(
        shortId: String,
        state: ModuleState,
        delivery: DeliveryKind = DeliveryKind.BUNDLED,
        category: ModuleCategory = ModuleCategory.PRODUCTIVITY,
        manifest: ModuleManifest? = manifest(shortId, category),
    ) = ModuleRuntime(
        descriptor = ModuleDescriptor(
            id = id(shortId),
            entryPointClass = "com.omnideck.$shortId.ModuleEntryPoint",
            delivery = delivery,
        ),
        state = state,
        manifest = manifest,
    )

    private fun manifest(shortId: String, category: ModuleCategory = ModuleCategory.PRODUCTIVITY) = ModuleManifest(
        id = id(shortId),
        version = SemVer(1, 0, 0),
        displayName = LocalizedString(shortId.replaceFirstChar(Char::titlecase)),
        summary = LocalizedString("A module"),
        category = category,
        icon = IconRef.Symbol("widgets"),
        delivery = DeliveryKind.BUNDLED,
        sdkRange = SemVerRange(SemVer(1, 0, 0), SemVer(2, 0, 0)),
        minHostVersionCode = 1,
        entryRoute = Route("omnideck://$shortId/home"),
        requiredCapabilities = setOf(CapabilityId.TELEMETRY),
        dataCategories = setOf(DataCategory.APP_ACTIVITY),
        owner = TeamRef("platform"),
    )
}
