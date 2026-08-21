package com.omnideck.designsystem

import com.github.takahirom.roborazzi.captureRoboImage
import com.omnideck.designsystem.component.ModuleTile
import com.omnideck.designsystem.component.TileState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tile is the most-seen component in the app — every module appears as one on
 * Home and in the Catalog — and it renders all five lifecycle states. A regression
 * here is visible on the first screen a user sees.
 *
 * Captured through Roborazzi's composable overload rather than a ComposeTestRule,
 * because a rule allows only one `setContent` per test and the matrix needs many
 * captures per case.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1024dp-h800dp-normal-notlong-notround-any-mdpi-keyshidden-nonav")
class ModuleTileScreenshotTest {

    private fun capture(name: String, variant: ThemeVariant, size: WindowSize, state: TileState) {
        captureRoboImage(screenshotPath(name, variant, size)) {
            ScreenshotSubject(variant, size) {
                ModuleTile(title = "Notes", subtitle = "Capture thoughts", state = state, onClick = {})
            }
        }
    }

    @Test
    fun readyTileAcrossThemesAndSizes() {
        // The full exit-gate matrix for the component that matters most.
        ThemeVariant.entries.forEach { variant ->
            WindowSize.entries.forEach { size ->
                capture("tile-ready", variant, size, TileState.Ready)
            }
        }
    }

    @Test
    fun availableTileShowsDownloadSize() {
        // The install affordance: size is what a user weighs before tapping, so it
        // must survive truncation at the narrowest width.
        ThemeVariant.entries.forEach { variant ->
            capture("tile-available", variant, WindowSize.Compact, TileState.Available(downloadMb = 4.2))
        }
    }

    @Test
    fun installingTileShowsProgress() {
        ThemeVariant.entries.forEach { variant ->
            capture("tile-installing", variant, WindowSize.Compact, TileState.Installing(fraction = 0.45f))
        }
    }

    @Test
    fun gatedTileExplainsWhyItIsLocked() {
        ThemeVariant.entries.forEach { variant ->
            capture("tile-gated", variant, WindowSize.Compact, TileState.Gated("Requires OmniDeck Pro"))
        }
    }

    @Test
    fun quarantinedTileUsesTheErrorContainer() {
        // The one state rendered on error colours; contrast here is the thing most
        // likely to break under dynamic colour.
        ThemeVariant.entries.forEach { variant ->
            capture("tile-quarantined", variant, WindowSize.Compact, TileState.Quarantined("Temporarily unavailable"))
        }
    }

    @Test
    fun longTitlesTruncateRatherThanReflow() {
        // Module names are author-supplied and localised, so the tile has to hold its
        // shape for a name far longer than the design mock used.
        capture(
            name = "tile-long-title",
            variant = ThemeVariant.Light,
            size = WindowSize.Compact,
            state = TileState.Gated("A very long explanation of why this module is currently unavailable to you"),
        )
    }
}
