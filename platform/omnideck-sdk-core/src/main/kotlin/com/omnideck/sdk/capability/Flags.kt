package com.omnideck.sdk.capability

import kotlinx.coroutines.flow.Flow

/**
 * Server-driven configuration. Also the transport for the module kill switch
 * (ADR-009) — which is why every read is telemetered: flag hygiene reporting
 * depends on knowing which flags are still consulted.
 */
interface FeatureFlagService {
    fun boolean(key: String, default: Boolean): Boolean
    fun string(key: String, default: String): String
    fun long(key: String, default: Long): Long
    fun double(key: String, default: Double): Double

    /** Decoded from a JSON flag value; returns [default] if absent or malformed. */
    fun <T> json(key: String, default: T, decode: (String) -> T): T

    /** Observes a flag; emits on every remote refresh. */
    fun booleanFlow(key: String, default: Boolean): Flow<Boolean>

    /** Forces a refresh. The Shell already does this on every foreground. */
    suspend fun refresh(): Boolean
}

/** Purpose-based consent (GDPR / India DPDP). Telemetry egress is gated on it. */
interface ConsentService {
    val state: Flow<ConsentState>

    fun isGranted(purpose: ConsentPurpose): Boolean

    /** Shows the consent UI for [purpose] and suspends until the user decides. */
    suspend fun request(purpose: ConsentPurpose): Boolean
}

data class ConsentState(val granted: Set<ConsentPurpose>, val lastUpdatedEpochMs: Long)

enum class ConsentPurpose {
    ESSENTIAL,
    PRODUCT_ANALYTICS,
    CRASH_DIAGNOSTICS,
    PERSONALISATION,
    MARKETING,
}

/** Locale and formatting, centralised so modules never read Locale.getDefault(). */
interface LocaleService {
    val languageTag: String
    val isRtl: Boolean
    fun formatCurrency(minorUnits: Long, currencyCode: String): String
    fun formatDate(epochMillis: Long, style: DateStyle = DateStyle.MEDIUM): String

    enum class DateStyle { SHORT, MEDIUM, LONG, RELATIVE }
}
