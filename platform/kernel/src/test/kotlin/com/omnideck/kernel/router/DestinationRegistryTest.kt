package com.omnideck.kernel.router

import androidx.compose.runtime.Composable
import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.Route
import com.omnideck.sdk.RouteArgs
import org.junit.Test

/**
 * Route ownership.
 *
 * A module declares its routes in its own manifest, so nothing stops one *asking* to
 * own `omnideck://payments/...`. The registry refuses instead of trusting the
 * declaration — which is what keeps a module from hijacking another's deep links,
 * including the ones in its notifications and App Links.
 */
class DestinationRegistryTest {

    private val notes = ModuleId("com.omnideck.notes")
    private val finance = ModuleId("com.omnideck.finance")

    private val content: @Composable (RouteArgs) -> Unit = {}

    // -- ownership enforcement ---------------------------------------------

    @Test
    fun `a module may register under its own short id`() {
        val registry = MutableDestinationRegistry()

        registry.scopedTo(notes).destination("omnideck://notes/home", content)

        assertThat(registry.ownerOf(Route("omnideck://notes/home"))).isEqualTo(notes)
    }

    @Test
    fun `a module cannot register a route belonging to another`() {
        val registry = MutableDestinationRegistry()

        val error = runCatching {
            registry.scopedTo(notes).destination("omnideck://payments/checkout", content)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("belongs to")
    }

    @Test
    fun `the same route cannot be registered twice`() {
        val registry = MutableDestinationRegistry()
        registry.scopedTo(notes).destination("omnideck://notes/home", content)

        val error = runCatching {
            registry.scopedTo(notes).destination("omnideck://notes/home", content)
        }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("already registered")
    }

    // -- resolution ---------------------------------------------------------

    @Test
    fun `an unregistered route resolves to null`() {
        assertThat(MutableDestinationRegistry().resolve(Route("omnideck://ghost/home"))).isNull()
    }

    @Test
    fun `a placeholder route captures its arguments`() {
        val registry = MutableDestinationRegistry()
        registry.scopedTo(notes).destination("omnideck://notes/detail/{id}", content)

        val (_, args) = registry.resolve(Route("omnideck://notes/detail/42"))!!

        assertThat(args.string("id")).isEqualTo("42")
    }

    @Test
    fun `a literal segment wins over a placeholder`() {
        // Otherwise `notes/detail/new` would be swallowed by `notes/detail/{id}` and
        // the "create" screen would be unreachable.
        val registry = MutableDestinationRegistry()
        val scoped = registry.scopedTo(notes)
        scoped.destination("omnideck://notes/detail/{id}", content)
        scoped.destination("omnideck://notes/detail/new", content)

        val (destination, _) = registry.resolve(Route("omnideck://notes/detail/new"))!!

        assertThat(destination.pattern.pattern).isEqualTo("omnideck://notes/detail/new")
    }

    @Test
    fun `query parameters are merged into the arguments`() {
        val registry = MutableDestinationRegistry()
        registry.scopedTo(notes).destination("omnideck://notes/search", content)

        val (_, args) = registry.resolve(Route("omnideck://notes/search?q=kotlin"))!!

        assertThat(args.string("q")).isEqualTo("kotlin")
    }

    // -- removal ------------------------------------------------------------

    @Test
    fun `removing a module withdraws only its destinations`() {
        // Quarantine relies on this: a disabled module's routes must stop resolving
        // while everyone else keeps working.
        val registry = MutableDestinationRegistry()
        registry.scopedTo(notes).destination("omnideck://notes/home", content)
        registry.scopedTo(finance).destination("omnideck://finance/home", content)

        registry.removeAll(notes)

        assertThat(registry.resolve(Route("omnideck://notes/home"))).isNull()
        assertThat(registry.ownerOf(Route("omnideck://finance/home"))).isEqualTo(finance)
    }

    @Test
    fun `a route can be re-registered after its module is removed`() {
        // Reactivation after quarantine must not trip the duplicate check.
        val registry = MutableDestinationRegistry()
        registry.scopedTo(notes).destination("omnideck://notes/home", content)
        registry.removeAll(notes)

        registry.scopedTo(notes).destination("omnideck://notes/home", content)

        assertThat(registry.ownerOf(Route("omnideck://notes/home"))).isEqualTo(notes)
    }

    @Test
    fun `the destinations flow tracks registration and removal`() {
        // ShellNavHost collects this so a just-installed module's screen appears
        // without further navigation.
        val registry = MutableDestinationRegistry()
        assertThat(registry.destinations.value).isEmpty()

        registry.scopedTo(notes).destination("omnideck://notes/home", content)
        assertThat(registry.destinations.value).hasSize(1)

        registry.removeAll(notes)
        assertThat(registry.destinations.value).isEmpty()
    }
}
