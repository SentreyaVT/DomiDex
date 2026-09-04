package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.model.CatalogSearchResult
import org.junit.Assert.*
import org.junit.Test

class ManualBinderCatalogSelectionTest {
    private val set = PokemonSet("sv-test", "Deutsches Testset", "Test", 100, 120, "2026-01-01", null, null)
    private val holo = PokemonPrintVariant("holo", "Holo", "Holo")
    private val reverse = PokemonPrintVariant("reverse", "Reverse Holo", "Reverse Holo")
    private val card = PokemonVisualCard("sv-test-025", "Testkarte", "025", "Rare", "", "", set,
        printVariants = listOf(holo, reverse))
    // Test fixtures only; no market request or persisted price.
    private val product = CatalogSearchResult(42L, "Source name", "market-set", 2.5, "EUR",
        finish = "UNKNOWN", variant = "UNKNOWN", holoTrendPriceEur = 3.5)

    @Test fun unpricedVisualSelectionKeepsItsSourceDeclaredHoloPrinting() {
        val selected = catalogResultForVisualCard(card)
        assertEquals("Holo", selected.finish)
        assertEquals("Holo", selected.variant)
        assertEquals(card.id, selected.sourceCardId)
        assertEquals(set.id, selected.sourceSetId)
        assertEquals(card.number, selected.collectorNumber)
        assertFalse(selected.hasMarketProduct)
        assertNull(selected.trendPriceEur)
    }

    @Test fun priceProductWithoutPrintingDoesNotTurnHoloIntoNormal() {
        val selected = catalogResultForVisualCard(card, product)
        assertEquals("Holo", selected.finish)
        assertEquals("Holo", selected.variant)
        assertEquals(card.id, selected.sourceCardId)
        assertEquals(set.name, selected.visualSetName)
        assertEquals(product.productId, selected.productId)
        assertEquals(product.expansionId, selected.expansionId)
        assertEquals(product.trendPriceEur, selected.trendPriceEur)
        assertEquals(product.holoTrendPriceEur, selected.holoTrendPriceEur)
        assertEquals(product.currency, selected.currency)
    }

    @Test fun explicitReverseSelectionIsNotChangedToBasePrinting() {
        val selected = catalogResultForVisualCard(card, product.copy(finish = "Reverse Holo", variant = "Reverse Holo"))
        assertEquals("Reverse Holo", selected.finish)
        assertEquals("Reverse Holo", selected.variant)
        assertEquals(card.id, selected.sourceCardId)
    }

    @Test fun blankSourceImageKeepsTheAvailableProductImage() {
        val withImage = product.copy(imageUrl = "https://example.test/product.webp")

        assertEquals(withImage.imageUrl, catalogResultForVisualCard(card, withImage).imageUrl)
        assertEquals(withImage.imageUrl, fallbackCardImageUrl(" ", withImage.imageUrl))
        assertNull(fallbackCardImageUrl("", " "))
    }

    @Test fun existingSourceImageIsPreferredToTheProductImage() {
        val withImage = card.copy(largeImageUrl = "https://example.test/source.webp")
        val marketImage = product.copy(imageUrl = "https://example.test/product.webp")

        assertEquals(withImage.largeImageUrl, catalogResultForVisualCard(withImage, marketImage).imageUrl)
        assertEquals(withImage.largeImageUrl, fallbackCardImageUrl(withImage.largeImageUrl, marketImage.imageUrl))
    }

    @Test fun editedSetOrNumberCannotReusePreviousSourceIdentity() {
        val selected = catalogResultForVisualCard(card)
        assertTrue(selected.matchesManualSourceSelection(set.name, "25"))
        assertTrue(selected.matchesManualSourceSelection(set.name, "025/100"))
        assertFalse(selected.matchesManualSourceSelection("Anderes Set", "025"))
        assertFalse(selected.matchesManualSourceSelection(set.name, "026"))
        assertFalse(selected.matchesManualSourceSelection(set.name, ""))
    }
}
