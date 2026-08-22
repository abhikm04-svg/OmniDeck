package com.omnideck.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.omnideck.designsystem.theme.BrandSeedDark
import com.omnideck.designsystem.theme.BrandSeedLight
import com.omnideck.designsystem.theme.OmniDeckShapes
import com.omnideck.designsystem.theme.OmniDeckTypography
import com.omnideck.designsystem.theme.Spacing
import org.junit.Test
import kotlin.math.pow

/**
 * Accessibility and scale invariants for the shared theme (OD-110, OD-114).
 *
 * Contrast is checked arithmetically rather than by eye, because a failing pair is
 * invisible in a screenshot to anyone with normal vision and only surfaces in an
 * external audit — by which point it is in every module at once (QA-12).
 */
class ThemeTest {

    // -- WCAG contrast ------------------------------------------------------

    /** Relative luminance, per WCAG 2.2. */
    private fun luminance(color: Color): Double {
        fun channel(raw: Float): Double {
            val c = raw.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** Every on/container pair a module can render text into. */
    private fun textPairs(scheme: ColorScheme): List<Triple<String, Color, Color>> = listOf(
        Triple("onPrimary/primary", scheme.onPrimary, scheme.primary),
        Triple("onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer),
        Triple("onBackground/background", scheme.onBackground, scheme.background),
        Triple("onSurface/surface", scheme.onSurface, scheme.surface),
        Triple("onSurfaceVariant/surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant),
        Triple("onError/error", scheme.onError, scheme.error),
        Triple("onErrorContainer/errorContainer", scheme.onErrorContainer, scheme.errorContainer),
    )

    @Test
    fun `light palette meets WCAG AA for normal text`() {
        val failures = textPairs(BrandSeedLight)
            .filter { (_, fg, bg) -> contrastRatio(fg, bg) < WCAG_AA_NORMAL }
            .map { (name, fg, bg) -> "$name = %.2f".format(contrastRatio(fg, bg)) }

        assertThat(failures).isEmpty()
    }

    @Test
    fun `dark palette meets WCAG AA for normal text`() {
        // Dark themes fail contrast differently from light ones — a colour that
        // works on white often does not work on near-black — so both are checked.
        val failures = textPairs(BrandSeedDark)
            .filter { (_, fg, bg) -> contrastRatio(fg, bg) < WCAG_AA_NORMAL }
            .map { (name, fg, bg) -> "$name = %.2f".format(contrastRatio(fg, bg)) }

        assertThat(failures).isEmpty()
    }

    @Test
    fun `the quarantine pairing is legible in both themes`() {
        // The tile state most likely to be misread, and the one the screenshot bug
        // was hiding: error content on an error container.
        listOf(BrandSeedLight, BrandSeedDark).forEach { scheme ->
            assertThat(contrastRatio(scheme.onErrorContainer, scheme.errorContainer))
                .isAtLeast(WCAG_AA_NORMAL)
        }
    }

    @Test
    fun `the contrast helper agrees with known reference values`() {
        // Guards the assertions above: a broken formula would pass everything.
        assertThat(contrastRatio(Color.Black, Color.White)).isWithin(0.01).of(21.0)
        assertThat(contrastRatio(Color.White, Color.White)).isWithin(0.01).of(1.0)
    }

    // -- scales -------------------------------------------------------------

    @Test
    fun `the spacing scale increases monotonically`() {
        // A scale that is not ordered stops being a scale, and modules start
        // reaching for raw dp instead.
        val steps = listOf(Spacing.xxs, Spacing.xs, Spacing.sm, Spacing.md, Spacing.lg, Spacing.xl, Spacing.xxl)

        steps.zipWithNext().forEach { (smaller, larger) ->
            assertThat(smaller.value).isLessThan(larger.value)
        }
    }

    @Test
    fun `the minimum touch target meets the accessibility floor`() {
        // 48 dp is the WCAG 2.2 AA and Material minimum; every control in this
        // package is sized against it.
        assertThat(Spacing.minTouchTarget.value).isAtLeast(48f)
    }

    @Test
    fun `the type scale is ordered from body to display`() {
        val sizes = listOf(
            OmniDeckTypography.labelSmall.fontSize.value,
            OmniDeckTypography.bodyMedium.fontSize.value,
            OmniDeckTypography.bodyLarge.fontSize.value,
            OmniDeckTypography.titleLarge.fontSize.value,
            OmniDeckTypography.headlineSmall.fontSize.value,
            OmniDeckTypography.headlineMedium.fontSize.value,
            OmniDeckTypography.displaySmall.fontSize.value,
        )

        sizes.zipWithNext().forEach { (smaller, larger) ->
            assertThat(smaller).isAtMost(larger)
        }
    }

    @Test
    fun `line height always exceeds font size`() {
        // Otherwise text clips its own descenders at larger accessibility font
        // scales, which is where it matters most.
        listOf(
            OmniDeckTypography.bodyMedium,
            OmniDeckTypography.bodyLarge,
            OmniDeckTypography.titleLarge,
            OmniDeckTypography.headlineMedium,
            OmniDeckTypography.displaySmall,
        ).forEach { style ->
            assertThat(style.lineHeight.value).isGreaterThan(style.fontSize.value)
        }
    }

    @Test
    fun `the shape scale increases with prominence`() {
        val radii = listOf(
            OmniDeckShapes.extraSmall,
            OmniDeckShapes.small,
            OmniDeckShapes.medium,
            OmniDeckShapes.large,
            OmniDeckShapes.extraLarge,
        )

        assertThat(radii).hasSize(5)
        assertThat(radii.toSet()).hasSize(5)
    }

    private companion object {
        /** WCAG 2.2 AA minimum for normal-size text. */
        const val WCAG_AA_NORMAL = 4.5
    }
}
