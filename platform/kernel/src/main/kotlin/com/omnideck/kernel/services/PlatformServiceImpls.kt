package com.omnideck.kernel.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import androidx.core.text.layoutDirection
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.Sku
import com.omnideck.sdk.capability.AuthException
import com.omnideck.sdk.capability.AuthService
import com.omnideck.sdk.capability.BillingService
import com.omnideck.sdk.capability.Connectivity
import com.omnideck.sdk.capability.ConsentPurpose
import com.omnideck.sdk.capability.ConsentService
import com.omnideck.sdk.capability.ConsentState
import com.omnideck.sdk.capability.HttpConfig
import com.omnideck.sdk.capability.LocaleService
import com.omnideck.sdk.capability.NetworkService
import com.omnideck.sdk.capability.SessionState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.toJavaDuration

/**
 * ADR-006 — one engine, many derived clients.
 *
 * The shared [engine] owns the connection pool, TLS sessions, cache, certificate
 * pinning and the telemetry `EventListener`. A module gets a client derived with
 * `newBuilder()`, so it inherits all of that (performance, security) while setting
 * its own timeouts and interceptors (isolation). Creating a second `OkHttpClient`
 * from scratch in a module is a lint error for exactly this reason.
 */
@Singleton
class NetworkEngine @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    val engine: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Certificate pinning is added in Phase 4 (OD-410) together with the
            // backup-pin rotation runbook. Pinning without a rehearsed rotation
            // procedure is how you brick a fleet (risk R6).
            .retryOnConnectionFailure(true)
            .build()
    }

    private val connectivityManager = context.getSystemService<ConnectivityManager>()

    val connectivity: Flow<Connectivity> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            trySend(Connectivity(false, false, Connectivity.Kind.NONE))
            awaitClose { }
            return@callbackFlow
        }

        fun emit() = trySend(snapshot())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = Unit.also { emit() }
            override fun onLost(network: Network) = Unit.also { emit() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = Unit.also { emit() }
        }

        cm.registerDefaultNetworkCallback(callback)
        emit()
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    fun snapshot(): Connectivity {
        val cm = connectivityManager ?: return Connectivity(false, false, Connectivity.Kind.NONE)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return Connectivity(false, false, Connectivity.Kind.NONE)

        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Connectivity.Kind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Connectivity.Kind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Connectivity.Kind.ETHERNET
            else -> Connectivity.Kind.OTHER
        }
        return Connectivity(online, metered, kind)
    }
}

class NetworkServiceImpl(
    private val engine: NetworkEngine,
    private val moduleId: ModuleId,
    // Wired by the single-flight refreshing Authenticator in Phase 4 (OD-402);
    // kept on the constructor now so that later change is additive, not a
    // signature break for every caller.
    @Suppress("UnusedPrivateProperty") private val tokenProvider: suspend () -> String?,
) : NetworkService {

    override fun client(config: HttpConfig): OkHttpClient = engine.engine.newBuilder()
        .connectTimeout(config.connectTimeout.toJavaDuration())
        .readTimeout(config.readTimeout.toJavaDuration())
        .writeTimeout(config.writeTimeout.toJavaDuration())
        .addInterceptor(moduleAttributionInterceptor())
        .apply { config.interceptors.forEach(::addInterceptor) }
        .build()

    override fun retrofit(baseUrl: String, config: HttpConfig): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client(config))
        .build()

    override val connectivity: Flow<Connectivity> get() = engine.connectivity

    override fun isOnline(): Boolean = engine.snapshot().online

    /** Server-side attribution and per-module rate limiting depend on this header. */
    private fun moduleAttributionInterceptor() = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("X-OmniDeck-Module", moduleId.value)
                .build(),
        )
    }
}

// ---------------------------------------------------------------------------
// Locale / consent
// ---------------------------------------------------------------------------

class LocaleServiceImpl(private val context: Context) : LocaleService {

    private val locale: Locale
        get() = context.resources.configuration.locales[0] ?: Locale.getDefault()

    override val languageTag: String get() = locale.toLanguageTag()

