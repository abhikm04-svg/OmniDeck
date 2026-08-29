package com.omnideck.finance.data

import com.google.common.truth.Truth.assertThat
import com.omnideck.finance.InMemorySpendStore
import com.omnideck.finance.spend
import com.omnideck.sdk.PurgeScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Finance's persistence.
 *
 * The store is in memory but the *encoding* is real — the repository serialises to
 * the same string production writes — so the case this exists for, an unparseable
 * payload left by an older build, is exercised rather than mocked away.
 */
class SpendRepositoryTest {

    @Test
    fun `records survive a round trip through the store`() = runTest {
        val repository = SpendRepository(InMemorySpendStore())

        repository.add(spend("a", 250))

        assertThat(repository.spends.first().map(Spend::id)).containsExactly("a")
    }

    @Test
    fun `the newest record comes first, because that is the one just entered`() = runTest {
        val repository = SpendRepository(InMemorySpendStore())
        repository.add(spend("old", 100, recordedAtMs = 1))
        repository.add(spend("new", 100, recordedAtMs = 99))

        assertThat(repository.spends.first().map(Spend::id)).containsExactly("new", "old").inOrder()
    }

    @Test
    fun `every field makes it through the encoding intact`() = runTest {
        val repository = SpendRepository(InMemorySpendStore())
        val original = spend("a", 1999, SpendCategory.LEISURE, recordedAtMs = 1_700_000_000_000)

        repository.add(original)

        assertThat(repository.spends.first().single()).isEqualTo(original)
    }

    @Test
    fun `removing takes out one record and leaves the rest`() = runTest {
        val repository = SpendRepository(InMemorySpendStore())
        repository.add(spend("a", 100))
        repository.add(spend("b", 200))

        repository.remove("a")

        assertThat(repository.spends.first().map(Spend::id)).containsExactly("b")
    }

    @Test
    fun `a full purge erases the records`() = runTest {
        val repository = SpendRepository(InMemorySpendStore())
        repository.add(spend("a", 100))

        repository.wipe(PurgeScope.ALL)

        assertThat(repository.spends.first()).isEmpty()
    }

    @Test
    fun `a cache purge keeps them, because they are records and not a cache`() = runTest {
        // "Free up space" must not silently delete a month of someone's spending.
        val repository = SpendRepository(InMemorySpendStore())
        repository.add(spend("a", 100))

        repository.wipe(PurgeScope.CACHE)

        assertThat(repository.spends.first()).hasSize(1)
    }

    @Test
    fun `an unreadable payload reads as empty rather than crashing the module`() = runTest {
        // The alternative is a module that will not start for anyone who used an
        // older build, with no way out but clearing their data.
        val repository = SpendRepository(InMemorySpendStore(initial = "not json"))

        assertThat(repository.spends.first()).isEmpty()
    }

    @Test
    fun `a write over an unreadable payload replaces it instead of compounding it`() = runTest {
        val store = InMemorySpendStore(initial = "not json")
        val repository = SpendRepository(store)

        repository.add(spend("a", 100))

        assertThat(repository.spends.first().map(Spend::id)).containsExactly("a")
    }
}
