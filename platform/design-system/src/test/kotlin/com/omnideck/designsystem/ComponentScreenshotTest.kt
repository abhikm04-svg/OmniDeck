package com.omnideck.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import com.omnideck.designsystem.component.EmptySurface
import com.omnideck.designsystem.component.ErrorSurface
import com.omnideck.designsystem.component.OmniTextField
import com.omnideck.designsystem.component.PrimaryButton
import com.omnideck.designsystem.component.SecondaryButton
import com.omnideck.designsystem.component.TertiaryButton
import com.omnideck.designsystem.theme.Spacing
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual coverage for the shared controls (OD-111, OD-113).
 *
 * Every module renders these, so a contrast or spacing regression here lands in all
 * of them at once. That breadth is the argument for a screenshot gate: no module's
 * own tests would ever notice.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1024dp-h800dp-normal-notlong-notround-any-mdpi-keyshidden-nonav")
class ComponentScreenshotTest {

    private fun capture(
        name: String,
        variant: ThemeVariant,
        size: WindowSize = WindowSize.Compact,
        content: @Composable () -> Unit,
    ) {
        captureRoboImage(screenshotPath(name, variant, size)) {
            ScreenshotSubject(variant, size) { content() }
        }
    }

    @Test
    fun buttonWeightsAcrossThemes() {
        // The three weights side by side, because the thing that goes wrong is their
        // relationship — a secondary that reads louder than the primary.
        ThemeVariant.entries.forEach { variant ->
            capture("buttons", variant) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    PrimaryButton(text = "Install", onClick = {})
                    SecondaryButton(text = "Learn more", onClick = {}, icon = Icons.Default.Download)
                    TertiaryButton(text = "Not now", onClick = {})
                }
            }
        }
    }

    @Test
    fun textFieldShowsItsErrorAsTextNotJustColour() {
        // The WCAG point made visible: the failure is legible without relying on the
        // red outline being perceivable.
        ThemeVariant.entries.forEach { variant ->
            capture("textfield-error", variant) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    OmniTextField(
                        value = "notes",
                        onValueChange = {},
                        label = "Module name",
                        supportingText = "Lowercase, no spaces",
                    )
                    OmniTextField(
                        value = "Not Valid",
                        onValueChange = {},
                        label = "Module name",
                        errorText = "Must be lowercase",
                    )
                }
            }
        }
    }

    @Test
    fun stateSurfacesAcrossThemes() {
        // Empty and error are required by the Definition of Done for every story, so
        // they are shipped here rather than rebuilt per module. Loading lives in
        // AnimatedScreenshotTest — its spinner is an infinite animation and cannot be
        // captured on this path at all.
        ThemeVariant.entries.forEach { variant ->
            capture("surface-empty", variant) {
                EmptySurface(
                    title = "No modules yet",
                    message = "Install one from the Catalog to get started.",
                    actionLabel = "Open Catalog",
                    onAction = {},
                )
            }
            capture("surface-error", variant) {
                ErrorSurface(
                    title = "Couldn't load",
                    message = "Check your connection and try again.",
                    onRetry = {},
                )
            }
        }
    }

    @Test
    fun stateSurfacesAtEveryWindowSize() {
        // Centred content is the case most likely to look wrong when stretched, which
        // is what Play's large-screen assessment looks at.
        WindowSize.entries.forEach { size ->
            capture("surface-empty", ThemeVariant.Light, size) {
                EmptySurface(
                    title = "No modules yet",
                    message = "Install one from the Catalog to get started.",
                    actionLabel = "Open Catalog",
                    onAction = {},
                )
            }
        }
    }
}
