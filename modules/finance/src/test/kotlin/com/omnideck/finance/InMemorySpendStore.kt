package com.omnideck.finance

import com.omnideck.finance.data.SpendStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The store, without a filesystem.
 *
 * It holds the same thing production does — the encoded string — so the repository's
 * serialisation, ordering and recovery-from-garbage all run for real against it.
 * What it removes is the file, which is what makes these tests behave identically on
 * Linux and Windows (see [SpendStore] for why that is not a hypothetical).
 */
class InMemorySpendStore(initial: String? = null) : SpendStore {

    private val state = MutableStateFlow(initial)

    override val raw: Flow<String?> = state

    /** What is actually persisted, for a test that wants to assert on the encoding. */
    val encoded: String? get() = state.value

    override suspend fun update(transform: (String?) -> String) {
        state.value = transform(state.value)
    }

    override suspend fun clear() {
        state.value = null
    }
}
