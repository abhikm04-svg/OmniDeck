package com.omnideck.sdk

import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.capability.PlatformEvent
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Range parsing and the event envelopes.
 *
 * `SemVerRange.parse` reads what a module author literally types into a manifest, and
 * the backend Registry parses the same string — so a disagreement here is a module
 * that the server serves and the device then refuses.
 */
class SemVerRangeParseTest {

    @Test
    fun `parses a bounded range`() {
        val range = SemVerRange.parse(">=2.0.0 <3.0.0")

        assertThat(range.minInclusive).isEqualTo(SemVer(2, 0, 0))
        assertThat(range.maxExclusive).isEqualTo(SemVer(3, 0, 0))
    }

    @Test
    fun `parses an open-ended range`() {
        val range = SemVerRange.parse(">=2.0.0")

        assertThat(range.minInclusive).isEqualTo(SemVer(2, 0, 0))
        assertThat(range.maxExclusive).isNull()
    }

    @Test
    fun `a bare version means that major only`() {
        // "2.1.0" is shorthand for ">=2.1.0 <3.0.0" — the intent is almost never
        // "this exact patch", and treating it that way would break every module on
        // the next patch release.
        val range = SemVerRange.parse("2.1.0")

        assertThat(SemVer(2, 1, 0) in range).isTrue()
        assertThat(SemVer(2, 9, 9) in range).isTrue()
        assertThat(SemVer(3, 0, 0) in range).isFalse()
        assertThat(SemVer(2, 0, 9) in range).isFalse()
    }

    @Test
    fun `tolerates extra whitespace between tokens`() {
        assertThat(SemVerRange.parse("  >=1.0.0    <2.0.0  ").maxExclusive)
            .isEqualTo(SemVer(2, 0, 0))
    }

    @Test
    fun `an empty range is rejected`() {
        assertThat(runCatching { SemVerRange.parse("   ") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a range round-trips through its rendered form`() {
        listOf(">=1.0.0 <2.0.0", ">=1.0.0").forEach { raw ->
            assertThat(SemVerRange.parse(raw).toString()).isEqualTo(raw)
        }
    }

    // -- event envelopes ----------------------------------------------------

    @Test
    fun `platform events carry a schema version for cross-version compatibility`() {
        // Satellites on an older SDK receive these; the version is how a consumer
        // knows whether it understands the payload (§8.2).
        assertThat(PlatformEvent.SessionChanged(signedIn = true, userIdHash = null).schemaVersion)
            .isAtLeast(1)
    }

    @Test
    fun `each event kind carries its payload`() {
        assertThat(PlatformEvent.ThemeChanged(darkMode = true, dynamicColor = false).darkMode).isTrue()
        assertThat(PlatformEvent.LocaleChanged("en-GB").languageTag).isEqualTo("en-GB")
        assertThat(PlatformEvent.PurchaseCompleted(Sku("pro")).sku).isEqualTo(Sku("pro"))
        assertThat(PlatformEvent.EntitlementsChanged(setOf(Sku("pro"))).skus).containsExactly(Sku("pro"))
        assertThat(PlatformEvent.ConnectivityChanged(online = false, metered = true).metered).isTrue()
        assertThat(
            PlatformEvent.ModuleStateChanged(ModuleId("com.omnideck.notes"), ModuleState.ACTIVE).state,
        ).isEqualTo(ModuleState.ACTIVE)
        assertThat(
            PlatformEvent.DataPurged(ModuleId("com.omnideck.notes"), PurgeScope.ALL).scope,
        ).isEqualTo(PurgeScope.ALL)
    }

    @Test
    fun `an event survives a JSON round trip`() {
        // Events cross the AIDL boundary to satellites as JSON, so a lossy round trip
        // would mean a satellite acting on a different fact than the host published.
        val original: PlatformEvent = PlatformEvent.SessionChanged(signedIn = true, userIdHash = "abc")

        val restored = Json.decodeFromString(
            PlatformEvent.serializer(),
            Json.encodeToString(PlatformEvent.serializer(), original),
        )

        assertThat(restored).isEqualTo(original)
    }
}
