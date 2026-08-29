package com.omnideck.finance.data

import kotlinx.serialization.Serializable

/** One recorded outgoing. Amounts are minor units — never a `Double`, ever. */
@Serializable
data class Spend(
    val id: String,
    val description: String,
    val minorUnits: Long,
    val category: SpendCategory,
    val recordedAtMs: Long,
)

/**
 * Deliberately a small fixed set rather than free text.
 *
 * The premium feature is a breakdown by category, and a breakdown over strings a
 * user typed is a list of typos. Fixing the set here is what makes the paid feature
 * worth anything.
 */
@Serializable
enum class SpendCategory {
    ESSENTIALS,
    TRANSPORT,
    FOOD,
    LEISURE,
    OTHER,
    ;

    val label: String get() = name.lowercase().replaceFirstChar(Char::titlecase)
}

/** A category's share of a period's spending. The premium view is a list of these. */
data class CategoryTotal(val category: SpendCategory, val minorUnits: Long, val share: Float)
