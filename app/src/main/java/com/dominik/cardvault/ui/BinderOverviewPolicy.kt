package com.dominik.cardvault.ui

internal enum class BinderOverviewKind { SET, PRIVATE }

internal enum class BinderOverviewTab(val storageValue: String) {
    SET_BINDERS("set"),
    PRIVATE_BINDERS("private");

    companion object {
        fun fromStorage(value: String?): BinderOverviewTab =
            entries.firstOrNull { it.storageValue == value } ?: SET_BINDERS
    }
}

internal enum class BinderSortOption(val storageValue: String, val label: String) {
    MOST_CARDS("most_cards", "Meiste Karten"),
    FEWEST_CARDS("fewest_cards", "Wenigste Karten"),
    HIGHEST_VALUE("highest_value", "Höchster Sammlungswert"),
    HIGHEST_PROGRESS("highest_progress", "Höchster Fortschritt"),
    LOWEST_PROGRESS("lowest_progress", "Niedrigster Fortschritt"),
    MOST_MISSING("most_missing", "Meiste fehlende Karten"),
    FEWEST_MISSING("fewest_missing", "Wenigste fehlende Karten"),
    A_TO_Z("a_to_z", "A–Z"),
    Z_TO_A("z_to_a", "Z–A"),
    LAST_OPENED("last_opened", "Zuletzt geöffnet"),
    LAST_ADDED("last_added", "Zuletzt hinzugefügt"),
    NEWEST_SET("newest_set", "Neueste Sets zuerst"),
    OLDEST_SET("oldest_set", "Älteste Sets zuerst"),
    COMPLETE_FIRST("complete_first", "Vollständig zuerst"),
    INCOMPLETE_FIRST("incomplete_first", "Unvollständig zuerst");

    companion object {
        fun fromStorage(value: String?): BinderSortOption =
            entries.firstOrNull { it.storageValue == value } ?: MOST_CARDS
    }
}

internal enum class BinderFilterOption(val storageValue: String, val label: String) {
    ALL("all", "Alle"),
    COMPLETE("complete", "Vollständig"),
    INCOMPLETE("incomplete", "Unvollständig"),
    SET_BINDERS("set", "Set-Binder"),
    PRIVATE_BINDERS("private", "Private Binder");

    companion object {
        fun fromStorage(value: String?): BinderFilterOption =
            entries.firstOrNull { it.storageValue == value } ?: ALL
    }
}

internal data class BinderOverviewRecord(
    val id: String,
    val name: String,
    val kind: BinderOverviewKind,
    val ownedCards: Int,
    val totalSlots: Int? = null,
    val marketValueEur: Double = 0.0,
    val lastOpenedAtEpochMillis: Long? = null,
    /** Actual newest ownership/placement time; null is retained for legacy data without evidence. */
    val lastAddedAtEpochMillis: Long? = null,
    /** Source-declared YYYY-MM-DD (or null when the catalogue provides no date). */
    val releaseDate: String? = null,
    /** Filled canonical set slots; physical card count stays independent for the default sort. */
    val completedSlots: Int? = null,
) {
    val progress: Double?
        get() = totalSlots?.takeIf { it > 0 }?.let { (completedSlots ?: ownedCards).coerceAtMost(it).toDouble() / it }

    val isComplete: Boolean
        get() = kind == BinderOverviewKind.SET && totalSlots != null && totalSlots > 0 && (completedSlots ?: ownedCards) >= totalSlots

    val isIncomplete: Boolean
        get() = kind == BinderOverviewKind.SET && totalSlots != null && totalSlots > 0 && !isComplete

    val missingSlots: Int?
        get() = totalSlots?.takeIf { it > 0 }?.let { (it - (completedSlots ?: ownedCards)).coerceAtLeast(0) }
}

internal fun binderOverviewRecordsForTab(
    records: List<BinderOverviewRecord>,
    tab: BinderOverviewTab,
): List<BinderOverviewRecord> = records.filter { record ->
    when (tab) {
        BinderOverviewTab.SET_BINDERS -> record.kind == BinderOverviewKind.SET
        BinderOverviewTab.PRIVATE_BINDERS -> record.kind == BinderOverviewKind.PRIVATE
    }
}

internal fun orderBinderOverview(
    records: List<BinderOverviewRecord>,
    sort: BinderSortOption,
    filter: BinderFilterOption,
): List<BinderOverviewRecord> {
    val filtered = records.filter { record ->
        when (filter) {
            BinderFilterOption.ALL -> true
            BinderFilterOption.COMPLETE -> record.isComplete
            BinderFilterOption.INCOMPLETE -> record.isIncomplete
            BinderFilterOption.SET_BINDERS -> record.kind == BinderOverviewKind.SET
            BinderFilterOption.PRIVATE_BINDERS -> record.kind == BinderOverviewKind.PRIVATE
        }
    }
    val nameThenId = compareBy<BinderOverviewRecord>({ it.name.lowercase() }, { it.id })
    val primary = when (sort) {
        BinderSortOption.MOST_CARDS -> compareByDescending<BinderOverviewRecord> { it.ownedCards }
        BinderSortOption.FEWEST_CARDS -> compareBy<BinderOverviewRecord> { it.ownedCards }
        BinderSortOption.HIGHEST_VALUE -> compareByDescending<BinderOverviewRecord> { it.marketValueEur }
        BinderSortOption.HIGHEST_PROGRESS -> compareByDescending<BinderOverviewRecord> { it.progress ?: -1.0 }
        BinderSortOption.LOWEST_PROGRESS -> compareBy<BinderOverviewRecord> { it.progress ?: Double.MAX_VALUE }
        BinderSortOption.MOST_MISSING -> compareByDescending<BinderOverviewRecord> { it.missingSlots ?: -1 }
        BinderSortOption.FEWEST_MISSING -> compareBy<BinderOverviewRecord> { it.missingSlots ?: Int.MAX_VALUE }
        BinderSortOption.A_TO_Z -> nameThenId
        BinderSortOption.Z_TO_A -> compareByDescending<BinderOverviewRecord> { it.name.lowercase() }.thenByDescending { it.id }
        BinderSortOption.LAST_OPENED -> compareByDescending<BinderOverviewRecord> { it.lastOpenedAtEpochMillis ?: Long.MIN_VALUE }
        BinderSortOption.LAST_ADDED -> compareByDescending<BinderOverviewRecord> { it.lastAddedAtEpochMillis ?: Long.MIN_VALUE }
        BinderSortOption.NEWEST_SET -> compareByDescending<BinderOverviewRecord> { it.releaseDate ?: "" }
        BinderSortOption.OLDEST_SET -> compareBy<BinderOverviewRecord> { it.releaseDate ?: "\uffff" }
        BinderSortOption.COMPLETE_FIRST -> compareByDescending<BinderOverviewRecord> { it.isComplete }
            .thenByDescending { it.ownedCards }
        BinderSortOption.INCOMPLETE_FIRST -> compareByDescending<BinderOverviewRecord> { it.isIncomplete }
            .thenByDescending { it.ownedCards }
    }
    return if (sort == BinderSortOption.A_TO_Z || sort == BinderSortOption.Z_TO_A) filtered.sortedWith(primary)
    else filtered.sortedWith(primary.then(nameThenId))
}
