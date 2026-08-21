package com.omnideck.sdk

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * URI routing. A route is produced by modules, notifications, App Links and app
 * shortcuts alike, so its parsing has to hold for inputs this code did not construct.
 */
class RouteTest {

    // -- parsing ------------------------------------------------------------

    @Test
    fun `a route must carry the scheme`() {
        val error = runCatching { Route("https://example.test/") }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `host is the module short id`() {
        assertThat(Route("omnideck://finance/account/42").host).isEqualTo("finance")
    }

    @Test
    fun `host stops at a query string even with no path`() {
        assertThat(Route("omnideck://finance?x=1").host).isEqualTo("finance")
    }

    @Test
    fun `path excludes the host and the query`() {
        assertThat(Route("omnideck://finance/account/42?x=1").path).isEqualTo("account/42")
    }

    @Test
    fun `a route with no path has an empty one`() {
        assertThat(Route("omnideck://finance").path).isEmpty()
    }

    @Test
    fun `query parameters are parsed into a map`() {
        val query = Route("omnideck://notes/search?q=kotlin&limit=10").query

        assertThat(query).containsExactly("q", "kotlin", "limit", "10")
    }

    @Test
    fun `a valueless query parameter maps to an empty string`() {
        assertThat(Route("omnideck://notes/x?flag").query["flag"]).isEmpty()
    }

    @Test
    fun `a route with no query has an empty map`() {
        assertThat(Route("omnideck://notes/home").query).isEmpty()
    }

    @Test
    fun `of builds a route from a module id`() {
        val route = Route.of(ModuleId("com.omnideck.finance"), "account/42")

        assertThat(route.uri).isEqualTo("omnideck://finance/account/42")
    }

    @Test
    fun `of tolerates a leading slash on the path`() {
        assertThat(Route.of(ModuleId("com.omnideck.finance"), "/account").uri)
            .isEqualTo("omnideck://finance/account")
    }

    @Test
    fun `of with no path yields just the host`() {
        assertThat(Route.of(ModuleId("com.omnideck.finance")).uri).isEqualTo("omnideck://finance")
    }

    // -- correlation ids (OD-205) -------------------------------------------

    @Test
    fun `a route without a correlation id reports null`() {
        assertThat(Route("omnideck://notes/home").correlationId).isNull()
    }

    @Test
    fun `a correlation id round-trips through the query string`() {
        // This is what makes it process-death-safe: it rides in the route, which the
        // back stack persists, rather than in a map that dies with the process.
        val id = CorrelationId("abc-123")

        val route = Route("omnideck://notes/pick").withCorrelationId(id)

        assertThat(route.correlationId).isEqualTo(id)
    }

    @Test
    fun `attaching a correlation id preserves existing query parameters`() {
        val route = Route("omnideck://notes/search?q=kotlin").withCorrelationId(CorrelationId("x"))

        assertThat(route.query["q"]).isEqualTo("kotlin")
        assertThat(route.correlationId).isEqualTo(CorrelationId("x"))
    }

    @Test
    fun `attaching a correlation id replaces any already present`() {
        // A route replayed from the back stack must not accumulate stale ids.
        val route = Route("omnideck://notes/pick")
            .withCorrelationId(CorrelationId("first"))
            .withCorrelationId(CorrelationId("second"))

        assertThat(route.correlationId).isEqualTo(CorrelationId("second"))
        assertThat(route.uri).doesNotContain("first")
    }

    @Test
    fun `a blank correlation id is treated as absent`() {
        assertThat(Route("omnideck://notes/pick?${Route.CORRELATION_KEY}=").correlationId).isNull()
    }

    @Test
    fun `attaching a correlation id keeps the host and path intact`() {
        val route = Route("omnideck://notes/detail/42").withCorrelationId(CorrelationId("x"))

        assertThat(route.host).isEqualTo("notes")
        assertThat(route.path).isEqualTo("detail/42")
    }

    // -- patterns -----------------------------------------------------------

    @Test
    fun `an exact pattern matches its route`() {
        val pattern = RoutePattern("omnideck://notes/home")

        assertThat(pattern.matches(Route("omnideck://notes/home"))).isTrue()
        assertThat(pattern.matches(Route("omnideck://notes/other"))).isFalse()
    }

    @Test
    fun `a placeholder captures a segment`() {
        val pattern = RoutePattern("omnideck://notes/detail/{id}")

        assertThat(pattern.extract(Route("omnideck://notes/detail/42"))).containsExactly("id", "42")
    }

    @Test
    fun `multiple placeholders are all captured`() {
        val pattern = RoutePattern("omnideck://notes/{folder}/item/{id}")

        assertThat(pattern.extract(Route("omnideck://notes/work/item/7")))
            .containsExactly("folder", "work", "id", "7")
    }

    @Test
    fun `a pattern does not match a route of different length`() {
        val pattern = RoutePattern("omnideck://notes/detail/{id}")

        assertThat(pattern.extract(Route("omnideck://notes/detail"))).isNull()
        assertThat(pattern.extract(Route("omnideck://notes/detail/42/extra"))).isNull()
    }

    @Test
    fun `a pattern ignores the query when matching`() {
        val pattern = RoutePattern("omnideck://notes/search")

        assertThat(pattern.matches(Route("omnideck://notes/search?q=kotlin"))).isTrue()
    }

    @Test
    fun `a literal pattern is more specific than a placeholder one`() {
        // Specificity is what makes notes/detail/new reachable alongside
        // notes/detail/{id} — without it the create screen is unreachable.
        val literal = RoutePattern("omnideck://notes/detail/new")
        val placeholder = RoutePattern("omnideck://notes/detail/{id}")

        assertThat(literal.specificity).isGreaterThan(placeholder.specificity)
    }
}
