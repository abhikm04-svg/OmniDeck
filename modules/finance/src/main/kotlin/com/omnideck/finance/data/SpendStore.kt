package com.omnideck.finance.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The one string Finance persists, and the only thing [SpendRepository] needs from
 * the platform.
 *
 * Narrower than `DataStore<Preferences>` on purpose. Everything worth testing in
 * this module's persistence — the encoding, the ordering, the recovery from an
 * unparseable payload — is logic over that string, and depending on the DataStore
 * directly drags a real file into every one of those tests. It also drags a real
 * *filesystem*: DataStore commits by renaming a temp file over the target, which
 * fails outright on Windows once the target exists, so the module's own tests would
 * pass on CI and fail on half the machines that develop it.
 */
interface SpendStore {

    /** Emits on every write. Null before anything has been stored. */
    val raw: Flow<String?>

    suspend fun update(transform: (String?) -> String)

    suspend fun clear()
}

/** The production implementation: a namespaced preferences DataStore from the SDK. */
class PreferencesSpendStore(private val store: DataStore<Preferences>) : SpendStore {

    override val raw: Flow<String?> = store.data.map { it[ENTRIES] }

    override suspend fun update(transform: (String?) -> String) {
        store.edit { prefs -> prefs[ENTRIES] = transform(prefs[ENTRIES]) }
    }

    override suspend fun clear() {
        store.edit { it.remove(ENTRIES) }
    }

    private companion object {
        val ENTRIES = stringPreferencesKey("spends")
    }
}
