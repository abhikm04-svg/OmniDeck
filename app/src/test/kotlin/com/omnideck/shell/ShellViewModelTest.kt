package com.omnideck.shell

import com.google.common.truth.Truth.assertThat
import com.omnideck.designsystem.component.TileState
import com.omnideck.kernel.lifecycle.ModuleLifecycleManager
import com.omnideck.kernel.lifecycle.ModuleRuntime
import com.omnideck.kernel.loader.ModuleDescriptor
import com.omnideck.kernel.router.MutableDestinationRegistry
import com.omnideck.kernel.router.RouterImpl
import com.omnideck.sdk.CapabilityId
import com.omnideck.sdk.CorrelationId
import com.omnideck.sdk.DataCategory
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.IconRef
import com.omnideck.sdk.LocalizedString
import com.omnideck.sdk.ModuleCategory
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleManifest
import com.omnideck.sdk.ModuleState
import com.omnideck.sdk.Route
import com.omnideck.sdk.SemVer
import com.omnideck.sdk.SemVerRange
import com.omnideck.sdk.TeamRef
import com.omnideck.sdk.capability.NavResult
import com.omnideck.shell.navigation.ModuleShortcuts
import com.omnideck.shell.navigation.ShellDestinations
import com.omnideck.shell.navigation.ShellNavigationSink
import com.omnideck.shell.navigation.ShellRoutes
import com.omnideck.shell.update.HostUpdater
import com.omnideck.testing.FakeTelemetryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Shell's navigation and tile logic.
 *
 * Two behaviours here are load-bearing and easy to lose: a module state that maps to
 * the wrong tile is a user tapping something that cannot work, and a popped
 * `navigateForResult` destination that does not report abandonment leaves the caller
 * suspended for the life of the process (OD-205).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModelTest {

    private val runtimes = MutableStateFlow<Map<ModuleId, ModuleRuntime>>(emptyMap())
    private val lifecycle = mockk<ModuleLifecycleManager>(relaxed = true) {
        every { modules } returns runtimes
    }
    private val router = mockk<RouterImpl>(relaxed = true)
    private val telemetry = FakeTelemetryService()
    private val sink = ShellNavigationSink()
    private val destinations = MutableDestinationRegistry()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the grid is whatever was discovered, in whatever state it is in`() = runTest {
        runtimes.value = mapOf(
            id("alpha") to runtime("alpha", ModuleState.ACTIVE),
            id("beta") to runtime("beta", ModuleState.ADVERTISED),
        )

        val tiles = viewModel().state.value.modules

        assertThat(tiles.map { it.id.shortId }).containsExactly("alpha", "beta")
        assertThat(tiles.first { it.id.shortId == "alpha" }.tileState).isEqualTo(TileState.Ready)
        assertThat(tiles.first { it.id.shortId == "beta" }.tileState).isInstanceOf(TileState.Available::class.java)
    }

    @Test
    fun `a downloading module shows real progress rather than an indeterminate spinner`() = runTest {
        runtimes.value = mapOf(
            id("alpha") to runtime("alpha", ModuleState.INSTALLING).copy(installProgress = 0.42f),
        )

        val tile = viewModel().state.value.modules.single().tileState

        assertThat(tile).isEqualTo(TileState.Installing(fraction = 0.42f))
    }

    @Test
    fun `a quarantined module's tile carries the reason the user will read`() = runTest {
        runtimes.value = mapOf(
            id("alpha") to runtime("alpha", ModuleState.QUARANTINED).copy(reason = "Disabled by the team."),
        )

        assertThat(viewModel().state.value.modules.single().tileState)
            .isEqualTo(TileState.Quarantined("Disabled by the team."))
    }

    @Test
    fun `tapping a tile navigates to the module's declared entry route`() = runTest {
        runtimes.value = mapOf(id("alpha") to runtime("alpha", ModuleState.INSTALLED))
        coEvery { router.navigate(any()) } returns NavResult.Navigated(Route("omnideck://alpha/home"))

        viewModel().onModuleClicked(id("alpha"))

        coVerify { router.navigate(Route("omnideck://alpha/home")) }
    }

    @Test
    fun `a module that cannot run sends the user to a status screen, not a vanishing toast`() = runTest {
        runtimes.value = mapOf(id("alpha") to runtime("alpha", ModuleState.QUARANTINED))
        coEvery { router.navigate(any()) } returns NavResult.Unavailable(id("alpha"), "Disabled.")

        val vm = viewModel()
        vm.onModuleClicked(id("alpha"))

        assertThat(vm.state.value.currentRoute).isEqualTo(ShellRoutes.moduleStatus(id("alpha")))
    }

    @Test
    fun `a route nothing owns is reported to the user`() = runTest {
        runtimes.value = mapOf(id("alpha") to runtime("alpha", ModuleState.ACTIVE))
        coEvery { router.navigate(any()) } returns NavResult.Unhandled(Route("omnideck://alpha/home"))

        val vm = viewModel()
        vm.onModuleClicked(id("alpha"))

        assertThat(vm.state.value.message).contains("omnideck://alpha/home")
    }

    @Test
    fun `back pops to the previous destination`() = runTest {
        val vm = viewModel()
        sink.navigate(Route("omnideck://alpha/one"))
        sink.navigate(Route("omnideck://alpha/two"))

        vm.onBack()

        assertThat(vm.state.value.currentRoute).isEqualTo(Route("omnideck://alpha/one"))
    }

    @Test
    fun `back from the first destination returns to the home grid`() = runTest {
        val vm = viewModel()
        sink.navigate(Route("omnideck://alpha/one"))

        vm.onBack()

        assertThat(vm.state.value.currentRoute).isNull()
    }

    @Test
    fun `leaving a navigateForResult destination without a result reports abandonment`() = runTest {
        // OD-205. Without this the caller's flow never completes: it is waiting for a
        // result from a screen the user has already dismissed.
        val vm = viewModel()
        val correlationId = CorrelationId("abc")
        sink.navigate(Route("omnideck://alpha/pick").withCorrelationId(correlationId))

        vm.onBack()

        verify { router.abandon(correlationId) }
    }

    @Test
    fun `leaving an ordinary destination abandons nothing`() = runTest {
        val vm = viewModel()
        sink.navigate(Route("omnideck://alpha/plain"))

        vm.onBack()

        verify(exactly = 0) { router.abandon(any()) }
    }

    @Test
    fun `the kill-switch watcher starts once discovery has something to watch`() = runTest {
        viewModel()

        // Started after discover(), not before: it subscribes to a flag per discovered
        // module, so starting it earlier would watch nothing (QA-9).
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            lifecycle.discover()
            lifecycle.watchKillSwitches()
        }
    }

    @Test
    fun `dismissing a message clears it so it cannot show twice`() = runTest {
        runtimes.value = mapOf(id("alpha") to runtime("alpha", ModuleState.ACTIVE))
        coEvery { router.navigate(any()) } returns NavResult.Unhandled(Route("omnideck://alpha/home"))
        val vm = viewModel()
        vm.onModuleClicked(id("alpha"))

        vm.dismissMessage()

        assertThat(vm.state.value.message).isNull()
    }

    /**
     * Mocked rather than real: publishing shortcuts needs a `Context` and a launcher,
     * and neither says anything about the navigation this class is here to test. That
     * the *right* modules are offered is asserted in ModuleShortcutsTest.
     */
    private val shortcuts = mockk<ModuleShortcuts>(relaxed = true)

    /** Play is not reachable from a unit test; the update flow is asserted in HostUpdaterTest. */
    private val updater = mockk<HostUpdater>(relaxed = true)

    private fun viewModel() = ShellViewModel(
        lifecycle = lifecycle,
        router = router,
        telemetry = telemetry,
        navigationSink = sink,
        shellDestinations = ShellDestinations(),
        shortcuts = shortcuts,
        updater = updater,
        destinations = destinations,
    )

    private fun id(shortId: String) = ModuleId("com.omnideck.$shortId")

    private fun runtime(shortId: String, state: ModuleState) = ModuleRuntime(
        descriptor = ModuleDescriptor(
            id = id(shortId),
            entryPointClass = "com.omnideck.$shortId.ModuleEntryPoint",
            delivery = DeliveryKind.BUNDLED,
        ),
        state = state,
        manifest = manifest(shortId),
    )

    private fun manifest(shortId: String) = ModuleManifest(
        id = id(shortId),
        version = SemVer(1, 0, 0),
        displayName = LocalizedString(shortId.replaceFirstChar(Char::titlecase)),
        summary = LocalizedString("A module"),
        category = ModuleCategory.PRODUCTIVITY,
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