    override val isRtl: Boolean
        get() = locale.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL

    override fun formatCurrency(minorUnits: Long, currencyCode: String): String =
        NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance(currencyCode)
        }.format(minorUnits / MINOR_UNITS_PER_MAJOR)

    override fun formatDate(epochMillis: Long, style: LocaleService.DateStyle): String {
        val flags = when (style) {
            LocaleService.DateStyle.SHORT -> android.text.format.DateUtils.FORMAT_NUMERIC_DATE
            LocaleService.DateStyle.MEDIUM -> android.text.format.DateUtils.FORMAT_ABBREV_ALL
            LocaleService.DateStyle.LONG -> android.text.format.DateUtils.FORMAT_SHOW_YEAR
            LocaleService.DateStyle.RELATIVE ->
                return android.text.format.DateUtils.getRelativeTimeSpanString(epochMillis).toString()
        }
        return android.text.format.DateUtils.formatDateTime(context, epochMillis, flags)
    }

    private companion object {
        const val MINOR_UNITS_PER_MAJOR = 100.0
    }
}

@Singleton
class ConsentServiceImpl @Inject constructor() : ConsentService {

    // Phase 4 (OD-411) persists this and drives it from the Privacy Centre UI.
    // ESSENTIAL is always granted; nothing else is, until the user says so.
    private val current = MutableStateFlow(
        ConsentState(granted = setOf(ConsentPurpose.ESSENTIAL), lastUpdatedEpochMs = 0L),
    )

    override val state: Flow<ConsentState> = current

    override fun isGranted(purpose: ConsentPurpose): Boolean = purpose in current.value.granted

    override suspend fun request(purpose: ConsentPurpose): Boolean = isGranted(purpose)

    fun grant(vararg purposes: ConsentPurpose) {
        current.value = current.value.copy(
            granted = current.value.granted + purposes,
            lastUpdatedEpochMs = System.currentTimeMillis(),
        )
    }
}

// ---------------------------------------------------------------------------
// Phase 4 placeholders — see implementation_plan.md §Phase 4
// ---------------------------------------------------------------------------

/**
 * Signed-out until OD-401 lands the AppAuth OIDC + PKCE flow.
 *
 * Deliberately a real object rather than a `TODO()`: modules must be able to run and
 * be tested against a signed-out platform from day one, because "works only when
 * signed in" is a bug class we would rather find in Phase 2 than Phase 7.
 */
@Singleton
class AnonymousAuthService @Inject constructor() : AuthService {

    private val state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val sessionState = state

    override suspend fun accessToken(): String =
        throw AuthException("No identity provider configured yet (OD-401).", recoverable = false)

    override suspend fun signIn(): SessionState =
        throw AuthException("Sign-in arrives in Phase 4 (OD-401/OD-404).", recoverable = false)

    override suspend fun signOut() {
        state.value = SessionState.SignedOut
    }

    override suspend fun stepUp(assurance: AuthService.Assurance): Boolean = false
}

/** Entitlements are server-authoritative; until OD-406/407, nothing is owned. */
@Singleton
class NoEntitlementsBillingService @Inject constructor() : BillingService {
    private val owned = MutableStateFlow<Set<Sku>>(emptySet())
    override val entitlements: Flow<Set<Sku>> = owned
    override suspend fun products(skus: Set<Sku>) = emptyList<BillingService.Product>()
    override suspend fun purchase(sku: Sku) =
        BillingService.PurchaseResult.Failed(-1, "Billing arrives in Phase 4 (OD-407).")

    override suspend fun refresh() = Unit
}

/** Small helper used by the scoped-services factory. */
internal class ScopedRegistryCache<K : Any, V : Any> {
    private val map = ConcurrentHashMap<K, V>()
    fun getOrPut(key: K, create: () -> V): V = map.getOrPut(key, create)
    fun remove(key: K): V? = map.remove(key)
    fun values(): Collection<V> = map.values
}

/** Flow of a single value, used where a capability has no dynamic source yet. */
internal fun <T> constantFlow(value: T): Flow<T> = flowOf(value)
