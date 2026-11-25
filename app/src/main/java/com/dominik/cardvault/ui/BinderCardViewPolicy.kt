package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CollectionCard
import com.dominik.cardvault.model.PrivateBinderCard
import java.util.Locale

/**
 * View-only binder ordering.  It never changes collection rows or the saved physical
 * placement of private-binder cards.  Missing catalogue slots are represented by
 * [CollectionCard.entryId] == 0 and therefore remain distinguishable from ownership.
 */
internal enum class SetBinderCardSort(val storageValue: String, val label: String) {
    NUMBER_ASC("number_asc", "Kartennummer ↑"),
    NUMBER_DESC("number_desc", "Kartennummer ↓"),
    NAME_ASC("name_asc", "Name A–Z"),
    NAME_DESC("name_desc", "Name Z–A"),
    LAST_ADDED("last_added", "Zuletzt hinzugefügt"),
    VALUE_DESC("value_desc", "Höchster Kartenwert"),
    VALUE_ASC("value_asc", "Niedrigster Kartenwert"),
    VARIANT_ASC("variant_asc", "Variante A–Z"),
    VARIANT_DESC("variant_desc", "Variante Z–A");

    companion object { fun fromStorage(value: String?) = entries.firstOrNull { it.storageValue == value } ?: NUMBER_ASC }
}

internal enum class PrivateBinderCardSort(val storageValue: String, val label: String) {
    SAVED_POSITION("saved_position", "Gespeicherte Anordnung"),
    LAST_ADDED("last_added", "Zuletzt hinzugefügt"),
    LAST_ADDED_ASC("last_added_asc", "Zuerst hinzugefügt"),
    LAST_EDITED("last_edited", "Zuletzt bearbeitet"),
    LAST_EDITED_ASC("last_edited_asc", "Zuerst bearbeitet"),
    NUMBER_ASC("number_asc", "Kartennummer ↑"),
    NUMBER_DESC("number_desc", "Kartennummer ↓"),
    NAME_ASC("name_asc", "Name A–Z"),
    NAME_DESC("name_desc", "Name Z–A"),
    VALUE_DESC("value_desc", "Höchster Kartenwert"),
    VALUE_ASC("value_asc", "Niedrigster Kartenwert");

    companion object { fun fromStorage(value: String?) = entries.firstOrNull { it.storageValue == value } ?: SAVED_POSITION }
}

internal fun isMissingBinderSlot(card: CollectionCard?) = card != null && card.entryId == 0L

private fun collectorKey(value: String): Pair<Int, String> {
    val cleaned = value.substringBefore('/').trim()
    // Match the Web rule: promo prefixes such as TG01 still sort by their
    // printed numeric component, while numbers that are genuinely unknown go last.
    return (Regex("\\d+").find(cleaned)?.value?.toIntOrNull() ?: Int.MAX_VALUE) to cleaned.lowercase(Locale.ROOT)
}

private val collectionTieComparator = compareBy<CollectionCard>(
    { it.name.lowercase(Locale.ROOT) },
    { collectorKey(it.number).first },
    { collectorKey(it.number).second },
    { it.finish.lowercase(Locale.ROOT) },
    { it.variant.lowercase(Locale.ROOT) },
    { it.entryId },
    { it.id },
)

/** Filter is deliberately applied before sorting; missing slots do not leak into an owned-only sort. */
internal fun orderedSetBinderCards(
    slots: List<CollectionCard?>,
    sort: SetBinderCardSort,
    onlyMissing: Boolean,
): List<CollectionCard?> {
    val filtered = if (onlyMissing) slots.filter(::isMissingBinderSlot) else slots
    val cards = filtered.filterNotNull()
    val comparator = when (sort) {
        SetBinderCardSort.NUMBER_ASC -> compareBy<CollectionCard>({ collectorKey(it.number).first }, { collectorKey(it.number).second })
        SetBinderCardSort.NUMBER_DESC -> compareByDescending<CollectionCard> { collectorKey(it.number).first }
            .thenByDescending { collectorKey(it.number).second }
        SetBinderCardSort.NAME_ASC -> compareBy<CollectionCard> { it.name.lowercase(Locale.ROOT) }
        SetBinderCardSort.NAME_DESC -> compareByDescending<CollectionCard> { it.name.lowercase(Locale.ROOT) }
        SetBinderCardSort.LAST_ADDED -> compareByDescending<CollectionCard> { it.createdAtEpochMillis ?: Long.MIN_VALUE }
        SetBinderCardSort.VALUE_DESC -> compareByDescending<CollectionCard> { it.effectiveValueEur ?: Double.NEGATIVE_INFINITY }
        SetBinderCardSort.VALUE_ASC -> compareBy<CollectionCard> { it.effectiveValueEur ?: Double.POSITIVE_INFINITY }
        SetBinderCardSort.VARIANT_ASC -> compareBy<CollectionCard>({ it.finish.lowercase(Locale.ROOT) }, { it.variant.lowercase(Locale.ROOT) })
        SetBinderCardSort.VARIANT_DESC -> compareByDescending<CollectionCard> { it.finish.lowercase(Locale.ROOT) }
            .thenByDescending { it.variant.lowercase(Locale.ROOT) }
    }.then(collectionTieComparator)
    return cards.sortedWith(comparator).map { it as CollectionCard? }
}

internal fun orderedPrivateBinderCards(
    cards: List<PrivateBinderCard>,
    sort: PrivateBinderCardSort,
): List<PrivateBinderCard> {
    val ties = compareBy<PrivateBinderCard>({ it.card.name.lowercase(Locale.ROOT) }, { collectorKey(it.card.number).first }, { it.position }, { it.placementId })
    val primary = when (sort) {
        PrivateBinderCardSort.SAVED_POSITION -> compareBy<PrivateBinderCard> { it.position }
        PrivateBinderCardSort.LAST_ADDED -> compareByDescending<PrivateBinderCard> { it.createdAtEpochMillis ?: Long.MIN_VALUE }
        PrivateBinderCardSort.LAST_ADDED_ASC -> compareBy<PrivateBinderCard> { it.createdAtEpochMillis ?: Long.MAX_VALUE }
        PrivateBinderCardSort.LAST_EDITED -> compareByDescending<PrivateBinderCard> { it.updatedAtEpochMillis ?: Long.MIN_VALUE }
        PrivateBinderCardSort.LAST_EDITED_ASC -> compareBy<PrivateBinderCard> { it.updatedAtEpochMillis ?: Long.MAX_VALUE }
        PrivateBinderCardSort.NUMBER_ASC -> compareBy<PrivateBinderCard>({ collectorKey(it.card.number).first }, { collectorKey(it.card.number).second })
        PrivateBinderCardSort.NUMBER_DESC -> compareByDescending<PrivateBinderCard> { collectorKey(it.card.number).first }.thenByDescending { collectorKey(it.card.number).second }
        PrivateBinderCardSort.NAME_ASC -> compareBy<PrivateBinderCard> { it.card.name.lowercase(Locale.ROOT) }
        PrivateBinderCardSort.NAME_DESC -> compareByDescending<PrivateBinderCard> { it.card.name.lowercase(Locale.ROOT) }
        PrivateBinderCardSort.VALUE_DESC -> compareByDescending<PrivateBinderCard> { it.card.effectiveValueEur ?: Double.NEGATIVE_INFINITY }
        PrivateBinderCardSort.VALUE_ASC -> compareBy<PrivateBinderCard> { it.card.effectiveValueEur ?: Double.POSITIVE_INFINITY }
    }
    return cards.sortedWith(primary.then(ties))
}
