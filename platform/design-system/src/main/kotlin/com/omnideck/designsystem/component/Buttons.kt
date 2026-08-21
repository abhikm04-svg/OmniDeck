package com.omnideck.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.omnideck.designsystem.theme.Spacing

/**
 * The three button weights every module uses (OD-111).
 *
 * Two things are handled here so no module has to remember them:
 *
 *  - **Minimum touch target.** WCAG 2.2 AA and Material both want 48 dp; Compose's
 *    buttons are shorter than that by default, so every module would otherwise ship
 *    targets that fail an accessibility audit (QA-12, OD-114).
 *  - **The busy state.** A button that stays tappable during its own action produces
 *    duplicate purchases and double-submits. Passing `loading` disables it and
 *    announces the state to TalkBack rather than only showing a spinner.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.accessibleTouchTarget().busySemantics(loading),
    ) {
        ButtonContent(text, icon, loading)
    }
}

/** Secondary weight: a real alternative to the primary action, not a lesser one. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.accessibleTouchTarget().busySemantics(loading),
    ) {
        ButtonContent(text, icon, loading)
    }
}

/** Lowest weight, for dismissals and "not now". */
@Composable
fun TertiaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.accessibleTouchTarget(),
    ) {
        Text(text)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, loading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )

            icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        if (loading || icon != null) {
            Text(text, modifier = Modifier.padding(start = Spacing.sm))
        } else {
            Text(text)
        }
    }
}

/**
 * Enforces the 48 dp minimum. Applied by every control in this package rather than
 * left to callers, because a missed target is invisible until an audit finds it.
 */
internal fun Modifier.accessibleTouchTarget(): Modifier =
    defaultMinSize(minWidth = Spacing.minTouchTarget, minHeight = Spacing.minTouchTarget)

/**
 * A spinner is a visual-only signal. Screen readers need the state announced, or a
 * TalkBack user hears an ordinary enabled button and taps it again.
 */
internal fun Modifier.busySemantics(loading: Boolean): Modifier =
    if (!loading) this else semantics { stateDescription = "Busy" }
