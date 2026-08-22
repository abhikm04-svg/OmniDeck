package com.omnideck.testing

import com.omnideck.sdk.CorrelationId
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.Route
import com.omnideck.sdk.Sku
import com.omnideck.sdk.capability.AuthService
import com.omnideck.sdk.capability.BillingService
import com.omnideck.sdk.capability.Connectivity
import com.omnideck.sdk.capability.ConsentPurpose
import com.omnideck.sdk.capability.ConsentService
import com.omnideck.sdk.capability.ConsentState
import com.omnideck.sdk.capability.EventBus
import com.omnideck.sdk.capability.FeatureFlagService
import com.omnideck.sdk.capability.LocaleService
import com.omnideck.sdk.capability.NavResult
import com.omnideck.sdk.capability.NavResultValue
import com.omnideck.sdk.capability.NotificationService
import com.omnideck.sdk.capability.PermissionBroker
import com.omnideck.sdk.capability.PlatformEvent
import com.omnideck.sdk.capability.Principal
import com.omnideck.sdk.capability.Router
import com.omnideck.sdk.capability.SecureStore
import com.omnideck.sdk.capability.SessionState
import com.omnideck.sdk.capability.TelemetryService
import com.omnideck.sdk.capability.WorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------------------
// Telemetry
// ---------------------------------------------------------------------------

class FakeTelemetryService : TelemetryService {

    data class Recorded(val name: String, val attributes: Map<String, Any?>)

    val events = mutableListOf<Recorded>()
    val metrics = mutableListOf<Recorded>()
    val breadcrumbs = mutableListOf<String>()
    val errors = mutableListOf<Throwable>()
    val spans = mutableListOf<String>()

    override fun event(name: String, attributes: Map<String, Any?>) {
        events += Recorded(name, attributes)
    }

    override fun metric(name: String, value: Double, attributes: Map<String, Any?>) {
        metrics += Recorded(name, attributes + ("value" to value))
    }

    override fun breadcrumb(message: String, attributes: Map<String, Any?>) {
        breadcrumbs += message
    }

    override fun recordError(throwable: Throwable, message: String?, fatal: Boolean) {
        errors += throwable
    }

    override fun startSpan(name: String, attributes: Map<String, Any?>): TelemetryService.Span {
        spans += name
        return object : TelemetryService.Span {
            override val traceId = "fake-trace-${spans.size}"
            override fun setAttribute(key: String, value: Any?) = Unit
            override fun recordException(throwable: Throwable) {
                errors += throwable
            }
            override fun setStatus(ok: Boolean, description: String?) = Unit
            override fun close() = Unit
        }
    }

    fun eventNames(): List<String> = events.map { it.name }

    fun reset() {
        events.clear()
        metrics.clear()
        breadcrumbs.clear()
        errors.clear()
        spans.clear()
    }
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

class FakeAuthService(
    initial: SessionState = SessionState.SignedIn(
        Principal(subjectId = "test-subject", displayName = "Test User", tenantId = null),
    ),
) : AuthService {

    private val state = MutableStateFlow(initial)
    override val sessionState = state

    var token: String = "fake-access-token"
    var stepUpSucceeds: Boolean = true

    override suspend fun accessToken(): String = token

    override suspend fun signIn(): SessionState =
        SessionState.SignedIn(Principal("test-subject", "Test User", null)).also { state.value = it }

    override suspend fun signOut() {
        state.value = SessionState.SignedOut
    }

    override suspend fun stepUp(assurance: AuthService.Assurance): Boolean = stepUpSucceeds

    fun setSignedOut() {
        state.value = SessionState.SignedOut
    }

    // vararg of a value class (Sku) is prohibited by Kotlin — take a Set instead.
    fun setEntitlements(skus: Set<Sku>) {
        val p = state.value.principalOrNull ?: Principal("test-subject", "Test User", null)
        state.value = SessionState.SignedIn(p.copy(entitlements = skus))
    }
}

// ---------------------------------------------------------------------------
// Flags / consent / locale
// ---------------------------------------------------------------------------

class FakeFeatureFlagService : FeatureFlagService {
    private val values = ConcurrentHashMap<String, Any>()

    /**
     * Bumped on every write so [booleanFlow] re-emits.
     *
     * Without it the fake handed back a single-value flow, and nothing built on this
     * harness could test a flag *changing* — which is the only interesting thing a
     * flag does. The kill switch (ADR-009) is exactly that case.
     */
    private val revision = MutableStateFlow(0)

