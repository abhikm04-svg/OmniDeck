package com.omnideck.sdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Semantic version. The compatibility contract between a module and the host SDK
 * (architecture.md §19.3) is expressed entirely in these types, which is what lets
 * a module ship on a different cadence from the Shell.
 */
@Serializable(with = SemVerSerializer::class)
data class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: String? = null) :
    Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        // A pre-release sorts *before* its release (1.0.0-rc1 < 1.0.0).
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    override fun toString(): String = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?""")

        fun parse(raw: String): SemVer {
            val m = requireNotNull(PATTERN.matchEntire(raw.trim())) {
                "Not a semantic version: '$raw'"
            }
            return SemVer(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                patch = m.groupValues[3].toInt(),
                preRelease = m.groupValues[4].takeIf { it.isNotEmpty() },
            )
        }

        fun parseOrNull(raw: String): SemVer? = runCatching { parse(raw) }.getOrNull()
    }
}

internal object SemVerSerializer : KSerializer<SemVer> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.omnideck.sdk.SemVer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SemVer) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): SemVer = SemVer.parse(decoder.decodeString())
}

/**
 * An inclusive-lower/exclusive-upper version range, written `">=2.0.0 <3.0.0"`.
 *
 * A module declares the host SDK versions it supports. The Shell refuses to load a
 * module outside its range, and the Registry refuses to serve one — so version skew
 * produces a clear, actionable message instead of a `NoSuchMethodError` three
 * screens into a user journey.
 */
@Serializable(with = SemVerRangeSerializer::class)
data class SemVerRange(val minInclusive: SemVer, val maxExclusive: SemVer?) {
    operator fun contains(version: SemVer): Boolean =
        version >= minInclusive && (maxExclusive == null || version < maxExclusive)

    override fun toString(): String = ">=$minInclusive" + (maxExclusive?.let { " <$it" } ?: "")

    companion object {
        /** Accepts `">=2.0.0 <3.0.0"`, `">=2.0.0"`, or a bare `"2.0.0"` (exact-major). */
        fun parse(raw: String): SemVerRange {
            val tokens = raw.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            require(tokens.isNotEmpty()) { "Empty version range" }

            if (tokens.size == 1 && !tokens[0].startsWith(">") && !tokens[0].startsWith("<")) {
                val exact = SemVer.parse(tokens[0])
                return SemVerRange(exact, SemVer(exact.major + 1, 0, 0))
            }

            var min: SemVer? = null
            var max: SemVer? = null
            tokens.forEach { token ->
                when {
                    token.startsWith(">=") -> min = SemVer.parse(token.removePrefix(">="))
                    token.startsWith("<") -> max = SemVer.parse(token.removePrefix("<"))
                    else -> error("Unsupported range token '$token' in '$raw' (use >= and <)")
                }
            }
            return SemVerRange(requireNotNull(min) { "Range '$raw' needs a >= bound" }, max)
        }
    }
}

internal object SemVerRangeSerializer : KSerializer<SemVerRange> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.omnideck.sdk.SemVerRange", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SemVerRange) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): SemVerRange = SemVerRange.parse(decoder.decodeString())
}
