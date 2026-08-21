package com.omnideck.testing

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.Sku
import com.omnideck.sdk.capability.AuthService
import com.omnideck.sdk.capability.BillingService
import com.omnideck.sdk.capability.ConsentPurpose
import com.omnideck.sdk.capability.NotificationService
import com.omnideck.sdk.capability.PermissionBroker
import com.omnideck.sdk.capability.SessionState
import com.omnideck.sdk.capability.WorkScheduler
import com.omnideck.sdk.capability.getString
import com.omnideck.sdk.capability.putString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Covers the fakes a module reaches through `PlatformServices`. Each one is both a
 * stand-in and an assertion surface, so these tests pin the recording behaviour too —
 * a fake that silently stops recording makes every module test vacuously pass.
 */
class CapabilityFakesTest {

    // -- auth ---------------------------------------------------------------

    @Test
    fun `auth starts signed in and can be signed out`() = runTest {
        val auth = FakeAuthService()
        assertThat(auth.sessionState.value).isInstanceOf(SessionState.SignedIn::class.java)

        auth.signOut()

        assertThat(auth.sessionState.value).isEqualTo(SessionState.SignedOut)
    }

    @Test
    fun `auth session transitions are observable`() = runTest {
        val auth = FakeAuthService()

        auth.sessionState.test {
            assertThat(awaitItem()).isInstanceOf(SessionState.SignedIn::class.java)
            auth.setSignedOut()
            assertThat(awaitItem()).isEqualTo(SessionState.SignedOut)
            auth.signIn()
            assertThat(awaitItem()).isInstanceOf(SessionState.SignedIn::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `auth entitlements land on the principal`() = runTest {
        val auth = FakeAuthService()
        val pro = Sku("pro")

        auth.setEntitlements(setOf(pro))

        val principal = (auth.sessionState.value as SessionState.SignedIn).principal
        assertThat(principal.entitlements).containsExactly(pro)
    }

    @Test
    fun `auth step-up outcome is scriptable`() = runTest {
        val auth = FakeAuthService()
        auth.stepUpSucceeds = false

        assertThat(auth.stepUp(AuthService.Assurance.BIOMETRIC)).isFalse()
    }

    // -- billing ------------------------------------------------------------

    @Test
    fun `purchase grants the entitlement by default`() = runTest {
        val billing = FakeBillingService()
        val sku = Sku("module.finance")

        val result = billing.purchase(sku)

        assertThat(result).isEqualTo(BillingService.PurchaseResult.Purchased(sku))
        assertThat(billing.entitlements.first()).containsExactly(sku)
    }

    @Test
    fun `a scripted purchase result is returned without granting`() = runTest {
        // Deliberate: scripting a cancellation or failure must not hand over the
        // entitlement, otherwise the failure path cannot be tested.
        val billing = FakeBillingService()
        billing.nextPurchaseResult = BillingService.PurchaseResult.Cancelled

        val result = billing.purchase(Sku("module.finance"))

        assertThat(result).isEqualTo(BillingService.PurchaseResult.Cancelled)
        assertThat(billing.entitlements.first()).isEmpty()
    }

    @Test
    fun `grant seeds entitlements without a purchase`() = runTest {
        val billing = FakeBillingService()
        val sku = Sku("bundle")

        billing.grant(setOf(sku))

        assertThat(billing.entitlements.first()).containsExactly(sku)
    }

    @Test
    fun `products filters the catalog to the requested skus`() = runTest {
        val wanted = Sku("a")
        val other = Sku("b")
        val billing = FakeBillingService().apply {
            catalog = listOf(product(wanted), product(other))
        }

        val products = billing.products(setOf(wanted))

        assertThat(products.map { it.sku }).containsExactly(wanted)
    }

    private fun product(sku: Sku) = BillingService.Product(
        sku = sku,
        title = sku.value,
        description = "",
        formattedPrice = "$1.00",
        priceMinorUnits = 100,
        currencyCode = "USD",
        subscription = false,
    )

    // -- consent ------------------------------------------------------------

    @Test
    fun `consent grants on request when auto-granting`() = runTest {
        val consent = FakeConsentService(granted = setOf(ConsentPurpose.ESSENTIAL))
        assertThat(consent.isGranted(ConsentPurpose.MARKETING)).isFalse()

        val granted = consent.request(ConsentPurpose.MARKETING)

        assertThat(granted).isTrue()
        assertThat(consent.isGranted(ConsentPurpose.MARKETING)).isTrue()
    }

    @Test
    fun `consent refusal is expressible`() = runTest {
        val consent = FakeConsentService(granted = setOf(ConsentPurpose.ESSENTIAL))
        consent.autoGrant = false

        val granted = consent.request(ConsentPurpose.PRODUCT_ANALYTICS)

        assertThat(granted).isFalse()
        assertThat(consent.isGranted(ConsentPurpose.PRODUCT_ANALYTICS)).isFalse()
    }

    // -- flags --------------------------------------------------------------

    @Test
    fun `flags return defaults when unset and values when set`() {
        val flags = FakeFeatureFlagService()

        assertThat(flags.boolean("missing", default = true)).isTrue()
        flags.set("enabled", false)
        assertThat(flags.boolean("enabled", default = true)).isFalse()
    }

    @Test
    fun `flags fall back to the default on a type mismatch`() {
        // A misconfigured remote value must not crash a module.
        val flags = FakeFeatureFlagService().set("count", "not-a-number")

        assertThat(flags.long("count", default = 7L)).isEqualTo(7L)
    }

    @Test
    fun `flag reads are recorded for hygiene assertions`() {
        val flags = FakeFeatureFlagService()

        flags.boolean("a", false)
        flags.string("b", "")

        assertThat(flags.reads).containsExactly("a", "b").inOrder()
    }

    // -- secure store -------------------------------------------------------

    @Test
    fun `secure store round-trips and deletes`() = runTest {
        val store = FakeSecureStore()

        store.putString("token", "abc")
        assertThat(store.getString("token")).isEqualTo("abc")
        assertThat(store.contains("token")).isTrue()

        store.delete("token")
        assertThat(store.get("token")).isNull()
        assertThat(store.contains("token")).isFalse()
    }

    @Test
    fun `biometric write fails when no biometric is enrolled`() = runTest {
        val store = FakeSecureStore()
        store.biometricAvailable = false

        assertThat(store.putBiometricGated("k", byteArrayOf(1))).isFalse()
        assertThat(store.get("k")).isNull()
    }

    @Test
    fun `biometric read returns null when the prompt is refused`() = runTest {
        val store = FakeSecureStore()
        store.putBiometricGated("k", byteArrayOf(1))
        store.biometricSucceeds = false

        assertThat(store.getBiometricGated("k", "Unlock")).isNull()
    }

    // -- permissions --------------------------------------------------------

    @Test
    fun `permission requests are recorded and the outcome is scriptable`() = runTest {
        val permissions = FakePermissionBroker()
        permissions.result = PermissionBroker.PermissionResult.DENIED

        val result = permissions.ensure("android.permission.CAMERA", rationale())

        assertThat(result).isEqualTo(PermissionBroker.PermissionResult.DENIED)
        assertThat(permissions.requested).containsExactly("android.permission.CAMERA")
        assertThat(permissions.isGranted("android.permission.CAMERA")).isFalse()
    }

    @Test
    fun `openSettings is observable so the denial path can be asserted`() {
        val permissions = FakePermissionBroker()

        permissions.openSettings()

        assertThat(permissions.settingsOpened).isTrue()
    }

    private fun rationale() = PermissionBroker.Rationale(
        title = "Camera",
        message = "Needed to scan documents",
        purpose = "scanning",
    )

    // -- notifications ------------------------------------------------------

    @Test
    fun `notifications are recorded when permitted`() = runTest {
        val notifications = FakeNotificationService()

        val posted = notifications.post(spec(1))

        assertThat(posted).isTrue()
        assertThat(notifications.posted.map { it.id }).containsExactly(1)
    }

    @Test
    fun `posting is refused without permission`() = runTest {
        val notifications = FakeNotificationService()
        notifications.permissionGranted = false

        assertThat(notifications.post(spec(1))).isFalse()
        assertThat(notifications.posted).isEmpty()
    }

    @Test
    fun `cancellation is recorded`() = runTest {
        val notifications = FakeNotificationService()
        notifications.post(spec(7))

        notifications.cancel(7)

        assertThat(notifications.cancelled).containsExactly(7)
    }

    private fun spec(id: Int) = NotificationService.NotificationSpec(
        id = id,
        channelId = "general",
        title = "Title",
        body = "Body",
    )

    // -- work ---------------------------------------------------------------

    @Test
    fun `enqueue records the spec and returns a unique id`() {
        val work = FakeWorkScheduler()

        val first = work.enqueue(workSpec("sync"))
        val second = work.enqueue(workSpec("upload"))

        assertThat(first).isNotEqualTo(second)
        assertThat(work.enqueued.map { it.name }).containsExactly("sync", "upload").inOrder()
    }

    @Test
    fun `cancelAll clears queued work and is observable`() {
        val work = FakeWorkScheduler()
        work.enqueue(workSpec("sync"))

        work.cancelAll()

        assertThat(work.cancelAllCalled).isTrue()
        assertThat(work.enqueued).isEmpty()
    }

    @Test
    fun `periodic work is recorded like one-shot work`() {
        val work = FakeWorkScheduler()

        work.enqueuePeriodic(workSpec("refresh"), java.time.Duration.ofHours(1))

        assertThat(work.enqueued.map { it.name }).containsExactly("refresh")
    }

    private fun workSpec(name: String) = WorkScheduler.WorkSpec(
        name = name,
        worker = Any::class.java,
    )

    // -- locale -------------------------------------------------------------

    @Test
    fun `locale formats currency from minor units`() {
        val locale = FakeLocaleService()

        assertThat(locale.formatCurrency(minorUnits = 1234, currencyCode = "USD")).isEqualTo("USD 12.34")
    }

    @Test
    fun `locale reports its configured tag and direction`() {
        val rtl = FakeLocaleService(languageTag = "ar-EG", isRtl = true)

        assertThat(rtl.languageTag).isEqualTo("ar-EG")
        assertThat(rtl.isRtl).isTrue()
    }
}
