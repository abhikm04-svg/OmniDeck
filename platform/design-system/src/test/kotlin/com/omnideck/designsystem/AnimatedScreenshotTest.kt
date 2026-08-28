package com.omnideck.designsystem

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.omnideck.designsystem.component.LoadingSurface
import com.omnideck.designsystem.component.ModuleTile
import com.omnideck.designsystem.component.PrimaryButton
import com.omnideck.designsystem.component.SecondaryButton
import com.omnideck.designsystem.component.TileState
import com.omnideck.designsystem.theme.Spacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The three components that render an *indeterminate* progress indicator, captured
 * separately from the rest of the matrix (OD-113).
 *
 * They need their own path because an indeterminate `CircularProgressIndicator` is an
 * infinite animation, and Roborazzi's composable `captureRoboImage` captures through
 * Espresso — which calls `loopMainThreadUntilIdle()`. Compose re-posts a frame through
 * `AndroidUiDispatcher` for as long as the animation runs, so the looper never goes
 * idle and the capture never returns. It is not a deadlock: the thread spins at 100%
 * CPU until something kills it, which on CI was the 60-minute job timeout, with no
 * output after the task line to say which test was responsible.
 *
 * The fix is to own the clock. A Compose rule with `autoAdvance = false` leaves the
 * animation suspended, so the looper drains and the capture proceeds — and because
 * the clock is then advanced by a fixed amount, the spinner is caught at the *same*
 * phase every run. The previous arrangement could not have been stable even if it had
 * completed: whatever arc happened to be on screen became the baseline.
 *
 * Capture is by [android.view.View] rather than the composable overload, since that
 * overload is the one that goes through Espresso. That makes the window itself the
 * frame, which is why the qualifiers below are the subject's size rather than the
 * tablet width the rest of the matrix uses — the captured image has to stay 360x640
 * to mean the same thing as its neighbours.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-normal-notlong-notround-any-mdpi-keyshidden-nonav")
class AnimatedScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val variant = mutableStateOf(ThemeVariant.Light)

    /**
     * One `setContent` per test is all a Compose rule allows, so the theme is state
     * the recomposition reads rather than a parameter — which is what lets a single
     * test still cover the light/dark/dynamic row of the matrix.
     */
    private fun captureAcrossThemes(
        name: String,
        variants: List<ThemeVariant> = ThemeVariant.entries,
        content: @Composable () -> Unit,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ScreenshotSubject(variant.value, SIZE) { content() }
        }
        variants.forEach { themeVariant ->
            variant.value = themeVariant
            // Advances composition and the animation together, by a fixed amount, so
            // the indicator is at a reproducible angle rather than wherever the host
            // machine's scheduling left it.
            composeRule.mainClock.advanceTimeBy(SETTLE_MS)
            composeRule.activity.window.decorView
                .captureRoboImage(screenshotPath(name, themeVariant, SIZE))
        }
    }

    @Test
    fun installingTileShowsProgress() {
        captureAcrossThemes("tile-installing") {
            ModuleTile(
                title = "Notes",
                subtitle = "Capture thoughts",
                state = TileState.Installing(fraction = 0.45f),
                onClick = {},
            )
        }
    }

    @Test
    fun loadingSurfaceAcrossThemes() {
        captureAcrossThemes("surface-loading") {
            LoadingSurface(label = "Starting OmniDeck…")
        }
    }

    @Test
    fun buttonStatesShowDisabledAndBusy() {
        // A busy button must look unavailable, not merely decorated with a spinner.
        captureAcrossThemes("buttons-states", variants = listOf(ThemeVariant.Light)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PrimaryButton(text = "Install", onClick = {}, enabled = false)
                PrimaryButton(text = "Installing", onClick = {}, loading = true)
                SecondaryButton(text = "Working", onClick = {}, loading = true)
            }
        }
    }

    private companion object {
        val SIZE = WindowSize.Compact

        /** Long enough for layout and one animation frame; short enough to stay quick. */
        const val SETTLE_MS = 300L
    }
}
