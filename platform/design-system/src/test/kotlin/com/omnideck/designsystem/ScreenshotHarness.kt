package com.omnideck.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnideck.designsystem.theme.OmniDeckTheme

/**
 * The matrix the Phase 1 exit gate asks for: every shared component captured in
 * light, dark and dynamic colour, at three window sizes.
 *
 * Breadth is the point. A contrast or layout regression in a shared component reaches
 * every module at once, and no module's own tests would catch it — by the time it
 * shows up it is in four apps' worth of screens.
 */
internal enum class ThemeVariant(val darkTheme: Boolean, val dynamicColor: Boolean) {
    Light(darkTheme = false, dynamicColor = false),
    Dark(darkTheme = true, dynamicColor = false),

    /**
     * Material You. Under Robolectric the platform returns a deterministic palette,
     * so this is stable enough to diff — and it is worth diffing, because dynamic
     * colour is where a hardcoded brand colour stops meeting contrast.
     */
    Dynamic(darkTheme = false, dynamicColor = true),
    ;

    val label: String get() = name.lowercase()
}

/**
 * Window sizes from the Material adaptive breakpoints — phone, unfolded/tablet
 * portrait, and tablet landscape. Play's large-screen quality tiers are assessed at
 * roughly these widths.
 */
internal enum class WindowSize(val widthDp: Int, val heightDp: Int) {
    Compact(widthDp = 360, heightDp = 640),
    Medium(widthDp = 700, heightDp = 900),
    Expanded(widthDp = 1024, heightDp = 800),
    ;

    val label: String get() = name.lowercase()
}

/**
 * Wraps [content] in the real theme at a fixed size, so a capture differs only by the
 * variant under test rather than by ambient device configuration.
 */
@Composable
internal fun ScreenshotSubject(variant: ThemeVariant, size: WindowSize, content: @Composable () -> Unit) {
    OmniDeckTheme(darkTheme = variant.darkTheme, dynamicColor = variant.dynamicColor) {
        Box(
            Modifier
                .size(width = size.widthDp.dp, height = size.heightDp.dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
        ) {
            content()
        }
    }
}

/** Stable file name, so a capture maps to exactly one cell of the matrix. */
internal fun screenshotPath(component: String, variant: ThemeVariant, size: WindowSize): String =
    "src/test/screenshots/$component-${variant.label}-${size.label}.png"