    val reads = mutableListOf<String>()

    fun set(key: String, value: Any) = apply {
        values[key] = value
        revision.value++
    }

    override fun boolean(key: String, default: Boolean): Boolean {
        reads += key
        return values[key] as? Boolean ?: default
    }

    override fun string(key: String, default: String): String {
        reads += key
        return values[key] as? String ?: default
    }

    override fun long(key: String, default: Long): Long {
        reads += key
        return values[key] as? Long ?: default
    }

    override fun double(key: String, default: Double): Double {
        reads += key
        return values[key] as? Double ?: default
    }

    override fun <T> json(key: String, default: T, decode: (String) -> T): T {
        reads += key
        return (values[key] as? String)?.let(decode) ?: default
    }

    override fun booleanFlow(key: String, default: Boolean): Flow<Boolean> =
        revision.map { boolean(key, default) }.distinctUntilChanged()

    override suspend fun refresh(): Boolean = true
}

class FakeConsentService(granted: Set<ConsentPurpose> = ConsentPurpose.entries.toSet()) : ConsentService {
    private val current = MutableStateFlow(ConsentState(granted, 0L))
    override val state: Flow<ConsentState> = current
    var autoGrant = true

    override fun isGranted(purpose: ConsentPurpose) = purpose in current.value.granted

