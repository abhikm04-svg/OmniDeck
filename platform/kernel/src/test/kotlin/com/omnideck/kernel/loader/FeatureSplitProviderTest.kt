package com.omnideck.kernel.loader

import com.google.android.play.core.splitinstall.model.SplitInstallErrorCode
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The Play-facing decisions: which updates the UI hears about, how a failure is
 * described, and whether it is worth retrying.
 *
 * All reachable without a Play-connected device because [SplitInstaller] is a seam —
 * before it existed, every line below could only run on a real install.
 */
class FeatureSplitProviderTest {

    private val notes = ModuleId("com.omnideck.notes")

    private class FakeSplitInstaller(
        private var installed: MutableSet<String> = mutableSetOf(),
        private val updates: Flow<SplitSessionUpdate> = flowOf(),
        /** What Play's consent dialog would do. False = it could not be shown. */
        private val confirmationShown: Boolean = true,
    ) : SplitInstaller {
        val installRequests = mutableListOf<String>()
        val uninstallRequests = mutableListOf<String>()
        val confirmationRequests = mutableListOf<Int>()

        override fun installedSplits(): Set<String> = installed

        override fun install(splitName: String): Flow<SplitSessionUpdate> {
            installRequests += splitName
            return updates
        }

        override fun deferredUninstall(splitName: String) {
            uninstallRequests += splitName
        }

        override suspend fun requestUserConfirmation(sessionId: Int): Boolean {
            confirmationRequests += sessionId
            return confirmationShown
        }

        fun markInstalled(splitName: String) = installed.add(splitName)
    }

    private fun provider(installer: SplitInstaller, classLoader: () -> ClassLoader = { javaClass.classLoader!! }) =
        FeatureSplitProvider(installer, Dispatchers.Unconfined, classLoader)

    // -- identity -----------------------------------------------------------

    @Test
    fun `handles the feature split delivery kind`() {
        assertThat(provider(FakeSplitInstaller()).handles).isEqualTo(DeliveryKind.FEATURE_SPLIT)
    }

    @Test
    fun `reports installed state from the installer`() {
        val installer = FakeSplitInstaller()
        val provider = provider(installer)
        assertThat(provider.isInstalled(notes)).isFalse()

        installer.markInstalled(notes.splitName)

        assertThat(provider.isInstalled(notes)).isTrue()
    }

    // -- install ------------------------------------------------------------

    @Test
    fun `an already-installed split reports success without asking Play`() = runTest {
        // Starting a session for a split already on device would show the user a
        // download that is not happening.
        val installer = FakeSplitInstaller().apply { markInstalled(notes.splitName) }

        val progress = provider(installer).install(notes).toList()

        assertThat(progress).containsExactly(InstallProgress.Installed)
        assertThat(installer.installRequests).isEmpty()
    }

    @Test
    fun `progress is relayed in order through to installed`() = runTest {
        val installer = FakeSplitInstaller(
            updates = flowOf(
                SplitSessionUpdate(SplitStatus.PENDING),
                SplitSessionUpdate(SplitStatus.DOWNLOADING, bytesDownloaded = 500, totalBytes = 1_000),
                SplitSessionUpdate(SplitStatus.INSTALLING),
                SplitSessionUpdate(SplitStatus.INSTALLED),
            ),
        )

        val progress = provider(installer).install(notes).toList()

        assertThat(progress).containsExactly(
            InstallProgress.Pending,
            InstallProgress.Downloading(500, 1_000),
            InstallProgress.Installing,
            InstallProgress.Installed,
        ).inOrder()
    }

    @Test
    fun `user confirmation is surfaced rather than swallowed`() = runTest {
        // Dropping this is the usual cause of "stuck at 0%" on metered connections.
        val installer = FakeSplitInstaller(
            updates = flowOf(SplitSessionUpdate(SplitStatus.REQUIRES_USER_CONFIRMATION)),
        )

        assertThat(provider(installer).install(notes).toList())
            .containsExactly(InstallProgress.RequiresUserConfirmation)
    }

