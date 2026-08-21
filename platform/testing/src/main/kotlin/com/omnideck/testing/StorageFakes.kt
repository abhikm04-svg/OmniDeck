package com.omnideck.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.RoomDatabase
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.capability.StorageService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [StorageService] backed by a real temporary directory.
 *
 * Files and preferences behave for real, so a module's persistence logic — including
 * `purge()` — can be tested with no Shell, no Android runtime and no Robolectric.
 *
 * **Room is the deliberate exception.** [database] cannot be honoured in a plain JVM
 * test: Room codegen needs a `Context`. Rather than return a half-working stub that
 * fails confusingly deep inside a DAO call, this throws immediately with instructions.
 * Supply [databaseFactory] to hand back your own in-memory database, or exercise Room
 * in an instrumented test where it can work properly.
 *
 * ```
 * val storage = FakeStorageService()
 * val services = FakePlatformServices(storage = storage)
 * // ...exercise the module...
 * assertThat(storage.filesDir().resolve("draft.json")).exists()
 * storage.clear(PurgeScope.ALL)
 * assertThat(storage.usageBytes()).isEqualTo(0)
 * ```
 */
class FakeStorageService(
    /** Root for this fake's files. Defaults to a fresh temp directory per instance. */
    root: File = createTempDirectory(),
    /**
     * Optional Room stand-in. Receives the requested name and class; return an instance
     * (typically `Room.inMemoryDatabaseBuilder(...)` from an instrumented test) or null
     * to fall through to the default error.
     */
    private val databaseFactory: ((name: String, klass: Class<*>) -> Any?)? = null,
) : StorageService {

    private val filesRoot = File(root, "files").apply { mkdirs() }
    private val cacheRoot = File(root, "cache").apply { mkdirs() }

    private val preferenceStores = ConcurrentHashMap<String, InMemoryPreferencesDataStore>()

    /** Every `database(name)` asked for, in call order — assert on wiring without a real DB. */
    val requestedDatabases = mutableListOf<String>()

    /** Every `preferences(name)` asked for, in call order. */
    val requestedPreferences = mutableListOf<String>()

    /** Purge calls received, in order. Lets a test assert the kernel's fan-out reached here. */
    val purges = mutableListOf<PurgeScope>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : RoomDatabase> database(
        name: String,
        klass: Class<T>,
        configure: RoomDatabase.Builder<T>.() -> Unit,
    ): T {
        requestedDatabases += name
        val supplied = databaseFactory?.invoke(name, klass)
        if (supplied != null) return supplied as T
        error(
            "FakeStorageService cannot build the Room database '$name' (${klass.simpleName}): " +
                "Room needs an Android Context. Either pass a `databaseFactory` to " +
                "FakeStorageService, or move this case to an instrumented test.",
        )
    }

    override fun preferences(name: String): DataStore<Preferences> {
        requestedPreferences += name
        return preferenceStores.getOrPut(name) { InMemoryPreferencesDataStore() }
    }

    override fun filesDir(): File = filesRoot

    override fun cacheDir(): File = cacheRoot

    override suspend fun usageBytes(): Long = filesRoot.walkBottomUp().sumOf { if (it.isFile) it.length() else 0L } +
        cacheRoot.walkBottomUp().sumOf { if (it.isFile) it.length() else 0L }

    override suspend fun clear(scope: PurgeScope) {
        purges += scope
        when (scope) {
            // Caches only — persistent files and preferences survive.
            PurgeScope.CACHE -> cacheRoot.clearContents()

            // Session data. The fake has no notion of which preferences are
            // session-scoped, so it clears preferences and caches but keeps files,
            // matching "sign out should not delete the user's downloaded content".
            PurgeScope.SESSION -> {
                cacheRoot.clearContents()
                preferenceStores.values.forEach { it.reset() }
            }

            PurgeScope.ALL -> {
                cacheRoot.clearContents()
                filesRoot.clearContents()
                preferenceStores.values.forEach { it.reset() }
            }
        }
    }

    private fun File.clearContents() {
        listFiles()?.forEach { it.deleteRecursively() }
    }

    private companion object {
        fun createTempDirectory(): File = File.createTempFile("omnideck-fake-storage", "").let { probe ->
            probe.delete()
            probe.apply { mkdirs() }
        }
    }
}

/**
 * A [DataStore] of [Preferences] held entirely in memory.
 *
 * Implemented directly rather than pointing the real `PreferenceDataStoreFactory` at a
 * temp file: no disk I/O, no scope to clean up, and updates are visible immediately,
 * which keeps assertions free of arbitrary waits.
 */
class InMemoryPreferencesDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {

    private val state = MutableStateFlow(initial)

    // Serialises read-modify-write so concurrent updateData calls cannot interleave,
    // the same guarantee the real DataStore gives.
    private val writeLock = Mutex()

    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writeLock.withLock {
            val updated = transform(state.value)
            state.value = updated
            updated
        }

    /** Drops everything back to empty. Used by [FakeStorageService.clear]. */
    fun reset() {
        state.value = emptyPreferences()
    }
}
