package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.model.CatalogSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualCardmarketLinkerTest {
    private val set = PokemonSet(
        id = "source-set",
        name = "Deutsches Set",
        series = "Test",
        printedTotal = 3,
        total = 3,
        releaseDate = "",
        logoUrl = null,
        ptcgoCode = "DST",
    )

    @Test
    fun resolvesCardmarketProductWithoutInventingCollectorNumberOrFinish() {
        val card = visual("source-set-001", "001", "Bisasam")
        val product = product(41, "Bulbasaur [Tackle]", "900", 2.35)

        val resolved = visualCardmarketProducts(
            card = card,
            cardsInSet = listOf(card),
            candidates = listOf(product),
            resolvedExpansionId = "900",
        )

        assertEquals(listOf(41L), resolved.map { it.productId })
        assertEquals("001", resolved.single().collectorNumber)
        assertEquals("UNKNOWN", resolved.single().finish)
        assertEquals(2.35, resolved.single().trendPriceEur!!, 0.0)
    }

    @Test
    fun sameNameReprintUsesStablePrintedOrderInsideVerifiedExpansion() {
        val first = visual("source-set-001", "001", "Bisasam")
        val second = visual("source-set-002", "002", "Bisasam")
        val products = listOf(
            product(20, "Bulbasaur [Move A]", "900", 1.0),
            product(30, "Bulbasaur [Move B]", "900", 3.0),
        )

        assertEquals(20L, visualCardmarketProducts(first, listOf(second, first), products, "900").single().productId)
        assertEquals(30L, visualCardmarketProducts(second, listOf(second, first), products, "900").single().productId)
    }

    @Test
    fun rejectsWrongExpansionAndNeverCreatesAPrice() {
        val card = visual("source-set-001", "001", "Bisasam")
        val result = visualCardmarketProducts(
            card,
            listOf(card),
            listOf(product(41, "Bulbasaur", "other", 99.0)),
            resolvedExpansionId = "900",
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun scannerKeepsCanonicalSourceCardIdWhileFoilsHaveDistinctUiKeys() {
        val card = visual("source-set-001", "001", "Bisasam")
        val normal = scannerCatalogResult(card, PokemonPrintVariant("normal", "Normal", "Standard"), null)
        val reverse = scannerCatalogResult(card, PokemonPrintVariant("reverseholofoil", "Reverse Holo", "Reverse Holo"), null)

        assertEquals("source-set-001", normal.sourceCardId)
        assertEquals(normal.sourceCardId, reverse.sourceCardId)
        assertTrue(normal.scanIdentityKey() != reverse.scanIdentityKey())
    }

    private fun visual(id: String, number: String, germanName: String) = PokemonVisualCard(
        id = id,
        name = germanName,
        number = number,
        rarity = null,
        smallImageUrl = "",
        largeImageUrl = "",
        set = set,
        marketSearchName = "Bulbasaur",
    )

    private fun product(id: Long, name: String, expansion: String, price: Double?) = CatalogSearchResult(
        productId = id,
        name = name,
        expansionId = expansion,
        trendPriceEur = price,
        currency = "EUR",
        collectorNumber = "",
        finish = "UNKNOWN",
    )
}