    @Test
    fun `the consent dialog is actually asked for, on the session that wants it`() = runTest {
        // Surfacing the state is not enough (OD-302): Play holds the session until the
        // dialog is answered, and never times out. It is also asked for by session id,
        // because consenting to the wrong session authorises a download nobody asked
        // for — a listener sees every session in the process.
        val installer = FakeSplitInstaller(
            updates = flowOf(SplitSessionUpdate(SplitStatus.REQUIRES_USER_CONFIRMATION, sessionId = 7)),
        )

        provider(installer).install(notes).toList()

        assertThat(installer.confirmationRequests).containsExactly(7)
    }

    @Test
    fun `an install that cannot ask for consent fails instead of hanging`() = runTest {
        // Play sends nothing more on a session whose dialog was never shown. Without a
        // terminal progress here the Router's first() never returns, the tile stays at
        // 0% and there is nothing for the user to retry.
        val installer = FakeSplitInstaller(
            updates = flowOf(SplitSessionUpdate(SplitStatus.REQUIRES_USER_CONFIRMATION, sessionId = 7)),
            confirmationShown = false,
        )

        val progress = provider(installer).install(notes).toList()

        assertThat(progress).hasSize(2)
        assertThat(progress.first()).isEqualTo(InstallProgress.RequiresUserConfirmation)
        val failure = progress.last() as InstallProgress.Failed
        assertThat(failure.code).isEqualTo(FeatureSplitProvider.CONFIRMATION_UNAVAILABLE)
        // Usually the app was in the background when Play asked; from a visible screen
        // the next attempt works, so the user is offered one.
        assertThat(failure.retryable).isTrue()
    }

    @Test
    fun `no consent is requested for a session that did not ask for it`() = runTest {
        val installer = FakeSplitInstaller(
            updates = flowOf(
                SplitSessionUpdate(SplitStatus.DOWNLOADING, bytesDownloaded = 1, totalBytes = 2),
                SplitSessionUpdate(SplitStatus.INSTALLED),
            ),
        )

        provider(installer).install(notes).toList()

        assertThat(installer.confirmationRequests).isEmpty()
    }

    @Test
    fun `cancellation is reported`() = runTest {
        val installer = FakeSplitInstaller(updates = flowOf(SplitSessionUpdate(SplitStatus.CANCELED)))

        assertThat(provider(installer).install(notes).toList())
            .containsExactly(InstallProgress.Canceled)
    }

    @Test
    fun `an unknown status is ignored rather than guessed at`() = runTest {
        val installer = FakeSplitInstaller(
            updates = flowOf(
                SplitSessionUpdate(SplitStatus.UNKNOWN),
                SplitSessionUpdate(SplitStatus.INSTALLED),
            ),
        )

        assertThat(provider(installer).install(notes).toList())
            .containsExactly(InstallProgress.Installed)
    }

    @Test
    fun `download progress carries the byte counts`() = runTest {
        val installer = FakeSplitInstaller(
            updates = flowOf(SplitSessionUpdate(SplitStatus.DOWNLOADING, 250, 1_000)),
        )

        val downloading = provider(installer).install(notes).toList()
            .single() as InstallProgress.Downloading

        assertThat(downloading.bytesDownloaded).isEqualTo(250)
        assertThat(downloading.totalBytes).isEqualTo(1_000)
        assertThat(downloading.fraction).isEqualTo(0.25f)
    }

    // -- failure classification --------------------------------------------

    @Test
    fun `a network failure is retryable and says what to do`() = runTest {
        val failure = failWith(SplitInstallErrorCode.NETWORK_ERROR)

        assertThat(failure.retryable).isTrue()
        assertThat(failure.message).contains("Check your network")
    }

    @Test
    fun `insufficient storage is not retryable`() = runTest {
        // Nothing changes until the user frees space, so retrying would burn battery
        // and data to fail identically.
        val failure = failWith(SplitInstallErrorCode.INSUFFICIENT_STORAGE)

        assertThat(failure.retryable).isFalse()
        assertThat(failure.message).contains("free space")
    }

    @Test
    fun `transient session errors are retryable`() = runTest {
        listOf(
            SplitInstallErrorCode.ACCESS_DENIED,
            SplitInstallErrorCode.SESSION_NOT_FOUND,
            SplitInstallErrorCode.INCOMPATIBLE_WITH_EXISTING_SESSION,
        ).forEach { code ->
            assertThat(failWith(code).retryable).isTrue()
        }
    }

