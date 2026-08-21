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
import kotlinx.coroutines.SupervisorJob
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
    private val dataStores = ConcurrentHashMap<String, DataStore<Preferences>>()

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

    override fun preferences(name: String): DataStore<Preferences> = dataStores.getOrPut(name) {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatchers.io + SupervisorJob()),
            produceFile = { File(moduleRoot, "datastore/$name.preferences_pb").apply { parentFile?.mkdirs() } },
        )
    }

    override fun filesDir(): File = File(moduleRoot, "files").apply { mkdirs() }

    override fun cacheDir(): File = moduleCache

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
                File(moduleRoot, "datastore").deleteRecursively()
            }

            PurgeScope.ALL -> {
                databases.values.forEach { runCatching { it.close() } }
                databases.clear()
                dataStores.clear()
                moduleRoot.deleteRecursively()
                moduleCache.deleteRecursively()
            }
        }
        Unit
    }
}
