package com.omnideck.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.omnideck.designsystem.theme.OmniDeckTheme
import com.omnideck.kernel.services.PermissionRequester
import com.omnideck.sdk.Route
import com.omnideck.sdk.capability.PermissionBroker
import com.omnideck.sdk.capability.Router
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

    private var pendingPermission: ((PermissionBroker.PermissionResult) -> Unit)? = null

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

    /** All external entry points converge on the Router — notifications, App Links, shortcuts. */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data?.toString() ?: return
        if (!uri.startsWith(Route.SCHEME_PREFIX)) return
        lifecycleScope.launch { router.navigate(Route(uri)) }
    }

    override fun onDestroy() {
        permissionRequesterHolder.detach()
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
