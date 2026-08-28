package com.omnideck.shell

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.RoutePattern
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
        assertThat(routeFor("https://omnideck.app/go/notes/note/42")?.uri).isEqualTo("omnideck://notes/note/42")
    }

    @Test
    fun `a web link keeps its query, so a correlation id survives the round trip`() {
        assertThat(routeFor("https://omnideck.app/go/notes/new?omnideck_result_to=abc")?.correlationId?.value)
            .isEqualTo("abc")
    }

    @Test
    fun `a trailing slash is not a different destination`() {
        assertThat(routeFor("https://omnideck.app/go/notes/home/")?.uri).isEqualTo("omnideck://notes/home")
    }

    @Test
    fun `the bare web origin addresses nothing`() {
        assertThat(routeFor("https://omnideck.app")).isNull()
        assertThat(routeFor("https://omnideck.app/")).isNull()
    }

    @Test
    fun `the reserved prefix on its own addresses nothing either`() {
        assertThat(routeFor("https://omnideck.app/go")).isNull()
        assertThat(routeFor("https://omnideck.app/go/")).isNull()
    }

    @Test
    fun `the website outside the reserved prefix is not ours to open`() {
        // OD-321 flips autoVerify, and a filter matching these would take them away
        // from the browser for good. Account deletion has to stay a public web page
        // (architecture.md 19.2); the rest is ordinary marketing.
        assertThat(routeFor("https://omnideck.app/delete-account")).isNull()
        assertThat(routeFor("https://omnideck.app/pricing")).isNull()
        assertThat(routeFor("https://omnideck.app/.well-known/assetlinks.json")).isNull()
        // Not a prefix match on the string: /gonzo is not /go/.
        assertThat(routeFor("https://omnideck.app/gonzo/notes/home")).isNull()
    }

    @Test
    fun `another origin is not ours to open`() {
        // The filter is on the host, not on the shape of the path — a look-alike
        // domain must not get the Shell to act on its links.
        assertThat(routeFor("https://omnideck.app.evil.test/go/notes/home")).isNull()
        assertThat(routeFor("https://example.test/go/notes/home")).isNull()
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
        assertThat(routeFor("HTTPS://OmniDeck.app/go/notes/home")?.uri).isEqualTo("omnideck://notes/home")
    }

    @Test
    fun `the app scheme is case-insensitive too, not just the web one`() {
        // The OS does not promise to normalise intent data, and a sender that shouts
        // the scheme is addressing the same destination.
        assertThat(routeFor("OMNIDECK://notes/note/42")?.uri).isEqualTo("omnideck://notes/note/42")
        assertThat(routeFor("OmniDeck://notes/home")?.uri).isEqualTo("omnideck://notes/home")
    }

    @Test
    fun `a scheme with no host addresses nothing`() {
        // Route only checks the prefix, so these are well-formed Routes that resolve
        // to no destination. They must not reach the Router as though asked for.
        assertThat(routeFor("omnideck://")).isNull()
        assertThat(routeFor("omnideck://?omnideck_result_to=abc")).isNull()
    }

    @Test
    fun `a fragment is dropped at either door, not just the web one`() {
        // Route treats '?' as its only delimiter, so a surviving # is absorbed rather
        // than ignored — see the two tests below for what that costs.
        assertThat(routeFor("https://omnideck.app/go/notes/note/42#comments")?.uri)
            .isEqualTo("omnideck://notes/note/42")
        assertThat(routeFor("omnideck://notes/note/42#comments")?.uri)
            .isEqualTo("omnideck://notes/note/42")
    }

    @Test
    fun `a fragment cannot smuggle itself into a path argument`() {
        // Unstripped, RoutePattern binds noteId = "42#comments" and RouteArgs.int
        // throws on a route the Shell accepted as well-formed.
        val route = routeFor("omnideck://notes/note/42#comments")!!
        val args = RoutePattern("omnideck://notes/note/{noteId}").extract(route)
        assertThat(args).containsExactly("noteId", "42")
    }

    @Test
    fun `a fragment cannot corrupt the correlation id`() {
        // Unstripped this reads "abc#frag", which matches no result the Router issued,
        // so navigateForResult silently never delivers.
        assertThat(routeFor("omnideck://notes/new?omnideck_result_to=abc#frag")?.correlationId?.value)
            .isEqualTo("abc")
    }

    @Test
    fun `a bare fragment addresses nothing`() {
        assertThat(routeFor("omnideck://#frag")).isNull()
    }

    @Test
    fun `the Shell's own screens are addressable like any module's`() {
        assertThat(routeFor("https://omnideck.app/go/shell/settings")?.uri).isEqualTo(ShellRoutes.SETTINGS)
        assertThat(routeFor("omnideck://shell/privacy")?.uri).isEqualTo(ShellRoutes.PRIVACY)
    }

    private fun routeFor(uri: String) = ExternalRoutes.from(Uri.parse(uri))
}
