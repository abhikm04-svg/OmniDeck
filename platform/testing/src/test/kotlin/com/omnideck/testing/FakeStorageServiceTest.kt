package com.omnideck.testing

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.RoomDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeStorageServiceTest {

    @Test
    fun `files and cache directories are separate and writable`() {
        val storage = FakeStorageService()

        storage.filesDir().resolve("note.txt").writeText("persistent")
        storage.cacheDir().resolve("thumb.bin").writeText("disposable")

        assertThat(storage.filesDir().resolve("note.txt").readText()).isEqualTo("persistent")
        assertThat(storage.cacheDir().resolve("thumb.bin").readText()).isEqualTo("disposable")
        assertThat(storage.filesDir().canonicalPath).isNotEqualTo(storage.cacheDir().canonicalPath)
    }

    @Test
    fun `two instances do not share a root`() {
        // Otherwise one test's leftovers leak into the next.
        val a = FakeStorageService()
        val b = FakeStorageService()

        assertThat(a.filesDir().canonicalPath).isNotEqualTo(b.filesDir().canonicalPath)
    }

    @Test
    fun `usageBytes counts files and caches`() = runTest {
        val storage = FakeStorageService()
        assertThat(storage.usageBytes()).isEqualTo(0)

        storage.filesDir().resolve("a").writeBytes(ByteArray(100))
        storage.cacheDir().resolve("b").writeBytes(ByteArray(50))

        assertThat(storage.usageBytes()).isEqualTo(150)
    }

    @Test
    fun `usageBytes includes nested directories`() = runTest {
        val storage = FakeStorageService()
        val nested = storage.filesDir().resolve("deep/deeper").apply { mkdirs() }
        nested.resolve("c").writeBytes(ByteArray(25))

        assertThat(storage.usageBytes()).isEqualTo(25)
    }

    // -- purge semantics ----------------------------------------------------
    // Each scope must differ, otherwise a module's purge() handling is untestable.

    @Test
    fun `CACHE purge clears caches and keeps persistent files`() = runTest {
        val storage = FakeStorageService()
        storage.filesDir().resolve("keep").writeText("x")
        storage.cacheDir().resolve("drop").writeText("y")

        storage.clear(com.omnideck.sdk.PurgeScope.CACHE)

        assertThat(storage.filesDir().resolve("keep").exists()).isTrue()
        assertThat(storage.cacheDir().resolve("drop").exists()).isFalse()
    }

    @Test
    fun `SESSION purge clears preferences and caches but keeps downloaded files`() = runTest {
        val storage = FakeStorageService()
        val key = stringPreferencesKey("token")
        storage.preferences().edit { it[key] = "secret" }
        storage.filesDir().resolve("download.pdf").writeText("content")
        storage.cacheDir().resolve("tmp").writeText("y")

        storage.clear(com.omnideck.sdk.PurgeScope.SESSION)

        assertThat(storage.preferences().data.first()[key]).isNull()
        assertThat(storage.cacheDir().resolve("tmp").exists()).isFalse()
        // Signing out should not delete the user's downloaded content.
        assertThat(storage.filesDir().resolve("download.pdf").exists()).isTrue()
    }

    @Test
    fun `ALL purge erases everything`() = runTest {
        val storage = FakeStorageService()
        val key = stringPreferencesKey("token")
        storage.preferences().edit { it[key] = "secret" }
        storage.filesDir().resolve("f").writeText("x")
        storage.cacheDir().resolve("c").writeText("y")

        storage.clear(com.omnideck.sdk.PurgeScope.ALL)

        assertThat(storage.usageBytes()).isEqualTo(0)
        assertThat(storage.preferences().data.first()[key]).isNull()
    }

    @Test
    fun `purges are recorded so the kernel fan-out can be asserted`() = runTest {
        val storage = FakeStorageService()

        storage.clear(com.omnideck.sdk.PurgeScope.CACHE)
        storage.clear(com.omnideck.sdk.PurgeScope.ALL)

        assertThat(storage.purges).containsExactly(
            com.omnideck.sdk.PurgeScope.CACHE,
            com.omnideck.sdk.PurgeScope.ALL,
        ).inOrder()
    }

    // -- preferences --------------------------------------------------------

    @Test
    fun `preferences persist across calls and are namespaced by name`() = runTest {
        val storage = FakeStorageService()
        val key = stringPreferencesKey("k")

        storage.preferences("alpha").edit { it[key] = "from-alpha" }
        storage.preferences("beta").edit { it[key] = "from-beta" }

        assertThat(storage.preferences("alpha").data.first()[key]).isEqualTo("from-alpha")
        assertThat(storage.preferences("beta").data.first()[key]).isEqualTo("from-beta")
    }

    @Test
    fun `preferences returns the same store for the same name`() {
        val storage = FakeStorageService()

        assertThat(storage.preferences("settings")).isSameInstanceAs(storage.preferences("settings"))
    }

    @Test
    fun `preference updates are visible immediately without waiting`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val flag = booleanPreferencesKey("enabled")

        store.updateData { it.toMutablePreferences().apply { set(flag, true) } }

        assertThat(store.data.first()[flag]).isTrue()
    }

    @Test
    fun `updateData returns the updated snapshot`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val key = stringPreferencesKey("k")

        val returned = store.updateData { it.toMutablePreferences().apply { set(key, "v") } }

        assertThat(returned[key]).isEqualTo("v")
    }

    // -- Room, the deliberate gap -------------------------------------------

    @Test
    fun `database without a factory fails with actionable guidance`() {
        val storage = FakeStorageService()

        val error = runCatching {
            storage.database("notes", RoomDatabase::class.java)
        }.exceptionOrNull()

        // The message must say what to do, not just that it failed — this path is
        // hit by anyone writing their first module test against Room.
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().contains("databaseFactory")
        assertThat(error).hasMessageThat().contains("instrumented test")
    }

    @Test
    fun `database delegates to an injected factory when provided`() {
        val stub = object : RoomDatabase() {
            override fun createOpenDelegate() = error("unused")
            override fun createInvalidationTracker() = error("unused")
            override fun clearAllTables() = Unit
        }
        val storage = FakeStorageService(databaseFactory = { _, _ -> stub })

        assertThat(storage.database("notes", RoomDatabase::class.java)).isSameInstanceAs(stub)
    }

    @Test
    fun `requested databases and preferences are recorded`() {
        val storage = FakeStorageService(databaseFactory = { _, _ -> null })
        runCatching { storage.database("notes", RoomDatabase::class.java) }
        storage.preferences("settings")

        assertThat(storage.requestedDatabases).containsExactly("notes")
        assertThat(storage.requestedPreferences).containsExactly("settings")
    }
}
