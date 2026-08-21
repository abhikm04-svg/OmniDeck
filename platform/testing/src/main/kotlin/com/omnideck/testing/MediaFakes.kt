package com.omnideck.testing

import android.net.Uri
import com.omnideck.sdk.capability.MediaService
import java.io.File

/**
 * Scriptable [MediaService] fake.
 *
 * A test primes what the picker "returns", then asserts on what the module did with it.
 * The pickers are queues rather than fixed values, so a flow that picks twice can be
 * given two different answers; an exhausted queue returns empty/null, which is the same
 * shape as a user cancelling.
 *
 * The fake never constructs a [Uri] itself. `Uri.parse` returns null in a plain JVM unit
 * test, so a fake that built its own would hand back nulls that look like cancellation.
 * Tests supply their own instances — from Robolectric, an instrumented test, or a stub.
 *
 * ```
 * val media = FakeMediaService()
 * media.enqueueImages(listOf(someUri))
 * media.setContent(someUri, "hello".toByteArray())
 * // ...module picks and imports...
 * assertThat(media.imported).containsExactly("avatar.png")
 * ```
 */
class FakeMediaService(
    /** Where [importToModuleStorage] writes. Defaults to a fresh temp directory. */
    private val importRoot: File = createTempDirectory(),
) : MediaService {

    private val imageResults = ArrayDeque<List<Uri>>()
    private val documentResults = ArrayDeque<List<Uri>>()
    private val captureResults = ArrayDeque<Uri?>()
    private val contents = mutableMapOf<Uri, ByteArray>()

    /** `allowMultiple` values passed to [pickImage], in call order. */
    val imagePicks = mutableListOf<Boolean>()

    /** MIME type lists passed to [pickDocument], in call order. */
    val documentPicks = mutableListOf<List<String>>()

    /** Number of [captureImage] calls. */
    var captureCount: Int = 0
        private set

    /** File names successfully imported, in order. */
    val imported = mutableListOf<String>()

    override suspend fun pickImage(allowMultiple: Boolean): List<Uri> {
        imagePicks += allowMultiple
        val next = imageResults.removeFirstOrNull().orEmpty()
        // Honour the contract: a single-select picker never yields more than one.
        return if (!allowMultiple) next.take(1) else next
    }

    override suspend fun pickDocument(mimeTypes: List<String>): List<Uri> {
        documentPicks += mimeTypes
        return documentResults.removeFirstOrNull().orEmpty()
    }

    override suspend fun captureImage(): Uri? {
        captureCount++
        return captureResults.removeFirstOrNull()
    }

    override suspend fun importToModuleStorage(uri: Uri, fileName: String): File? {
        // No primed content means the Uri is not readable — the same outcome as a
        // revoked permission or a file the user deleted between picking and importing.
        val bytes = contents[uri] ?: return null
        val target = File(importRoot, fileName)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        imported += fileName
        return target
    }

    // -- test controls ------------------------------------------------------

    /** Queues one result for the next [pickImage] call. */
    fun enqueueImages(uris: List<Uri>) {
        imageResults += uris
    }

    /** Queues one result for the next [pickDocument] call. */
    fun enqueueDocuments(uris: List<Uri>) {
        documentResults += uris
    }

    /** Queues one result for the next [captureImage] call; null models cancellation. */
    fun enqueueCapture(uri: Uri?) {
        captureResults += uri
    }

    /** Makes [uri] readable by [importToModuleStorage], returning [bytes]. */
    fun setContent(uri: Uri, bytes: ByteArray) {
        contents[uri] = bytes
    }

    private companion object {
        fun createTempDirectory(): File =
            File.createTempFile("omnideck-fake-media", "").let { probe ->
                probe.delete()
                probe.apply { mkdirs() }
            }
    }
}
