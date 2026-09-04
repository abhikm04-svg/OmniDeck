package com.omnideck.shell.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.omnideck.designsystem.component.ErrorSurface
import com.omnideck.designsystem.component.PrimaryButton
import com.omnideck.designsystem.theme.Spacing
import com.omnideck.kernel.lifecycle.ModuleRuntime
import com.omnideck.kernel.lifecycle.QuarantineCause
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.ModuleState
import com.omnideck.shell.ShellViewModel
import com.omnideck.shell.navigation.LocalShellViewModel

/**
 * The contained failure surface (OD-208, QA-6).
 *
 * A module that cannot run must not degrade the Shell around it: the user lands on a
 * screen that names the module, says what is wrong in their language rather than the
 * state machine's, and offers the action that could actually help. A toast that
 * disappears is not that, which is why `NavResult.Unavailable` routes here.
 */
@Composable
fun ModuleStatusRoute(moduleId: ModuleId, shell: ShellViewModel = LocalShellViewModel.current) {
    val runtimes by shell.runtimes.collectAsState()
    val runtime = runtimes[moduleId]

    ModuleStatusScreen(
        moduleId = moduleId,
        runtime = runtime,
        onRetry = { shell.onRetryModule(moduleId) },
        // Offered only where an update would actually resolve it (OD-308/OD-309).
        // A module gated on a capability this build does not implement is not fixed
        // by any update, and offering one there teaches users the button is a lie.
        onUpdateApp = if (runtime?.hostUpdateWouldHelp == true) {
            { shell.onUpdateHost() }
        } else {
            null
        },
        onBack = shell::onBack,
    )
}

@Composable
fun ModuleStatusScreen(
    moduleId: ModuleId,
    runtime: ModuleRuntime?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onUpdateApp: (() -> Unit)? = null,
) {
    val name = runtime?.manifest?.displayName?.default ?: moduleId.shortId.replaceFirstChar(Char::titlecase)

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        ErrorSurface(
            title = name,
            message = runtime.explain(),
            // Retrying a kill switch or an incompatible SDK cannot succeed, so the
            // button is only offered where it might: a failed download, a transient
            // initialisation failure, a module simply not fetched yet.
            onRetry = onRetry.takeIf { runtime.isRetryable() },
            onSecondary = onBack,
            secondaryLabel = "Go back",
        )
        onUpdateApp?.let {
            PrimaryButton(
                text = "Update OmniDeck",
                onClick = it,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        runtime?.manifest?.owner?.let { owner ->
            Text(
                text = "Reported to $owner",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.md),
            )
        }
    }
}

private fun ModuleRuntime?.explain(): String = when {
    this == null -> "This module is not part of this build."

    state == ModuleState.QUARANTINED && quarantineCause == QuarantineCause.KILL_SWITCH ->
        reason ?: "Turned off by the OmniDeck team. Nothing you can do from here."

    state == ModuleState.QUARANTINED ->
        "This module kept failing to start, so it has been switched off to protect the rest of " +
            "the app. An update should fix it. (${reason ?: "no further detail"})"

    state == ModuleState.GATED -> reason ?: "Not available on this version of OmniDeck."

    state == ModuleState.FAILED -> "This module could not be loaded. ${reason.orEmpty()}".trim()

    state == ModuleState.INSTALLING -> "Still downloading. Give it a moment and try again."

    else -> reason ?: "This module is temporarily unavailable."
}

/**
 * A retry only makes sense where the same action could produce a different answer.
 * Offering one against a kill switch teaches users the button is a lie.
 */
private fun ModuleRuntime?.isRetryable(): Boolean = when {
    this == null -> false
    quarantineCause == QuarantineCause.KILL_SWITCH -> false
    state == ModuleState.GATED -> false
    else -> true
}
