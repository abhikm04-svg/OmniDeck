package com.omnideck.kernel.services

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.omnideck.core.DispatcherProvider
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.PurgeScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ADR-005's central claim is that per-module isolation is a *filesystem* property
 * rather than a coding convention, and that erasure is therefore deterministic.
 * These tests check exactly that, on a real Context via Robolectric — the paths are
 * the whole point, so faking the filesystem would test nothing.
 */
@RunWith(RobolectricTestRunner::class)
class StorageServiceImplTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val unconfined = Dispatchers.Unconfined
    }

    private fun storage(moduleId: String = "com.omnideck.notes") =
        StorageServiceImpl(context, ModuleId(moduleId), dispatchers)

    // -- namespacing --------------------------------------------------------

    @Test
    fun `files and caches are rooted under the module's own directory`() {
        val storage = storage()

        assertThat(storage.filesDir().absolutePath)
            .contains("modules${File.separator}com.omnideck.notes")
        assertThat(storage.cacheDir().absolutePath)
            .contains("modules${File.separator}com.omnideck.notes")
    }

    @Test
    fun `two modules never share a directory`() {
        // The isolation claim, stated as a path property.
        val notes = storage("com.omnideck.notes")
        val finance = storage("com.omnideck.finance")

        assertThat(notes.filesDir().canonicalPath).isNotEqualTo(finance.filesDir().canonicalPath)
        assertThat(notes.cacheDir().canonicalPath).isNotEqualTo(finance.cacheDir().canonicalPath)
    }

    @Test
    fun `one module cannot see another's files through its own root`() {
        val notes = storage("com.omnideck.notes")
        val finance = storage("com.omnideck.finance")
        notes.filesDir().resolve("secret.txt").writeText("notes data")

        assertThat(finance.filesDir().resolve("secret.txt").exists()).isFalse()
    }

    @Test
    fun `directories are created on demand`() {
        val storage = storage("com.omnideck.fresh")

        assertThat(storage.filesDir().isDirectory).isTrue()
        assertThat(storage.cacheDir().isDirectory).isTrue()
    }

    @Test
    fun `preferences are namespaced per module and per name`() {
        val notes = storage("com.omnideck.notes")

        assertThat(notes.preferences("settings")).isSameInstanceAs(notes.preferences("settings"))
        assertThat(notes.preferences("settings")).isNotSameInstanceAs(notes.preferences("other"))
    }

    // -- usage --------------------------------------------------------------

    @Test
    fun `usageBytes counts files and caches for this module only`() = runTest {
        val notes = storage("com.omnideck.notes")
        val finance = storage("com.omnideck.finance")

        notes.filesDir().resolve("a").writeBytes(ByteArray(120))
        notes.cacheDir().resolve("b").writeBytes(ByteArray(80))
        finance.filesDir().resolve("theirs").writeBytes(ByteArray(9_999))

        assertThat(notes.usageBytes()).isEqualTo(200)
    }

    @Test
    fun `usageBytes is zero for a module that has written nothing`() = runTest {
        assertThat(storage("com.omnideck.empty").usageBytes()).isEqualTo(0)
    }

    // -- purge --------------------------------------------------------------
    // The three scopes must genuinely differ; collapsing them would make a module's
    // purge() handling untestable and erasure claims unverifiable.

    @Test
    fun `CACHE purge clears the cache and leaves persistent files`() = runTest {
        val storage = storage()
        storage.filesDir().resolve("keep").writeText("x")
        storage.cacheDir().resolve("drop").writeText("y")

        storage.clear(PurgeScope.CACHE)

        assertThat(storage.filesDir().resolve("keep").exists()).isTrue()
        assertThat(storage.cacheDir().resolve("drop").exists()).isFalse()
    }

    @Test
    fun `SESSION purge drops the datastore but keeps downloaded files`() = runTest {
        val storage = storage()
        val datastore = File(storage.filesDir().parentFile, "datastore").apply { mkdirs() }
        datastore.resolve("settings.preferences_pb").writeText("token")
        storage.filesDir().resolve("download.pdf").writeText("content")

        storage.clear(PurgeScope.SESSION)

        assertThat(datastore.exists()).isFalse()
        // Signing out must not delete what the user saved.
        assertThat(storage.filesDir().resolve("download.pdf").exists()).isTrue()
    }

    @Test
    fun `ALL purge erases everything the module owns`() = runTest {
        // The GDPR/DPDP erasure guarantee: a directory delete, not an audit.
        val storage = storage()
        storage.filesDir().resolve("f").writeText("x")
        storage.cacheDir().resolve("c").writeText("y")

        storage.clear(PurgeScope.ALL)

        assertThat(storage.usageBytes()).isEqualTo(0)
    }

    @Test
    fun `ALL purge leaves other modules untouched`() = runTest {
        val notes = storage("com.omnideck.notes")
        val finance = storage("com.omnideck.finance")
        notes.filesDir().resolve("mine").writeText("x")
        finance.filesDir().resolve("theirs").writeText("y")

        notes.clear(PurgeScope.ALL)

        assertThat(finance.filesDir().resolve("theirs").exists()).isTrue()
    }

    @Test
    fun `purging a module that has written nothing is harmless`() = runTest {
        val storage = storage("com.omnideck.untouched")

        storage.clear(PurgeScope.ALL)

        assertThat(storage.usageBytes()).isEqualTo(0)
    }
}
