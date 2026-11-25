package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderOverviewPolicyTest {
    private val setComplete = BinderOverviewRecord("set-a", "Alpha", BinderOverviewKind.SET, 10, 10, lastOpenedAtEpochMillis = 2)
    private val setSparse = BinderOverviewRecord("set-b", "Beta", BinderOverviewKind.SET, 2, 10, lastOpenedAtEpochMillis = 3)
    private val privateLarge = BinderOverviewRecord("private-c", "Showcase", BinderOverviewKind.PRIVATE, 20, null, lastOpenedAtEpochMillis = 1)

    @Test fun `default puts binders with most cards first`() {
        val result = orderBinderOverview(listOf(setSparse, setComplete, privateLarge), BinderSortOption.MOST_CARDS, BinderFilterOption.ALL)
        assertEquals(listOf("private-c", "set-a", "set-b"), result.map { it.id })
    }

    @Test fun `default sort uses physical count while progress uses filled set slots`() {
        val duplicateHeavy = BinderOverviewRecord("duplicates", "Duplicates", BinderOverviewKind.SET, ownedCards = 25, totalSlots = 100, completedSlots = 5)
        val varied = BinderOverviewRecord("varied", "Varied", BinderOverviewKind.SET, ownedCards = 12, totalSlots = 20, completedSlots = 12)
        assertEquals(listOf("duplicates", "varied"), orderBinderOverview(listOf(varied, duplicateHeavy), BinderSortOption.MOST_CARDS, BinderFilterOption.ALL).map { it.id })
        assertEquals(listOf("varied", "duplicates"), orderBinderOverview(listOf(duplicateHeavy, varied), BinderSortOption.HIGHEST_PROGRESS, BinderFilterOption.ALL).map { it.id })
    }

    @Test fun `value sort uses known market value and never converts unpriced cards into value`() {
        val lowValue = BinderOverviewRecord("low", "Low", BinderOverviewKind.SET, 99, 120, marketValueEur = 3.50)
        val highValue = BinderOverviewRecord("high", "High", BinderOverviewKind.PRIVATE, 1, marketValueEur = 42.75)
        val unknown = BinderOverviewRecord("unknown", "Unknown", BinderOverviewKind.SET, 500, 600)
        assertEquals(
            listOf("high", "low", "unknown"),
            orderBinderOverview(listOf(lowValue, unknown, highValue), BinderSortOption.HIGHEST_VALUE, BinderFilterOption.ALL).map { it.id },
        )
    }

    @Test fun `progress sorts keep private binders after measurable set progress`() {
        val high = orderBinderOverview(listOf(privateLarge, setSparse, setComplete), BinderSortOption.HIGHEST_PROGRESS, BinderFilterOption.ALL)
        val low = orderBinderOverview(listOf(privateLarge, setComplete, setSparse), BinderSortOption.LOWEST_PROGRESS, BinderFilterOption.ALL)
        assertEquals(listOf("set-a", "set-b", "private-c"), high.map { it.id })
        assertEquals(listOf("set-b", "set-a", "private-c"), low.map { it.id })
        assertNull(privateLarge.progress)
    }

    @Test fun `complete and incomplete filters never invent private progress`() {
        val all = listOf(privateLarge, setSparse, setComplete)
        assertEquals(listOf("set-a"), orderBinderOverview(all, BinderSortOption.MOST_CARDS, BinderFilterOption.COMPLETE).map { it.id })
        assertEquals(listOf("set-b"), orderBinderOverview(all, BinderSortOption.MOST_CARDS, BinderFilterOption.INCOMPLETE).map { it.id })
        assertTrue(setComplete.isComplete)
        assertFalse(privateLarge.isComplete)
    }

    @Test fun `kind and last opened sorting are stable`() {
        val all = listOf(setComplete, privateLarge, setSparse)
        assertEquals(listOf("private-c"), orderBinderOverview(all, BinderSortOption.A_TO_Z, BinderFilterOption.PRIVATE_BINDERS).map { it.id })
        assertEquals(listOf("set-b", "set-a", "private-c"), orderBinderOverview(all, BinderSortOption.LAST_OPENED, BinderFilterOption.ALL).map { it.id })
    }

    @Test fun `last-added release and missing sorts use their own source fields`() {
        val older = BinderOverviewRecord("old", "Alt", BinderOverviewKind.SET, ownedCards = 4, totalSlots = 10, completedSlots = 4, lastAddedAtEpochMillis = 10, releaseDate = "2024-01-01")
        val newer = BinderOverviewRecord("new", "Neu", BinderOverviewKind.SET, ownedCards = 7, totalSlots = 10, completedSlots = 7, lastAddedAtEpochMillis = 20, releaseDate = "2025-01-01")
        val unknown = BinderOverviewRecord("unknown", "Legacy", BinderOverviewKind.SET, ownedCards = 1, totalSlots = 10, completedSlots = 1)
        val all = listOf(older, unknown, newer)

        assertEquals(listOf("new", "old", "unknown"), orderBinderOverview(all, BinderSortOption.LAST_ADDED, BinderFilterOption.ALL).map { it.id })
        assertEquals(listOf("new", "old", "unknown"), orderBinderOverview(all, BinderSortOption.NEWEST_SET, BinderFilterOption.ALL).map { it.id })
        assertEquals(listOf("old", "new", "unknown"), orderBinderOverview(all, BinderSortOption.OLDEST_SET, BinderFilterOption.ALL).map { it.id })
        assertEquals(listOf("unknown", "old", "new"), orderBinderOverview(all, BinderSortOption.MOST_MISSING, BinderFilterOption.ALL).map { it.id })
    }

    @Test fun `unknown slot counts are neither complete nor incomplete and have no percentage`() {
        val unknown = BinderOverviewRecord("unknown", "Unloaded", BinderOverviewKind.SET, 500)
        val all = listOf(unknown, setComplete, setSparse)
        assertNull(unknown.progress)
        assertFalse(unknown.isComplete)
        assertFalse(unknown.isIncomplete)
        assertEquals(listOf("set-a"), orderBinderOverview(all, BinderSortOption.MOST_CARDS, BinderFilterOption.COMPLETE).map { it.id })
        assertEquals(listOf("set-b"), orderBinderOverview(all, BinderSortOption.MOST_CARDS, BinderFilterOption.INCOMPLETE).map { it.id })
        assertEquals("set-b", orderBinderOverview(all, BinderSortOption.INCOMPLETE_FIRST, BinderFilterOption.ALL).first().id)
        assertEquals(3, orderBinderOverview(all, BinderSortOption.MOST_CARDS, BinderFilterOption.ALL).size)
    }

    @Test fun `private binder tab contains only private binders`() {
        val all = listOf(setComplete, privateLarge, setSparse)

        assertEquals(
            listOf("private-c"),
            binderOverviewRecordsForTab(all, BinderOverviewTab.PRIVATE_BINDERS).map { it.id },
        )
        assertEquals(
            listOf("set-a", "set-b"),
            binderOverviewRecordsForTab(all, BinderOverviewTab.SET_BINDERS).map { it.id },
        )
    }

    @Test fun `unknown stored values keep required defaults`() {
        assertEquals(BinderSortOption.MOST_CARDS, BinderSortOption.fromStorage("future-value"))
        assertEquals(BinderFilterOption.ALL, BinderFilterOption.fromStorage(null))
    }

    @Test fun `all persisted choices round trip through stable storage values`() {
        BinderSortOption.entries.forEach { option ->
            assertEquals(option, BinderSortOption.fromStorage(option.storageValue))
        }
        BinderFilterOption.entries.forEach { option ->
            assertEquals(option, BinderFilterOption.fromStorage(option.storageValue))
        }
        BinderOverviewTab.entries.forEach { tab ->
            assertEquals(tab, BinderOverviewTab.fromStorage(tab.storageValue))
        }
    }

    @Test fun `completed slots count variants but not duplicate copies`() {
        fun card(entryId: Long, finish: String, quantity: Int = 1) = CollectionCard(
            entryId = entryId,
            id = "card-$entryId",
            name = "Testkarte",
            set = "Testset",
            number = "001/100",
            language = CardLanguage.GERMAN,
            finish = finish,
            variant = "Standard",
            condition = "Near Mint",
            quantity = quantity,
            currentValueEur = 1.0,
            changePercent = 0.0,
            priceUpdatedAtEpochMillis = 1L,
            notes = "",
            acquisitionPriceEur = null,
            acquisitionDateEpochDay = null,
            accent = 0L,
            sourceSetId = "set-a",
            sourceCardId = "set-a-001",
        )

        assertEquals(
            2,
            completedBinderSlotCount(
                listOf(
                    card(entryId = 1L, finish = "Normal", quantity = 4),
                    card(entryId = 2L, finish = "Normal"),
                    card(entryId = 3L, finish = "Reverse Holo"),
                ),
            ),
        )
    }

    @Test fun `classic historic denominators keep equal numerators as distinct cards`() {
        fun classic(entryId: Long, number: String) = CollectionCard(
            entryId = entryId,
            id = "classic-$entryId",
            name = "Klassische Karte",
            set = "30 Jahre: Klassische Sammlung",
            number = number,
            language = CardLanguage.GERMAN,
            finish = "Normal",
            variant = "Standard",
            condition = "Near Mint",
            quantity = 1,
            currentValueEur = 1.0,
            changePercent = 0.0,
            priceUpdatedAtEpochMillis = 1L,
            notes = "",
            acquisitionPriceEur = null,
            acquisitionDateEpochDay = null,
            accent = 0L,
        )

        assertEquals(2, completedBinderSlotCount(listOf(classic(1, "11/113"), classic(2, "11/101"))))
        assertEquals(3, completedBinderSlotCount(listOf(
            classic(1, "106/106"), classic(2, "106/160"), classic(3, "106/105"),
        )))
    }

    @Test fun `ordinary legacy number with and without denominator remains one slot`() {
        fun ordinary(entryId: Long, number: String) = CollectionCard(
            entryId = entryId,
            id = "ordinary-$entryId",
            name = "Normale Karte",
            set = "Normales Set",
            number = number,
            language = CardLanguage.GERMAN,
            finish = "Normal",
            variant = "Standard",
            condition = "Near Mint",
            quantity = 1,
            currentValueEur = 1.0,
            changePercent = 0.0,
            priceUpdatedAtEpochMillis = 1L,
            notes = "",
            acquisitionPriceEur = null,
            acquisitionDateEpochDay = null,
            accent = 0L,
        )

        assertEquals(1, completedBinderSlotCount(listOf(ordinary(1, "001"), ordinary(2, "001/100"))))
    }
}
