package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.model.CatalogSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardmarketExpansionResolverTest {
    private val set = PokemonSet("test", "Testset", "Test", 3, 3, "", null, null)
    private fun card(id: String, name: String) = PokemonVisualCard(id, name, id, null, "", "", set, marketSearchName = name)
    private fun product(id: Long, name: String, expansion: String) = CatalogSearchResult(id, name, expansion, 1.0, "EUR")

    @Test fun resolvesAnExpansionOnlyWhenSeveralDistinctCardsAgree() {
        val cards = listOf(card("001", "Alpha"), card("002", "Beta"), card("003", "Gamma"))
        val candidates = mapOf(
            "Alpha" to listOf(product(1, "Alpha [Move]", "44")),
            "Beta" to listOf(product(2, "Beta [Move]", "44")),
            "Gamma" to listOf(product(3, "Gamma [Move]", "44")),
        )
        assertEquals("44", resolveCardmarketExpansionId(cards, candidates))
    }

    @Test fun keepsAnAmbiguousExpansionUnlinkedInsteadOfGuessing() {
        val cards = listOf(card("001", "Alpha"), card("002", "Beta"), card("003", "Gamma"))
        val candidates = mapOf(
            "Alpha" to listOf(product(1, "Alpha", "44"), product(2, "Alpha", "45")),
            "Beta" to listOf(product(3, "Beta", "44"), product(4, "Beta", "45")),
            "Gamma" to listOf(product(5, "Gamma", "44"), product(6, "Gamma", "45")),
        )
        assertNull(resolveCardmarketExpansionId(cards, candidates))
    }
}
