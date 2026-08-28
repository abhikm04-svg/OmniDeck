package com.omnideck.shell

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.splitcompat.SplitCompat
import com.omnideck.designsystem.theme.OmniDeckTheme
import com.omnideck.kernel.loader.ConfirmationLauncher
import com.omnideck.kernel.services.PermissionRequester
import com.omnideck.sdk.capability.PermissionBroker
import com.omnideck.sdk.capability.Router
import com.omnideck.shell.navigation.ExternalRoutes
import com.omnideck.shell.navigation.ShellNavHost
import com.omnideck.shell.navigation.ShellNavigationSink
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * The single Activity (ADR-003).
 *
 * Modules contribute composable destinations, never Activities, so there is exactly
 * one back stack, one theme scope and one deep-link entry point — and adding a
 * module never requires a manifest merge.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var navigationSink: ShellNavigationSink

    @Inject lateinit var router: Router

    @Inject lateinit var permissionRequesterHolder: ActivityPermissionRequester

    @Inject lateinit var confirmationLauncherHolder: ActivityConfirmationLauncher

    private var pendingPermission: ((PermissionBroker.PermissionResult) -> Unit)? = null

    private var pendingConfirmation: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val callback = pendingPermission
        pendingPermission = null
        callback?.invoke(
            when {
                granted -> PermissionBroker.PermissionResult.GRANTED
                // shouldShowRequestPermissionRationale is false *after* a permanent
                // denial, which is the only signal Android gives us for it.
                else -> PermissionBroker.PermissionResult.DENIED
            },
        )
    }

    /**
     * OD-302. Play's consent dialog for a large or metered download, launched as an
     * `IntentSender` the system owns. Play holds the session until this is answered,
     * so failing to launch it is the "stuck at 0%" report.
     */
    private val confirmationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val callback = pendingConfirmation
        pendingConfirmation = null
        callback?.invoke(result.resultCode == RESULT_OK)
    }

    /**
     * OD-304. `SplitCompat.install()` in the Application makes a freshly installed
     * split's *code* reachable; its *resources* are only reachable from a context
     * that has also been patched. Modules contribute Composables rendered inside this
     * Activity (ADR-003), so without this a split's strings and drawables throw
     * `Resources$NotFoundException` while its Kotlin runs perfectly — a failure that
     * appears only for on-demand modules, and only after the first install.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        SplitCompat.installActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionRequesterHolder.attach { permission, _ ->
            suspendCancellableCoroutine { continuation ->
                pendingPermission = { continuation.resume(it) }
                permissionLauncher.launch(permission)
                continuation.invokeOnCancellation { pendingPermission = null }
            }
        }

        confirmationLauncherHolder.attach { intentSender ->
            suspendCancellableCoroutine { continuation ->
                pendingConfirmation = { continuation.resume(it) }
                // A stale IntentSender — a session Play has since cancelled — throws
                // rather than returning. Treated as a decline: the install stops with
                // a message instead of waiting on a dialog that will never appear.
                val launched = runCatching {
                    confirmationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }.isSuccess
                if (!launched) {
                    pendingConfirmation = null
                    continuation.resume(false)
                }
                continuation.invokeOnCancellation { pendingConfirmation = null }
            }
        }

        setContent {
            OmniDeckTheme {
                ShellNavHost(onReady = { splash.setKeepOnScreenCondition { false } })
            }
        }

        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * All external entry points converge on the Router — notifications, App Links,
     * shortcuts, the `omnideck://` scheme (OD-204).
     *
     * [ExternalRoutes] returns null for anything OmniDeck does not own, and that null
     * matters: an Intent's data is attacker-controllable, so a URI we cannot map is
     * dropped rather than handed to the Router as though the user had asked for it.
     */
    private fun handleDeepLink(intent: Intent?) {
        val route = ExternalRoutes.from(intent?.data) ?: return
        lifecycleScope.launch { router.navigate(route) }
    }

    override fun onDestroy() {
        permissionRequesterHolder.detach()
        confirmationLauncherHolder.detach()
        super.onDestroy()
    }
}

/**
 * Bridges the kernel's permission broker to the Activity that owns the
 * ActivityResult plumbing. The kernel deliberately has no Activity reference.
 */
@javax.inject.Singleton
class ActivityPermissionRequester @Inject constructor() : PermissionRequester {

    @Volatile
    private var delegate: (suspend (String, PermissionBroker.Rationale) -> PermissionBroker.PermissionResult)? = null

    fun attach(block: suspend (String, PermissionBroker.Rationale) -> PermissionBroker.PermissionResult) {
        delegate = block
    }

    fun detach() {
        delegate = null
    }

    override suspend fun request(
        permission: String,
        rationale: PermissionBroker.Rationale,
    ): PermissionBroker.PermissionResult =
        delegate?.invoke(permission, rationale) ?: PermissionBroker.PermissionResult.DENIED
}

/**
 * The same bridge for Play's split-install consent dialog (OD-302).
 *
 * Returning false with no Activity attached is the point of this class, not a
 * degenerate case: Play asks for confirmation whenever it likes, including while the
 * app is in the background, and the kernel needs a definite "could not ask" so it can
 * end the install with something the user can retry rather than a progress bar that
 * never moves.
 */
@javax.inject.Singleton
class ActivityConfirmationLauncher @Inject constructor() : ConfirmationLauncher {

    @Volatile
    private var delegate: (suspend (IntentSender) -> Boolean)? = null

    fun attach(block: suspend (IntentSender) -> Boolean) {
        delegate = block
    }

    fun detach() {
        delegate = null
    }

    override suspend fun launch(intentSender: IntentSender): Boolean = delegate?.invoke(intentSender) ?: false
}
