package com.omnideck.designsystem

import com.google.common.truth.Truth.assertThat
import com.omnideck.designsystem.component.TileState
import org.junit.Test

/**
 * The tile's text, including what TalkBack reads out (OD-114).
 *
 * A screenshot proves a sighted user sees the right thing; only this proves a
 * screen-reader user hears it. The two have diverged before in real apps — a state
 * rendered purely as an icon colour is invisible to both TalkBack and to anyone who
 * cannot distinguish the colour.
 */
class TileStateTest {

    @Test
    fun `an installed tile shows the module's own subtitle`() {
        // Null caption means "fall through to the subtitle" — the tile has nothing
        // of its own to say once a module is simply working.
        assertThat(TileState.Ready.captionOrNull).isNull()
    }

    @Test
    fun `an available tile states the download size and what tapping does`() {
        // Size is what a user weighs before tapping, especially on mobile data.
        val caption = TileState.Available(downloadMb = 4.5).captionOrNull

        assertThat(caption).contains("4.5")
        assertThat(caption).contains("MB")
        assertThat(caption).contains("tap to install")
    }

    @Test
    fun `download size is shown to one decimal place, rounded up at the halfway point`() {
        // Pinned because it is a user-facing number: a module advertised as smaller
        // than it downloads is worse than one advertised as slightly larger.
        assertThat(TileState.Available(downloadMb = 4.25).captionOrNull).contains("4.3")
        assertThat(TileState.Available(downloadMb = 12.041).captionOrNull).contains("12.0")
    }

    @Test
    fun `gated and quarantined tiles surface their reason rather than a generic message`() {
        // "Unavailable" with no reason is the most frustrating possible state; the
        // caller always has something more specific.
        assertThat(TileState.Gated("Requires Pro").captionOrNull).isEqualTo("Requires Pro")
        assertThat(TileState.Quarantined("Under maintenance").captionOrNull)
            .isEqualTo("Under maintenance")
    }

    @Test
    fun `every state has a distinct accessibility label`() {
        // Two states reading identically to TalkBack would make them
        // indistinguishable to a screen-reader user even though they look different.
        val labels = listOf(
            TileState.Ready,
            TileState.Available(1.0),
            TileState.Installing(null),
            TileState.Gated("locked"),
            TileState.Quarantined("broken"),
        ).map { it.accessibilityLabel }

        assertThat(labels).containsNoDuplicates()
        assertThat(labels).doesNotContain("")
    }

    @Test
    fun `accessibility labels carry the reason for unavailable states`() {
        // The reason must reach a screen reader too, not only the visible caption.
        assertThat(TileState.Gated("Requires Pro").accessibilityLabel).contains("Requires Pro")
        assertThat(TileState.Quarantined("Under maintenance").accessibilityLabel)
            .contains("Under maintenance")
    }

    @Test
    fun `an available tile tells a screen reader it is not installed`() {
        // Sighted users get a download icon; this is its spoken equivalent.
        assertThat(TileState.Available(2.0).accessibilityLabel).contains("Not installed")
    }

    @Test
    fun `installing progress is optional so an indeterminate state is expressible`() {
        // Play does not always report byte counts, and a determinate bar stuck at
        // zero reads as broken.
        assertThat(TileState.Installing(fraction = null).captionOrNull).isEqualTo("Installing…")
        assertThat(TileState.Installing(fraction = 0.5f).captionOrNull).isEqualTo("Installing…")
    }
}
