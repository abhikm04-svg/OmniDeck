package com.omnideck.notes.sync

import com.omnideck.notes.data.NotePayload
import com.omnideck.sdk.sync.OutboxRecord
import com.omnideck.sdk.sync.PushOutcome
import com.omnideck.sdk.sync.SyncTransport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** What the Notes service answers with on success. */
@Serializable
private data class AckResponse(val version: Long)

/** What it answers with on 409, so the resolver has both sides of the conflict. */
@Serializable
private data class ConflictResponse(val version: Long, val updatedAtEpochMs: Long, val note: NotePayload? = null)

/**
 * Delivers one outbox record to the Notes service.
 *
 * The whole of this class is the mapping from HTTP to [PushOutcome], and that mapping
 * is the part worth being careful about: getting it wrong means either a permanent
 * failure retried forever (battery, server load, an outbox that never drains) or a
 * transient one dead-lettered on the first blip (silent data loss). Anything the
 * server can fix is retryable; anything only a new client version can fix is not.
 */
class NotesSyncTransport(
    private val baseUrl: String,
    private val clientFactory: () -> OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SyncTransport {

    override suspend fun push(record: OutboxRecord): PushOutcome {
        val url = "${baseUrl.trimEnd('/')}/notes/${record.entityId}".toHttpUrlOrNull()
            ?: return PushOutcome.Rejected("Malformed sync endpoint: $baseUrl")

        val request = Request.Builder()
            .url(url)
            .header("If-Match", record.baseVersion.toString())
            .apply {
                when (record.operation) {
                    OutboxRecord.Operation.UPSERT ->
                        put(record.payload.orEmpty().toRequestBody(JSON_MEDIA_TYPE))

                    OutboxRecord.Operation.DELETE -> delete()
                }
            }
            .build()

        return try {
            clientFactory().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                interpret(response.code, body)
            }
        } catch (e: IOException) {
            // Offline, DNS failure, TLS handshake, read timeout. Every one of these
            // resolves itself given time, so none of them may lose the user's edit.
            PushOutcome.Retryable(e.message ?: "Network unavailable")
        }
    }

    private fun interpret(code: Int, body: String): PushOutcome = when {
        code in SUCCESS_RANGE -> PushOutcome.Applied(remoteVersion = decodeVersion(body))

        code == HTTP_CONFLICT -> decodeConflict(body)

        // 408 timeout, 425 too early, 429 rate limited, and everything the server
        // admits is its own fault.
        code in RETRYABLE_CODES || code >= HTTP_SERVER_ERROR -> PushOutcome.Retryable("HTTP $code")

        // 400, 401, 403, 404, 422 — the request itself is wrong. Repeating it
        // unchanged cannot help, and doing so forever is how an outbox wedges.
        else -> PushOutcome.Rejected("HTTP $code")
    }

    private fun decodeVersion(body: String): Long =
        runCatching { json.decodeFromString<AckResponse>(body).version }.getOrDefault(UNKNOWN_VERSION)

    /**
     * A 409 without a parseable body still has to become a conflict rather than a
     * rejection — the server is telling us the versions diverged, and dropping the
     * user's edit because the error payload was malformed would be the worse failure.
     */
    private fun decodeConflict(body: String): PushOutcome {
        val decoded = runCatching { json.decodeFromString<ConflictResponse>(body) }.getOrNull()
        return PushOutcome.Conflict(
            remoteVersion = decoded?.version ?: UNKNOWN_VERSION,
            remotePayload = decoded?.note?.let { json.encodeToString(it) },
            remoteUpdatedAtEpochMs = decoded?.updatedAtEpochMs ?: 0L,
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val SUCCESS_RANGE = 200..299
        val RETRYABLE_CODES = setOf(408, 425, 429)
        const val HTTP_CONFLICT = 409
        const val HTTP_SERVER_ERROR = 500

        /**
         * Used when the server accepted the write but said nothing useful about the
         * version. Zero, not -1: it means "assume the server has never seen this",
         * so the next edit sends the change again rather than claiming a version the
         * server would then reject.
         */
        const val UNKNOWN_VERSION = 0L
    }
}
