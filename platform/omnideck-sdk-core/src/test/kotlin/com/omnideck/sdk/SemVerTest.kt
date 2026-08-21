package com.omnideck.sdk

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The compatibility contract between a module and the host.
 *
 * Everything that decides "will this module load" is expressed in these types, and
 * they are shared verbatim with the backend Registry — so a disagreement between the
 * two about what `>=1.0.0 <2.0.0` means is a module that installs and then refuses to
 * start. Ordering and boundary behaviour are pinned deliberately.
 */
class SemVerTest {

    // -- parsing ------------------------------------------------------------

    @Test
    fun `parses a plain version`() {
        val version = SemVer.parse("1.2.3")

        assertThat(version).isEqualTo(SemVer(1, 2, 3))
        assertThat(version.preRelease).isNull()
    }

    @Test
    fun `parses a pre-release version`() {
        val version = SemVer.parse("2.0.0-rc.1")

        assertThat(version.major).isEqualTo(2)
        assertThat(version.preRelease).isEqualTo("rc.1")
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertThat(SemVer.parse("  1.0.0  ")).isEqualTo(SemVer(1, 0, 0))
    }

    @Test
    fun `rejects malformed versions`() {
        listOf("1.0", "1", "v1.0.0", "1.0.0.0", "", "abc", "1.0.0-").forEach { raw ->
            val error = runCatching { SemVer.parse(raw) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `parseOrNull returns null rather than throwing`() {
        assertThat(SemVer.parseOrNull("nonsense")).isNull()
        assertThat(SemVer.parseOrNull("1.0.0")).isEqualTo(SemVer(1, 0, 0))
    }

    @Test
    fun `round-trips through its string form`() {
        listOf("0.0.1", "1.2.3", "10.20.30", "2.0.0-rc.1").forEach { raw ->
            assertThat(SemVer.parse(raw).toString()).isEqualTo(raw)
        }
    }

    // -- ordering -----------------------------------------------------------

    @Test
    fun `orders by major then minor then patch`() {
        val ascending = listOf(
            SemVer(1, 0, 0),
            SemVer(1, 0, 1),
            SemVer(1, 1, 0),
            SemVer(2, 0, 0),
        )

        assertThat(ascending.shuffled().sorted()).isEqualTo(ascending)
    }

    @Test
    fun `a pre-release sorts before its release`() {
        // 2.0.0-rc1 must not satisfy a range that starts at 2.0.0, or release
        // candidates would be served to users expecting the stable version.
        assertThat(SemVer.parse("2.0.0-rc1")).isLessThan(SemVer.parse("2.0.0"))
    }

    @Test
    fun `pre-releases order among themselves`() {
        assertThat(SemVer.parse("1.0.0-alpha")).isLessThan(SemVer.parse("1.0.0-beta"))
    }

    @Test
    fun `equal versions compare equal`() {
        assertThat(SemVer(1, 2, 3).compareTo(SemVer(1, 2, 3))).isEqualTo(0)
    }

    // -- ranges -------------------------------------------------------------

    @Test
    fun `the lower bound is inclusive and the upper exclusive`() {
        // The convention that makes ">=1.0.0 <2.0.0" mean "all of 1.x".
        val range = SemVerRange(SemVer(1, 0, 0), SemVer(2, 0, 0))

        assertThat(SemVer(1, 0, 0) in range).isTrue()
        assertThat(SemVer(1, 9, 9) in range).isTrue()
        assertThat(SemVer(2, 0, 0) in range).isFalse()
        assertThat(SemVer(0, 9, 9) in range).isFalse()
    }

    @Test
    fun `an open-ended range accepts everything above its floor`() {
        val range = SemVerRange(SemVer(1, 0, 0), maxExclusive = null)

        assertThat(SemVer(1, 0, 0) in range).isTrue()
        assertThat(SemVer(99, 0, 0) in range).isTrue()
        assertThat(SemVer(0, 9, 0) in range).isFalse()
    }

    @Test
    fun `a range renders the way it is written in a manifest`() {
        assertThat(SemVerRange(SemVer(1, 0, 0), SemVer(2, 0, 0)).toString())
            .isEqualTo(">=1.0.0 <2.0.0")
        assertThat(SemVerRange(SemVer(1, 0, 0), null).toString()).isEqualTo(">=1.0.0")
    }

    // -- serialization ------------------------------------------------------

    @Test
    fun `serializes as a plain string for the backend`() {
        // The Registry stores manifests as JSON; a version encoded as an object
        // would break the shared schema.
        val json = Json.encodeToString(SemVer.serializer(), SemVer(1, 2, 3))

        assertThat(json).isEqualTo("\"1.2.3\"")
    }

    @Test
    fun `deserializes from a plain string`() {
        val version = Json.decodeFromString(SemVer.serializer(), "\"2.0.0-rc.1\"")

        assertThat(version).isEqualTo(SemVer(2, 0, 0, "rc.1"))
    }

    @Test
    fun `survives a serialization round trip`() {
        val original = SemVer(3, 4, 5, "beta.2")

        val restored = Json.decodeFromString(
            SemVer.serializer(),
            Json.encodeToString(SemVer.serializer(), original),
        )

        assertThat(restored).isEqualTo(original)
    }
}
