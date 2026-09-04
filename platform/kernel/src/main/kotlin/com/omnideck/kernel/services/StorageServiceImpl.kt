package com.omnideck.kernel.services

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.RoomDatabase
import com.omnideck.core.DispatcherProvider
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.PurgeScope
import com.omnideck.sdk.capability.StorageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-005 — database-per-module, on device.
 *
 * Every path is rooted at `files/modules/<moduleId>/`, so isolation is a filesystem
 * property rather than a coding convention. Two consequences worth stating:
 *
 *  - a module physically cannot open another module's database, even by accident;
 *  - "erase everything this module knows about the user" is `deleteRecursively()`,
 *    which is what makes GDPR/DPDP erasure a deterministic, testable operation
 *    instead of an archaeology exercise (architecture.md §11.1).
 */
class StorageServiceImpl(
    private val context: Context,
    private val moduleId: ModuleId,
    private val dispatchers: DispatcherProvider,
) : StorageService {

    private val databases = ConcurrentHashMap<String, RoomDatabase>()

    private val moduleRoot: File
        get() = File(context.filesDir, "modules/${moduleId.value}").apply { mkdirs() }

    private val moduleCache: File
        get() = File(context.cacheDir, "modules/${moduleId.value}").apply { mkdirs() }

    @Suppress("UNCHECKED_CAST")
    override fun <T : RoomDatabase> database(
        name: String,
        klass: Class<T>,
        configure: RoomDatabase.Builder<T>.() -> Unit,
    ): T = databases.getOrPut(name) {
        val file = File(moduleRoot, "db/$name.db").apply { parentFile?.mkdirs() }
        Room.databaseBuilder(context.applicationContext, klass, file.absolutePath)
            .apply(configure)
            .build()
    } as T

    override fun preferences(name: String): DataStore<Preferences> {
        val file = File(moduleRoot, "datastore/$name.preferences_pb").apply { parentFile?.mkdirs() }
        return ActiveDataStores.open(file) { PreferenceDataStoreFactory.create(scope = it, produceFile = { file }) }
    }

    override fun filesDir(): File = File(moduleRoot, "files").apply { mkdirs() }

    override fun cacheDir(): File = moduleCache

    /**
     * DataStores, keyed by file, for the whole process.
     *
     * DataStore's contract is one instance per file *per process* — a second one on the
     * same path throws. That cannot be honoured by a cache living on
     * [StorageServiceImpl], because the factory drops and rebuilds that object whenever
     * a module is purged and re-initialised, so the second incarnation would build a
     * second DataStore over a file the first one still holds. Keying on the resolved
     * file, above the object lifetime, is what makes the rule hold.
     *
     * Each entry owns the scope it was created with so releasing can cancel it — the
     * only way the file is genuinely let go.
     */
    private object ActiveDataStores {

        private val open = ConcurrentHashMap<String, Entry>()

        private class Entry(val store: DataStore<Preferences>, val scope: CoroutineScope)

        fun open(file: File, create: (CoroutineScope) -> DataStore<Preferences>): DataStore<Preferences> =
            open.getOrPut(file.absolutePath) {
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                Entry(create(scope), scope)
            }.store

        /** Cancels and forgets every DataStore whose file sits under [root]. */
        fun releaseUnder(root: File) {
            val prefix = root.absolutePath
            open.keys
                .filter { it == prefix || it.startsWith(prefix + File.separator) }
                .forEach { key -> open.remove(key)?.scope?.cancel() }
        }
    }

    override suspend fun usageBytes(): Long = withContext(dispatchers.io) {
        moduleRoot.walkBottomUp().sumOf { if (it.isFile) it.length() else 0L } +
            moduleCache.walkBottomUp().sumOf { if (it.isFile) it.length() else 0L }
    }

    override suspend fun clear(scope: PurgeScope) = withContext(dispatchers.io) {
        when (scope) {
            PurgeScope.CACHE -> {
                moduleCache.deleteRecursively()
            }

            PurgeScope.SESSION -> {
                moduleCache.deleteRecursively()
                // Released before the files go, for the same reason as ALL below.
                ActiveDataStores.releaseUnder(File(moduleRoot, "datastore"))
                File(moduleRoot, "datastore").deleteRecursively()
            }

            PurgeScope.ALL -> {
                databases.values.forEach { runCatching { it.close() } }
                databases.clear()
                // Cancelling, not just forgetting. Dropping the reference leaves the
                // DataStore's own coroutine alive and still holding the file, so the
                // next `preferences()` call builds a second one on the same path and
                // DataStore throws "There are multiple DataStores active for the same
                // file" — on a device, after a remove-and-reinstall, in a module that
                // had done nothing wrong.
                ActiveDataStores.releaseUnder(moduleRoot)
                moduleRoot.deleteRecursively()
                moduleCache.deleteRecursively()
            }
        }
        Unit
    }
}
