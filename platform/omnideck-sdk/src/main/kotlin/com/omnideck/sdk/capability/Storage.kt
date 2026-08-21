package com.omnideck.sdk.capability

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.RoomDatabase
import java.io.File

/**
 * Per-module persistence (ADR-005).
 *
 * There is no shared application database. Every path handed back here is already
 * namespaced under `files/modules/<moduleId>/`, so a module physically cannot read
 * another module's data, and "erase everything this module knows" is a directory
 * delete rather than a schema audit.
 */
interface StorageService {

    /**
     * Builds (or returns) a Room database owned by this module.
     * The file name is namespaced; passing `"notes"` yields `modules/<id>/notes.db`.
     */
    fun <T : RoomDatabase> database(
        name: String,
        klass: Class<T>,
        configure: RoomDatabase.Builder<T>.() -> Unit = {},
    ): T

    /** Namespaced preferences DataStore. */
    fun preferences(name: String = "settings"): DataStore<Preferences>

    /** Module-private persistent directory. Survives cache eviction. */
    fun filesDir(): File

    /** Module-private cache directory. May be evicted by the kernel under storage pressure. */
    fun cacheDir(): File

    /** Bytes currently used by this module across all of the above. */
    suspend fun usageBytes(): Long

    /** Deletes everything in [scope] for this module. Called by the kernel during purge. */
    suspend fun clear(scope: com.omnideck.sdk.PurgeScope)
}

/**
 * Keystore-backed secret storage (ADR-007 — Tink AEAD over an Android Keystore master
 * key, because `androidx.security:security-crypto` is deprecated).
 *
 * Keys are derived per module, so one module's ciphertext is undecryptable by another
 * even with filesystem access on a rooted device.
 */
interface SecureStore {

    suspend fun put(alias: String, value: ByteArray)

    suspend fun get(alias: String): ByteArray?

    suspend fun delete(alias: String)

    suspend fun contains(alias: String): Boolean

    /**
     * Stores a value behind a key that requires user authentication to unwrap
     * (`setUserAuthenticationRequired`). Reading it later prompts for biometrics.
     * Returns false if the device has no enrolled biometric or secure lock screen.
     */
    suspend fun putBiometricGated(alias: String, value: ByteArray, timeoutSeconds: Int = 30): Boolean

    /** Prompts and returns the value, or null if the user cancels or fails. */
    suspend fun getBiometricGated(alias: String, promptTitle: String, promptSubtitle: String? = null): ByteArray?
}

/** Convenience string helpers — the common case, without every caller re-encoding. */
suspend fun SecureStore.putString(alias: String, value: String) = put(alias, value.toByteArray(Charsets.UTF_8))

suspend fun SecureStore.getString(alias: String): String? = get(alias)?.toString(Charsets.UTF_8)
