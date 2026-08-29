package com.omnideck.finance.data

import com.omnideck.sdk.PurgeScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Finance's whole persistence layer.
 *
 * A serialised list in a preferences store rather than a Room database, on purpose:
 * Notes already proves the Room path through the SDK, and the second module is only
 * worth building if it exercises a *different* part of the contract. This is the
 * small-data path — no schema, no migrations, no DAO — and it is the one most
 * modules will actually want.
 *
 * The underlying store is handed out by
 * [com.omnideck.sdk.capability.StorageService], so the file lands under
 * `modules/com.omnideck.finance/` and is erased with the module (architecture.md
 * §12.4). Nothing here knows where that is.
 */
class SpendRepository(private val store: SpendStore, private val json: Json = Json { ignoreUnknownKeys = true }) {

    val spends: Flow<List<Spend>> = store.raw.map { raw ->
        raw?.let(::decode).orEmpty().sortedByDescending(Spend::recordedAtMs)
    }

    suspend fun add(spend: Spend) = store.update { raw ->
        json.encodeToString(raw?.let(::decode).orEmpty() + spend)
    }

    suspend fun remove(id: String) = store.update { raw ->
        json.encodeToString(raw?.let(::decode).orEmpty().filterNot { it.id == id })
    }

    /**
     * The module's half of the erasure guarantee.
     *
     * [PurgeScope.CACHE] deliberately keeps the entries: they are the user's own
     * records, not a cache, and a "free up space" sweep that silently deleted a
     * month of someone's spending would be a data-loss bug wearing a maintenance
     * label.
     */
    suspend fun wipe(scope: PurgeScope) {
        if (scope == PurgeScope.CACHE) return
        store.clear()
    }

    /**
     * A payload this module cannot parse is treated as empty rather than fatal.
     *
     * The alternative is a module that refuses to start for everyone who used an
     * older build, with no way out but clearing their data — and since the entries
     * are recoverable by nothing, crashing gains nothing either.
     */
    private fun decode(raw: String): List<Spend> = runCatching {
        json.decodeFromString<List<Spend>>(raw)
    }.getOrDefault(emptyList())
}
