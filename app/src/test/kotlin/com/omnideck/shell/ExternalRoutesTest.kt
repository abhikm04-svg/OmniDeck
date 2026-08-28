package com.omnideck.shell

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.omnideck.shell.navigation.ExternalRoutes
import com.omnideck.shell.navigation.ShellRoutes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OD-204. Every external entry point converges here, which makes this the Shell's
 * attack surface as much as its convenience: the interesting cases are the ones that
 * must *not* become a route.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalRoutesTest {

    @Test
    fun `an omnideck uri passes through unchanged`() {
        assertThat(routeFor("omnideck://notes/note/42?x=1")?.uri).isEqualTo("omnideck://notes/note/42?x=1")
    }

    @Test
    fun `a web link maps onto the same path in the app`() {
        assertThat(routeFor("https://omnideck.app/notes/note/42")?.uri).isEqualTo("omnideck://notes/note/42")
    }

    @Test
    fun `a web link keeps its query, so a correlation id survives the round trip`() {
        assertThat(routeFor("https://omnideck.app/notes/new?omnideck_result_to=abc")?.correlationId?.value)
            .isEqualTo("abc")
    }

    @Test
    fun `a trailing slash is not a different destination`() {
        assertThat(routeFor("https://omnideck.app/notes/home/")?.uri).isEqualTo("omnideck://notes/home")
    }

    @Test
    fun `the bare web origin addresses nothing`() {
        assertThat(routeFor("https://omnideck.app")).isNull()
        assertThat(routeFor("https://omnideck.app/")).isNull()
    }

    @Test
    fun `another origin is not ours to open`() {
        // The filter is on the host, not on the shape of the path — a look-alike
        // domain must not get the Shell to act on its links.
        assertThat(routeFor("https://omnideck.app.evil.test/notes/home")).isNull()
        assertThat(routeFor("https://example.test/notes/home")).isNull()
    }

    @Test
    fun `an unrelated scheme is dropped rather than guessed at`() {
        assertThat(routeFor("mailto:someone@example.test")).isNull()
        assertThat(routeFor("file:///data/data/com.omnideck.shell/databases/shell.db")).isNull()
        assertThat(routeFor("javascript:alert(1)")).isNull()
    }

    @Test
    fun `no data at all is not an error`() {
        assertThat(ExternalRoutes.from(null)).isNull()
    }

    @Test
    fun `the scheme is matched case-insensitively, as the OS delivers it`() {
        assertThat(routeFor("HTTPS://OmniDeck.app/notes/home")?.uri).isEqualTo("omnideck://notes/home")
    }

    @Test
    fun `the Shell's own screens are addressable like any module's`() {
        assertThat(routeFor("https://omnideck.app/shell/settings")?.uri).isEqualTo(ShellRoutes.SETTINGS)
        assertThat(routeFor("omnideck://shell/privacy")?.uri).isEqualTo(ShellRoutes.PRIVACY)
    }

    private fun routeFor(uri: String) = ExternalRoutes.from(Uri.parse(uri))
}
