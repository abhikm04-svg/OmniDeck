package com.omnideck.sdk

import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.capability.NavResult
import com.omnideck.sdk.capability.NavResultValue
import com.omnideck.sdk.capability.Router
import com.omnideck.sdk.capability.navigateForResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Navigation outcomes, and the reified overloads modules actually call.
 *
 * Every `NavResult` variant is a distinct thing the UI has to render — a spinner, an
 * error, a "get this module" prompt — so a caller that cannot tell them apart shows
 * the wrong screen. The `when` here stands in for that rendering decision.
 */
class NavigationResultTest {

    private val route = Route("omnideck://notes/home")
    private val notes = ModuleId("com.omnideck.notes")

    @Test
    fun `every navigation outcome is distinguishable`() {
        val outcomes: List<NavResult> = listOf(
            NavResult.Navigated(route),
            NavResult.NavigatedAfterInstall(route, notes),
            NavResult.Unhandled(route),
            NavResult.Unavailable(notes, "quarantined"),
            NavResult.AcquisitionAborted(notes, "cancelled"),
        )

        val rendered = outcomes.map { outcome ->
            when (outcome) {
                is NavResult.Navigated -> "shown"
                is NavResult.NavigatedAfterInstall -> "installed-then-shown"
                is NavResult.Unhandled -> "no-handler"
                is NavResult.Unavailable -> "unavailable:${outcome.reason}"
                is NavResult.AcquisitionAborted -> "aborted:${outcome.reason}"
            }
        }

        assertThat(rendered).containsExactly(
            "shown",
            "installed-then-shown",
            "no-handler",
            "unavailable:quarantined",
            "aborted:cancelled",
        ).inOrder()
    }

    @Test
    fun `an install-then-navigate outcome names the module that was fetched`() {
        // The Catalog uses this to mark the module as owned without re-querying.
        val outcome = NavResult.NavigatedAfterInstall(route, notes)

        assertThat(outcome.moduleId).isEqualTo(notes)
        assertThat(outcome.route).isEqualTo(route)
    }

    @Test
    fun `result values distinguish success, cancellation and failure`() {
        // Cancelled is not a failure: the user backing out of a picker is a normal
        // path, and treating it as an error would surface a needless message.
        val values: List<NavResultValue<String>> = listOf(
            NavResultValue.Success("picked"),
            NavResultValue.Cancelled,
            NavResultValue.Failed("type mismatch"),
        )

        val rendered = values.map { value ->
            when (value) {
                is NavResultValue.Success -> "value:${value.value}"
                is NavResultValue.Cancelled -> "cancelled"
                is NavResultValue.Failed -> "failed:${value.reason}"
            }
        }

        assertThat(rendered).containsExactly("value:picked", "cancelled", "failed:type mismatch").inOrder()
    }

    // -- reified overloads --------------------------------------------------

    private class StubRouter : Router {
        var requestedType: Class<*>? = null

        override suspend fun navigate(route: Route) = NavResult.Navigated(route)

        override fun <T : Any> navigateForResult(route: Route, type: Class<T>): Flow<NavResultValue<T>> {
            requestedType = type
            @Suppress("UNCHECKED_CAST")
            return flowOf(NavResultValue.Cancelled as NavResultValue<T>)
        }

        override fun canHandle(route: Route) = true
        override fun back() = true
        override fun <T : Any> setResult(correlationId: CorrelationId, value: T) = Unit
    }

    @Test
    fun `the reified navigateForResult infers the expected type`() {
        // The overload module authors call; if inference broke they would have to
        // pass a Class literal at every call site.
        val router = StubRouter()

        runTest {
            val result = router.navigateForResult<String>(route).first()

            assertThat(router.requestedType).isEqualTo(String::class.java)
            assertThat(result).isEqualTo(NavResultValue.Cancelled)
        }
    }

    @Test
    fun `the reified capability accessor infers the expected type`() {
        // PlatformServices is a wide interface, so this stubs only the one member
        // under test rather than hand-writing every capability accessor.
        val services = mockk<PlatformServices>()
        val requestedType = slot<Class<*>>()
        every { services.capability(any(), capture(requestedType)) } returns null

        val resolved: String? = services.capability(CapabilityId("omnideck.test.x"))

        assertThat(requestedType.captured).isEqualTo(String::class.java)
        assertThat(resolved).isNull()
    }
}
