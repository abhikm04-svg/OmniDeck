package com.omnideck.testing

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeMediaServiceTest {

    // Uri is abstract and Uri.parse returns null off-device, so tests mint their own
    // instances. Identity is all this fake needs from them.
    private fun uri(): Uri = mockk(relaxed = true)

    @Test
    fun `pickImage returns the queued result`() = runTest {
        val media = FakeMediaService()
        val picked = uri()
        media.enqueueImages(listOf(picked))

        assertThat(media.pickImage(allowMultiple = true)).containsExactly(picked)
    }

    @Test
    fun `single select truncates a multi-item queued result`() = runTest {
        // Honours the contract rather than the priming mistake: a single-select
        // picker can never hand back two items on a real device.
        val media = FakeMediaService()
        media.enqueueImages(listOf(uri(), uri()))

        assertThat(media.pickImage(allowMultiple = false)).hasSize(1)
    }

    @Test
    fun `successive picks consume the queue in order`() = runTest {
        val media = FakeMediaService()
        val first = uri()
        val second = uri()
        media.enqueueImages(listOf(first))
        media.enqueueImages(listOf(second))

        assertThat(media.pickImage(true)).containsExactly(first)
        assertThat(media.pickImage(true)).containsExactly(second)
    }

    @Test
    fun `an exhausted queue models cancellation`() = runTest {
        val media = FakeMediaService()

        assertThat(media.pickImage(true)).isEmpty()
        assertThat(media.pickDocument(listOf("application/pdf"))).isEmpty()
        assertThat(media.captureImage()).isNull()
    }

    @Test
    fun `pick arguments are recorded`() = runTest {
        val media = FakeMediaService()

        media.pickImage(allowMultiple = true)
        media.pickImage(allowMultiple = false)
        media.pickDocument(listOf("application/pdf", "text/csv"))
        media.captureImage()

        assertThat(media.imagePicks).containsExactly(true, false).inOrder()
        assertThat(media.documentPicks).containsExactly(listOf("application/pdf", "text/csv"))
        assertThat(media.captureCount).isEqualTo(1)
    }

    @Test
    fun `captureImage returns a queued uri then null`() = runTest {
        val media = FakeMediaService()
        val shot = uri()
        media.enqueueCapture(shot)

        assertThat(media.captureImage()).isSameInstanceAs(shot)
        assertThat(media.captureImage()).isNull()
    }

    @Test
    fun `enqueueCapture accepts null to model an immediate cancel`() = runTest {
        val media = FakeMediaService()
        media.enqueueCapture(null)

        assertThat(media.captureImage()).isNull()
        assertThat(media.captureCount).isEqualTo(1)
    }

    // -- import -------------------------------------------------------------

    @Test
    fun `import writes primed content into module storage`() = runTest {
        val media = FakeMediaService()
        val source = uri()
        media.setContent(source, "hello".toByteArray())

        val file = media.importToModuleStorage(source, "avatar.png")

        assertThat(file).isNotNull()
        assertThat(file!!.readText()).isEqualTo("hello")
        assertThat(media.imported).containsExactly("avatar.png")
    }

    @Test
    fun `import of an unreadable uri returns null rather than throwing`() = runTest {
        // Models a revoked permission or a file deleted between pick and import —
        // a path modules must handle, so the fake has to be able to produce it.
        val media = FakeMediaService()

        val file = media.importToModuleStorage(uri(), "missing.png")

        assertThat(file).isNull()
        assertThat(media.imported).isEmpty()
    }

    @Test
    fun `import creates nested directories`() = runTest {
        val media = FakeMediaService()
        val source = uri()
        media.setContent(source, byteArrayOf(1, 2, 3))

        val file = media.importToModuleStorage(source, "nested/deep/file.bin")

        assertThat(file).isNotNull()
        assertThat(file!!.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }
}