    override suspend fun request(purpose: ConsentPurpose): Boolean {
        if (autoGrant) current.value = current.value.copy(granted = current.value.granted + purpose)
        return autoGrant
    }
}

class FakeLocaleService(override val languageTag: String = "en-US", override val isRtl: Boolean = false) :
    LocaleService {
    override fun formatCurrency(minorUnits: Long, currencyCode: String): String =
        "$currencyCode ${"%.2f".format(minorUnits / MINOR_UNITS_PER_MAJOR)}"

    override fun formatDate(epochMillis: Long, style: LocaleService.DateStyle): String = "date($epochMillis)"

    private companion object {
        const val MINOR_UNITS_PER_MAJOR = 100.0
    }
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------

class FakeEventBus : EventBus {
    private val flow = MutableSharedFlow<PlatformEvent>(replay = 16, extraBufferCapacity = 64)
    val published = mutableListOf<PlatformEvent>()

    override fun publish(event: PlatformEvent) {
        published += event
        flow.tryEmit(event)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : PlatformEvent> subscribe(type: Class<T>): Flow<T> = flow.asSharedFlow()
        .filter { type.isInstance(it) }
        .map { it as T }
}

// ---------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------

class FakeRouter : Router {
    val navigations = mutableListOf<Route>()
    var handledRoutes: Set<String> = emptySet()
    var nextResult: NavResult? = null

    override suspend fun navigate(route: Route): NavResult {
        navigations += route
        return nextResult ?: NavResult.Navigated(route)
    }

    override fun <T : Any> navigateForResult(route: Route, type: Class<T>): Flow<NavResultValue<T>> {
        navigations += route
        return flowOf(NavResultValue.Cancelled)
    }

    override fun canHandle(route: Route): Boolean = handledRoutes.isEmpty() || route.uri in handledRoutes

    override fun back(): Boolean = navigations.removeLastOrNull() != null

    override fun <T : Any> setResult(correlationId: CorrelationId, value: T) = Unit

    fun lastRoute(): Route? = navigations.lastOrNull()

    fun reset() {
        navigations.clear()
    }
}

// ---------------------------------------------------------------------------
// Secure store
// ---------------------------------------------------------------------------

class FakeSecureStore : SecureStore {
    private val store = ConcurrentHashMap<String, ByteArray>()
    var biometricAvailable = true
    var biometricSucceeds = true

    override suspend fun put(alias: String, value: ByteArray) {
        store[alias] = value
    }
    override suspend fun get(alias: String): ByteArray? = store[alias]
    override suspend fun delete(alias: String) {
        store.remove(alias)
    }
    override suspend fun contains(alias: String): Boolean = store.containsKey(alias)

    override suspend fun putBiometricGated(alias: String, value: ByteArray, timeoutSeconds: Int): Boolean {
        if (!biometricAvailable) return false
        store[alias] = value
        return true
    }

    override suspend fun getBiometricGated(alias: String, promptTitle: String, promptSubtitle: String?): ByteArray? =
        if (biometricSucceeds) store[alias] else null
}

// ---------------------------------------------------------------------------
// Permissions / notifications
// ---------------------------------------------------------------------------

class FakePermissionBroker : PermissionBroker {
    val requested = mutableListOf<String>()
    var result: PermissionBroker.PermissionResult = PermissionBroker.PermissionResult.GRANTED
    var settingsOpened = false

    override suspend fun ensure(permission: String, rationale: PermissionBroker.Rationale) =
        result.also { requested += permission }

    override fun isGranted(permission: String) = result == PermissionBroker.PermissionResult.GRANTED

    override fun openSettings() {
        settingsOpened = true
    }

    fun reset() {
        requested.clear()
        settingsOpened = false
    }
}

class FakeNotificationService : NotificationService {
    val posted = mutableListOf<NotificationService.NotificationSpec>()
    val cancelled = mutableListOf<Int>()
    var permissionGranted = true
    var enabled = true

    override suspend fun post(spec: NotificationService.NotificationSpec): Boolean {
        if (!permissionGranted) return false
        posted += spec
        return true
    }

    override suspend fun cancel(notificationId: Int) {
        cancelled += notificationId
    }

    override suspend fun cancelAll() {
        posted.clear()
    }

    override suspend fun ensurePermission(rationale: PermissionBroker.Rationale) = permissionGranted

    override fun areNotificationsEnabled() = enabled

    fun reset() {
        posted.clear()
        cancelled.clear()
    }
}

// ---------------------------------------------------------------------------
// Billing / work
// ---------------------------------------------------------------------------

class FakeBillingService : BillingService {
    private val owned = MutableStateFlow<Set<Sku>>(emptySet())
    override val entitlements: Flow<Set<Sku>> = owned

    var catalog: List<BillingService.Product> = emptyList()
    var nextPurchaseResult: BillingService.PurchaseResult? = null

    override suspend fun products(skus: Set<Sku>) = catalog.filter { it.sku in skus }

    override suspend fun purchase(sku: Sku): BillingService.PurchaseResult =
        nextPurchaseResult ?: BillingService.PurchaseResult.Purchased(sku).also {
            owned.value = owned.value + sku
        }

    override suspend fun refresh() = Unit

    // vararg of a value class (Sku) is prohibited by Kotlin — take a Set instead.
    fun grant(skus: Set<Sku>) {
        owned.value = owned.value + skus
    }
}

class FakeWorkScheduler : WorkScheduler {
    private val ids = AtomicInteger()
    val enqueued = mutableListOf<WorkScheduler.WorkSpec>()
    val cancelled = mutableListOf<String>()
    var cancelAllCalled = false

    /**
     * Periodic schedules with the interval they asked for.
     *
     * Recorded separately because the interval is the part a module gets wrong —
     * WorkManager silently floors anything below 15 minutes, so a module that asks
     * for 5 believes it syncs three times as often as it does. Folding these into
     * [enqueued] alone would make that untestable.
     */
    val periodic = mutableListOf<Pair<WorkScheduler.WorkSpec, Duration>>()

    override fun enqueue(spec: WorkScheduler.WorkSpec): String {
        enqueued += spec
        return "work-${ids.incrementAndGet()}"
    }

    override fun enqueuePeriodic(spec: WorkScheduler.WorkSpec, interval: Duration): String {
        periodic += spec to interval
        return enqueue(spec)
    }

    override fun cancel(workId: String) {
        cancelled += workId
    }

    override fun cancelAll() {
        cancelAllCalled = true
        enqueued.clear()
        periodic.clear()
    }

    override fun status(workId: String): Flow<WorkScheduler.WorkStatus> = flowOf(WorkScheduler.WorkStatus.SUCCEEDED)

    fun reset() {
        enqueued.clear()
        periodic.clear()
        cancelled.clear()
        cancelAllCalled = false
    }
}

/** Convenience for asserting purge behaviour in module tests. */
fun purgeScopes(): List<PurgeScope> = PurgeScope.entries

/** Convenience default used by connectivity-sensitive tests. */
val OnlineWifi = Connectivity(online = true, metered = false, kind = Connectivity.Kind.WIFI)
val Offline = Connectivity(online = false, metered = false, kind = Connectivity.Kind.NONE)
