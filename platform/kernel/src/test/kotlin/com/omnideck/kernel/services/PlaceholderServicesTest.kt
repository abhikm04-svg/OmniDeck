package com.omnideck.kernel.services

import com.google.common.truth.Truth.assertThat
import com.omnideck.kernel.lifecycle.moduleId
import com.omnideck.sdk.Sku
import com.omnideck.sdk.capability.AuthException
import com.omnideck.sdk.capability.AuthService
import com.omnideck.sdk.capability.BillingService
import com.omnideck.sdk.capability.SessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The capabilities that are deliberately not implemented yet.
 *
 * These look like the least interesting code in the kernel and are close to the most
 * important to pin down. A module written in Phase 2 runs against a signed-out
 * platform with nothing purchased and no media picker, and the contract it is written
 * against is *this* behaviour — a specific exception, an empty set, a `false`. If a
 * placeholder later starts returning something else, or throws where it used to
 * return, every module built on it changes behaviour at once with no module-level
 * test to catch it (architecture.md §6.3).
 *
 * So these assert the shape of "not yet", not merely that the calls exist. Each one
 * names the ticket that will replace it, and should be rewritten — not deleted — when
 * that ticket lands.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceholderServicesTest {

    // --- AnonymousAuthService: signed out until OD-401 ----------------------------

    @Test
    fun `auth starts signed out`() = runTest {
        assertThat(AnonymousAuthService().sessionState.first()).isEqualTo(SessionState.SignedOut)
    }

    @Test
    fun `requesting a token fails unrecoverably rather than returning a blank one`() = runTest {
        // A recoverable error would invite callers to retry forever; an empty string
        // would sail past a null check and fail much later, at the HTTP boundary.
        val error = runCatching { AnonymousAuthService().accessToken() }.exceptionOrNull()

        assertThat(error).isInstanceOf(AuthException::class.java)
        assertThat((error as AuthException).recoverable).isFalse()
    }

    @Test
    fun `signing in fails unrecoverably until the OIDC flow lands`() = runTest {
        val error = runCatching { AnonymousAuthService().signIn() }.exceptionOrNull()

        assertThat(error).isInstanceOf(AuthException::class.java)
        assertThat((error as AuthException).recoverable).isFalse()
    }

    @Test
    fun `signing out is safe to call while already signed out`() = runTest {
        val auth = AnonymousAuthService()

        auth.signOut()

        assertThat(auth.sessionState.first()).isEqualTo(SessionState.SignedOut)
    }

    @Test
    fun `step-up assurance is refused, not granted by default`() = runTest {
        // Failing open here would let a module gate a sensitive action on an
        // assurance level the platform cannot actually establish.
        assertThat(AnonymousAuthService().stepUp(AuthService.Assurance.entries.first())).isFalse()
    }

    // --- NoEntitlementsBillingService: nothing owned until OD-406/407 -------------

    @Test
    fun `nothing is entitled and no products are listed`() = runTest {
        val billing = NoEntitlementsBillingService()

        assertThat(billing.entitlements.first()).isEmpty()
        assertThat(billing.products(setOf(Sku("premium")))).isEmpty()
    }

    @Test
    fun `a purchase fails rather than silently succeeding`() = runTest {
        val result = NoEntitlementsBillingService().purchase(Sku("premium"))

        assertThat(result).isInstanceOf(BillingService.PurchaseResult.Failed::class.java)
    }

    @Test
    fun `refreshing entitlements is a no-op that does not throw`() = runTest {
        NoEntitlementsBillingService().refresh()
    }

    // --- UnavailableMediaService: media picking lands in Phase 6 ------------------

    @Test
    fun `every media entry point refuses loudly and names the module`() = runTest {
        val media = UnavailableMediaService(moduleId())

        // Checked one by one: a picker that returned an empty list instead of
        // throwing would read to a module as "the user cancelled".
        val failures = listOf(
            runCatching { media.pickImage(allowMultiple = false) },
            runCatching { media.pickDocument(listOf("image/*")) },
            runCatching { media.captureImage() },
            runCatching { media.importToModuleStorage(android.net.Uri.EMPTY, "photo.jpg") },
        ).map { it.exceptionOrNull() }

        assertThat(failures).hasSize(4)
        failures.forEach {
            assertThat(it).isInstanceOf(UnsupportedOperationException::class.java)
            assertThat(it).hasMessageThat().contains(moduleId().value)
        }
    }

    // --- InMemoryFeatureFlagService: replaced by Remote Config in Phase 4 ---------

    @Test
    fun `an unset flag reads back its default at every type`() {
        val flags = InMemoryFeatureFlagService()

        assertThat(flags.boolean("absent", default = true)).isTrue()
        assertThat(flags.string("absent", default = "fallback")).isEqualTo("fallback")
        assertThat(flags.long("absent", default = 7L)).isEqualTo(7L)
        assertThat(flags.double("absent", default = 1.5)).isEqualTo(1.5)
    }

    @Test
    fun `a stored flag wins over the default`() {
        val flags = InMemoryFeatureFlagService()

        flags.put("bool", true)
        flags.put("text", "on")
        flags.put("count", 42L)
        flags.put("ratio", 0.25)

        assertThat(flags.boolean("bool", default = false)).isTrue()
        assertThat(flags.string("text", default = "off")).isEqualTo("on")
        assertThat(flags.long("count", default = 0L)).isEqualTo(42L)
        assertThat(flags.double("ratio", default = 0.0)).isEqualTo(0.25)
    }

    @Test
    fun `a flag stored at the wrong type falls back instead of throwing`() {
        // The store is untyped, and a Remote Config payload is server-controlled. A
        // ClassCastException here would crash the host on a bad config push.
        val flags = InMemoryFeatureFlagService()
        flags.put("bool", "not a boolean")

        assertThat(flags.boolean("bool", default = false)).isFalse()
    }

    @Test
    fun `json decodes a stored payload and falls back when it cannot`() {
        val flags = InMemoryFeatureFlagService()
        flags.put("json", "5")

        assertThat(flags.json("json", default = 0) { it.toInt() }).isEqualTo(5)

        flags.put("json", "not a number")
        assertThat(flags.json("json", default = -1) { it.toInt() }).isEqualTo(-1)
        assertThat(flags.json("absent", default = -1) { it.toInt() }).isEqualTo(-1)
    }

    @Test
    fun `a flag flow re-emits when the value behind it changes`() = runTest {
        val flags = InMemoryFeatureFlagService()

        assertThat(flags.booleanFlow("kill", default = false).first()).isFalse()
        flags.put("kill", true)
        assertThat(flags.booleanFlow("kill", default = false).first()).isTrue()
    }

    @Test
    fun `refresh succeeds against the local store`() = runTest {
        assertThat(InMemoryFeatureFlagService().refresh()).isTrue()
    }
}
