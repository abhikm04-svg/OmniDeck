package com.omnideck.notes.sync

import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.sync.OutboxRecord
import com.omnideck.sdk.sync.PushOutcome
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The HTTP-to-[PushOutcome] mapping, which is the only interesting thing this class
 * does and the one place a mistake is expensive: classify a permanent failure as
 * retryable and the outbox never drains; classify a transient one as permanent and
 * the user's note is quietly never sent.
 */
class NotesSyncTransportTest {

    private val server = MockWebServer()
    private lateinit var transport: NotesSyncTransport

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        transport = NotesSyncTransport(baseUrl = server.url("/api").toString(), clientFactory = { client })
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a 200 with a version is applied`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":5}"""))

        assertThat(transport.push(upsert())).isEqualTo(PushOutcome.Applied(remoteVersion = 5))
    }

    @Test
    fun `an upsert is a PUT carrying the payload and the base version`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":1}"""))

        transport.push(upsert(payload = """{"id":"n1"}""", baseVersion = 3))

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.path).isEqualTo("/api/notes/n1")
        assertThat(request.getHeader("If-Match")).isEqualTo("3")
        assertThat(request.body.readUtf8()).isEqualTo("""{"id":"n1"}""")
    }

    @Test
    fun `a delete is a DELETE with no body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204).setBody(""))

        transport.push(upsert().copy(operation = OutboxRecord.Operation.DELETE, payload = null))

        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    @Test
    fun `a success with no parseable body still applies, at version zero`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204).setBody(""))

        assertThat(transport.push(upsert())).isEqualTo(PushOutcome.Applied(remoteVersion = 0))
    }

    @Test
    fun `a 409 becomes a conflict carrying both sides`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """
                {"version":8,"updatedAtEpochMs":1234,
                 "note":{"id":"n1","title":"Theirs","body":"b","updatedAtEpochMs":1234}}
                """.trimIndent(),
            ),
        )

        val outcome = transport.push(upsert()) as PushOutcome.Conflict

        assertThat(outcome.remoteVersion).isEqualTo(8)
        assertThat(outcome.remoteUpdatedAtEpochMs).isEqualTo(1234)
        assertThat(outcome.remotePayload).contains("Theirs")
    }

    @Test
    fun `a 409 with an unreadable body is still a conflict, not a rejection`() = runTest {
        // Dropping the user's edit because the *error* payload was malformed would be
        // the worse of the two failures.
        server.enqueue(MockResponse().setResponseCode(409).setBody("<html>gateway</html>"))

        assertThat(transport.push(upsert())).isInstanceOf(PushOutcome.Conflict::class.java)
    }

    @Test
    fun `server errors and rate limits are retryable`() = runTest {
        listOf(408, 425, 429, 500, 503).forEach { code ->
            alwaysRespond(code)
            assertThat(transport.push(upsert())).isEqualTo(PushOutcome.Retryable("HTTP $code"))
        }
    }

    @Test
    fun `client errors are permanent`() = runTest {
        listOf(400, 401, 403, 404, 422).forEach { code ->
            alwaysRespond(code)
            assertThat(transport.push(upsert())).isEqualTo(PushOutcome.Rejected("HTTP $code"))
        }
    }

    @Test
    fun `being offline is retryable, never a lost edit`() = runTest {
        server.shutdown()

        assertThat(transport.push(upsert())).isInstanceOf(PushOutcome.Retryable::class.java)
    }

    @Test
    fun `a malformed endpoint is rejected rather than retried forever`() = runTest {
        val broken = NotesSyncTransport(baseUrl = "not a url", clientFactory = { OkHttpClient() })

        assertThat(broken.push(upsert())).isInstanceOf(PushOutcome.Rejected::class.java)
    }

    /**
     * A standing answer rather than a queued one.
     *
     * OkHttp transparently retries a 408 and some 503s of its own accord, so a queue
     * of one-shot responses would hand the *next* code's response to the retry and
     * the assertion would silently check the wrong status.
     */
    private fun alwaysRespond(code: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code)
        }
    }

    private fun upsert(payload: String? = "{}", baseVersion: Long = 0) = OutboxRecord(
        id = 1,
        entityType = "note",
        entityId = "n1",
        operation = OutboxRecord.Operation.UPSERT,
        payload = payload,
        baseVersion = baseVersion,
    )
}
