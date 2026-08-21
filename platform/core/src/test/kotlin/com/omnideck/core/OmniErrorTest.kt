package com.omnideck.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The error taxonomy is what modules branch on to decide between retrying, prompting
 * for re-auth, and showing an offline state. Equality and payload carrying therefore
 * have to hold — a data class that stopped comparing by value would make those
 * branches silently wrong.
 */
class OmniErrorTest {

    @Test
    fun `singleton errors compare by identity`() {
        assertThat(OmniError.Offline).isEqualTo(OmniError.Offline)
        assertThat(OmniError.Timeout).isNotEqualTo(OmniError.Offline)
        assertThat(OmniError.Unauthorized).isNotEqualTo(OmniError.Forbidden)
        assertThat(OmniError.NotFound).isNotEqualTo(OmniError.Timeout)
    }

    @Test
    fun `http errors carry status and body and compare by value`() {
        val a = OmniError.Http(code = 503, body = "unavailable")

        assertThat(a.code).isEqualTo(503)
        assertThat(a.body).isEqualTo("unavailable")
        assertThat(a).isEqualTo(OmniError.Http(503, "unavailable"))
        assertThat(a).isNotEqualTo(OmniError.Http(500, "unavailable"))
    }

    @Test
    fun `http body is optional`() {
        assertThat(OmniError.Http(404, null).body).isNull()
    }

    @Test
    fun `detail-carrying errors keep their detail`() {
        assertThat(OmniError.Serialization("missing field 'id'").detail).isEqualTo("missing field 'id'")
        assertThat(OmniError.Storage("disk full").detail).isEqualTo("disk full")
        assertThat(OmniError.Unknown("boom").detail).isEqualTo("boom")
    }

    @Test
    fun `distinct error kinds are never equal even with the same detail`() {
        // Storage("x") and Serialization("x") must not collapse — a module retrying
        // a serialization fault as if it were a disk fault would loop forever.
        assertThat(OmniError.Storage("x")).isNotEqualTo(OmniError.Serialization("x"))
        assertThat(OmniError.Unknown("x")).isNotEqualTo(OmniError.Storage("x"))
    }

    @Test
    fun `errors are usable as when subjects`() {
        fun classify(error: OmniError): String = when (error) {
            OmniError.Offline, OmniError.Timeout -> "retry"
            OmniError.Unauthorized -> "reauthenticate"
            OmniError.Forbidden, OmniError.NotFound -> "give-up"
            is OmniError.Http -> if (error.code >= 500) "retry" else "give-up"
            is OmniError.Serialization, is OmniError.Storage, is OmniError.Unknown -> "report"
        }

        assertThat(classify(OmniError.Offline)).isEqualTo("retry")
        assertThat(classify(OmniError.Http(503, null))).isEqualTo("retry")
        assertThat(classify(OmniError.Http(400, null))).isEqualTo("give-up")
        assertThat(classify(OmniError.Unauthorized)).isEqualTo("reauthenticate")
        assertThat(classify(OmniError.Storage("d"))).isEqualTo("report")
    }
}
