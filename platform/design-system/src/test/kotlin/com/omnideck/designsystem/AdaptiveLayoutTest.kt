package com.omnideck.designsystem

import com.google.common.truth.Truth.assertThat
import com.omnideck.designsystem.layout.ReadableContentWidth
import com.omnideck.designsystem.layout.WindowWidthClass
import com.omnideck.designsystem.layout.contentPadding
import com.omnideck.designsystem.layout.moduleGridColumns
import org.junit.Test

/**
 * Adaptive layout decisions (OD-112).
 *
 * These are pure functions on purpose: the choice of how many columns a tablet gets
 * is exactly the sort of thing that otherwise only ever gets verified by looking at a
 * device, and Play assesses large-screen quality on precisely this — a phone layout
 * stretched across a tablet is the usual way an app loses its large-screen tier.
 */
class AdaptiveLayoutTest {

    @Test
    fun `width classes follow the Material breakpoints`() {
        assertThat(WindowWidthClass.fromWidth(360)).isEqualTo(WindowWidthClass.Compact)
        assertThat(WindowWidthClass.fromWidth(599)).isEqualTo(WindowWidthClass.Compact)
        assertThat(WindowWidthClass.fromWidth(600)).isEqualTo(WindowWidthClass.Medium)
        assertThat(WindowWidthClass.fromWidth(839)).isEqualTo(WindowWidthClass.Medium)
        assertThat(WindowWidthClass.fromWidth(840)).isEqualTo(WindowWidthClass.Expanded)
        assertThat(WindowWidthClass.fromWidth(1600)).isEqualTo(WindowWidthClass.Expanded)
    }

    @Test
    fun `a folded foldable is compact and an unfolded one is not`() {
        // The transition that has to be right for foldable certification: the same
        // app, two widths, two layouts.
        assertThat(WindowWidthClass.fromWidth(widthDp = 400)).isEqualTo(WindowWidthClass.Compact)
        assertThat(WindowWidthClass.fromWidth(widthDp = 700)).isEqualTo(WindowWidthClass.Medium)
    }

    @Test
    fun `grid columns grow with the window`() {
        assertThat(moduleGridColumns(WindowWidthClass.Compact)).isEqualTo(1)
        assertThat(moduleGridColumns(WindowWidthClass.Medium)).isEqualTo(2)
        assertThat(moduleGridColumns(WindowWidthClass.Expanded)).isEqualTo(3)
    }

    @Test
    fun `content padding grows with the window`() {
        val compact = contentPadding(WindowWidthClass.Compact)
        val medium = contentPadding(WindowWidthClass.Medium)
        val expanded = contentPadding(WindowWidthClass.Expanded)

        assertThat(compact.value).isLessThan(medium.value)
        assertThat(medium.value).isLessThan(expanded.value)
    }

    @Test
    fun `prose stops growing rather than stretching across a wide window`() {
        // Long lines are hard to read, so an expanded window caps the column instead
        // of filling it — otherwise a help page on a tablet is one enormous line.
        assertThat(ReadableContentWidth.value).isAtMost(720f)
        assertThat(ReadableContentWidth.value).isAtLeast(480f)
    }

    @Test
    fun `every width class yields a usable column count`() {
        // Guards a future class being added without a grid rule, which would
        // otherwise surface as a zero-column grid rendering nothing.
        WindowWidthClass.entries.forEach { widthClass ->
            assertThat(moduleGridColumns(widthClass)).isAtLeast(1)
            assertThat(contentPadding(widthClass).value).isGreaterThan(0f)
        }
    }
}
