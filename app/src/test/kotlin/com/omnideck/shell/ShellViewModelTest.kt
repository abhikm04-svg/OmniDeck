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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    fun `a deep link that arrives before discovery is held, not dropped`() = runTest {
        // Every external entry point — a notification tap, a launcher shortcut, an
        // omnideck:// link — cold-starts the app, and MainActivity hands the route
        // over from onCreate. The Router resolves a module route by asking the
        // lifecycle manager who owns the host, and that map is empty until discover()
        // returns, so a route navigated immediately resolved to nothing.
        val slow = CompletableDeferred<Unit>()
        coEvery { lifecycle.discover() } coAnswers { slow.await() }
        val viewModel = viewModel()

        viewModel.onExternalRoute(Route("omnideck://alpha/home"))

        // Nothing yet: the Shell is not ready and the route is still waiting.
        coVerify(exactly = 0) { router.navigate(any()) }

        slow.complete(Unit)
        runCurrent()

        coVerify { router.navigate(Route("omnideck://alpha/home")) }
    }

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
    fun `a module removed but not yet reclaimed by Play does not advertise a download`() = runTest {
        // OD-307. The split is still on the device, so tapping this fetches nothing and
        // reopens the module at once. A tile reading "4.2 MB · tap to install" would be
        // stating a figure that never materialises, which is what made a removal look
        // like it had done nothing at all.
        runtimes.value = mapOf(
            id("alpha") to runtime("alpha", ModuleState.ADVERTISED).copy(awaitingPlayCleanup = true),
        )

        assertThat(viewModel().state.value.modules.single().tileState).isEqualTo(TileState.AwaitingCleanup)
    }

    @Test
    fun `a module that was never installed still advertises its download size`() = runTest {
        // The ordinary ADVERTISED case must keep its size, or the flag has simply
        // replaced one wrong label with another.
        runtimes.value = mapOf(id("alpha") to runtime("alpha", ModuleState.ADVERTISED))

        assertThat(viewModel().state.value.modules.single().tileState)
            .isInstanceOf(TileState.Available::class.java)
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

        assertThat(vm.openIntent()).isEqualTo(ShellRoutes.moduleStatus(id("alpha")))
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
    fun `a routed navigation is handed to the controller as an open intent`() = runTest {
        // The Shell no longer keeps its own back stack: the NavController's entries are
        // the stack, because each is a ViewModelStoreOwner and that is what gives a
        // destination's ViewModels a lifecycle (OD-205). What is assertable here is the
        // decision — where to go — and that is what this checks. Where the stack ends
        // up after a pop is the controller's own behaviour and is covered on a device
        // by ShellBackStackInstrumentedTest.
        val vm = viewModel()

        sink.navigate(Route("omnideck://alpha/one"))

        assertThat(vm.openIntent()).isEqualTo(Route("omnideck://alpha/one"))
    }

    @Test
    fun `back asks the controller to pop rather than mutating state itself`() = runTest {
        val vm = viewModel()
        vm.onCurrentDestinationChanged(Route("omnideck://alpha/one"))

        vm.onBack()

        assertThat(vm.navigationIntents.first()).isEqualTo(ShellViewModel.NavIntent.Back)
    }

    @Test
    fun `leaving a navigateForResult destination without a result reports abandonment`() = runTest {
        // OD-205. Without this the caller's flow never completes: it is waiting for a
        // result from a screen the user has already dismissed.
        val vm = viewModel()
        val correlationId = CorrelationId("abc")
        // The NavController reports where the user is; the Shell mirrors it rather than
        // tracking a second copy that could drift from the real back stack.
        vm.onCurrentDestinationChanged(Route("omnideck://alpha/pick").withCorrelationId(correlationId))

        vm.onBack()

        verify { router.abandon(correlationId) }
    }

    @Test
    fun `leaving an ordinary destination abandons nothing`() = runTest {
        val vm = viewModel()
        vm.onCurrentDestinationChanged(Route("omnideck://alpha/plain"))

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

    /** The route of the next navigation the ViewModel asked the controller to perform. */
    private suspend fun ShellViewModel.openIntent(): Route =
        (navigationIntents.first() as ShellViewModel.NavIntent.Open).route

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