    @Test
    fun `permanent errors are not retryable`() = runTest {
        listOf(
            SplitInstallErrorCode.MODULE_UNAVAILABLE,
            SplitInstallErrorCode.INVALID_REQUEST,
            SplitInstallErrorCode.API_NOT_AVAILABLE,
            SplitInstallErrorCode.APP_NOT_OWNED,
            SplitInstallErrorCode.PLAY_STORE_NOT_FOUND,
        ).forEach { code ->
            assertThat(failWith(code).retryable).isFalse()
        }
    }

    @Test
    fun `an unrecognised error code still produces a message carrying the code`() = runTest {
        val failure = failWith(9_999)

        assertThat(failure.message).contains("9999")
        assertThat(failure.retryable).isFalse()
    }

    @Test
    fun `the failure keeps the original error code for telemetry`() = runTest {
        assertThat(failWith(SplitInstallErrorCode.NETWORK_ERROR).code)
            .isEqualTo(SplitInstallErrorCode.NETWORK_ERROR)
    }

    private suspend fun failWith(code: Int): InstallProgress.Failed {
        val installer = FakeSplitInstaller(
            updates = flowOf(SplitSessionUpdate(SplitStatus.FAILED, errorCode = code)),
        )
        return provider(installer).install(notes).toList().single() as InstallProgress.Failed
    }

    // -- uninstall ----------------------------------------------------------

    @Test
    fun `uninstall is deferred to Play`() = runTest {
        val installer = FakeSplitInstaller()

        provider(installer).uninstall(notes)

        assertThat(installer.uninstallRequests).containsExactly(notes.splitName)
    }

    // -- load ---------------------------------------------------------------

    class StubEntryPoint : OmniModule {
        override val manifest: ModuleManifest get() = error("unused")
        override suspend fun initialize(services: PlatformServices) = ModuleInitResult.Ready
        override fun registerDestinations(registry: DestinationRegistry) = Unit
    }

    class NotAModule

    private fun descriptorFor(klass: Class<*>) = ModuleDescriptor(
        id = notes,
        entryPointClass = klass.name,
        delivery = DeliveryKind.FEATURE_SPLIT,
    )

    @Test
    fun `loading a split that is not installed fails before touching the class loader`() = runTest {
        val provider = provider(FakeSplitInstaller()) { error("class loader must not be consulted") }

        val error = runCatching { provider.load(descriptorFor(StubEntryPoint::class.java)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(ModuleLoadException::class.java)
        assertThat(error).hasMessageThat().contains("not installed")
    }

    @Test
    fun `loads and instantiates the entry point`() = runTest {
        val installer = FakeSplitInstaller().apply { markInstalled(notes.splitName) }

        val module = provider(installer).load(descriptorFor(StubEntryPoint::class.java))

        assertThat(module).isInstanceOf(StubEntryPoint::class.java)
    }

    @Test
    fun `a missing entry point blames the keep rule and SplitCompat`() = runTest {
        // The two real causes, and the failure mode is release-only — so the message
        // has to carry the diagnosis rather than just the class name.
        val installer = FakeSplitInstaller().apply { markInstalled(notes.splitName) }
        val descriptor = ModuleDescriptor(notes, "com.omnideck.nope.Missing", DeliveryKind.FEATURE_SPLIT)

        val error = runCatching { provider(installer).load(descriptor) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("keep rule")
        assertThat(error).hasMessageThat().contains("SplitCompat")
    }

    @Test
    fun `a class that is not an OmniModule is reported as such`() = runTest {
        val installer = FakeSplitInstaller().apply { markInstalled(notes.splitName) }

        val error = runCatching {
            provider(installer).load(descriptorFor(NotAModule::class.java))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ModuleLoadException::class.java)
        assertThat(error).hasMessageThat().contains("does not implement OmniModule")
    }

    @Test
    fun `the class loader is re-read on every load`() = runTest {
        // SplitCompat swaps it in after an install; capturing one at construction is
        // how "installed but not found" bugs happen.
        val installer = FakeSplitInstaller().apply { markInstalled(notes.splitName) }
        var reads = 0
        val provider = provider(installer) {
            reads++
            javaClass.classLoader!!
        }

        provider.load(descriptorFor(StubEntryPoint::class.java))
        provider.load(descriptorFor(StubEntryPoint::class.java))

        assertThat(reads).isEqualTo(2)
    }
}
