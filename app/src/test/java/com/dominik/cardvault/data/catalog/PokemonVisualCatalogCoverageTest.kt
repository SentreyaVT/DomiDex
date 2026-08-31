package com.dominik.cardvault.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonVisualCatalogCoverageTest {

    @Test
    fun printedTotalFallbackKeepsEveryMatchingSetBeyondFormerEightSetLimit() {
        val matching = (1..12).map { index ->
            PokemonSet("set$index", "Set $index", "Serie", 100, 120, "2026-01-01", null, "S$index")
        }
        val unrelated = PokemonSet("other", "Anderes Set", "Serie", 99, 110, "2026-01-01", null, "OTH")

        assertEquals(matching, setsMatchingPrintedTotal(matching + unrelated, 100))
    }

    @Test
    fun englishSourceRecordsOnlyFillCardsMissingFromGermanSetPayload() {
        val set = PokemonSet("promo", "Promokarten", "Serie", 2, 3, "2026-01-01", null, "PRM")
        fun card(id: String, name: String, german: Boolean) = PokemonVisualCard(
            id, name, id.takeLast(1), null, "", "", set,
            hasGermanLocalizedName = german,
        )
        val merged = mergeLocalizedSetCards(
            germanCards = listOf(card("promo-1", "Deutscher Name", true)),
            englishCards = listOf(
                card("promo-1", "English Name", false),
                card("promo-2", "English Fallback", false),
            ),
        )

        assertEquals(listOf("promo-1", "promo-2"), merged.map(PokemonVisualCard::id))
        assertEquals("Deutscher Name", merged.first().name)
        assertEquals("English Name", merged.first().marketSearchName)
        assertTrue(merged.first().hasGermanLocalizedName)
        assertFalse(merged.last().hasGermanLocalizedName)
    }
}
