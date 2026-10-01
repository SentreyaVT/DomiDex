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
        assertEquals(product.holoTrendPriceEur, selected.trendPriceEur)
    }

    @Test fun cardmarketCatalogueLabelCannotCreateAnExtraPrinting() {
        val source = card.copy(printVariants = listOf(PokemonPrintVariant("holo", "Holo", "Standard", 42L)))
        val selected = catalogResultForVisualCard(source, product.copy(variant = "Cardmarket-Produkt"))
        assertEquals("Holo", selected.finish)
        assertEquals("Standard", selected.variant)
        assertEquals(source.id, selected.sourceCardId)
        assertEquals(source.set.id, selected.sourceSetId)
    }

    @Test fun exactDeclaredProductSelectsItsSourcePrintingNotTheFirstSlot() {
        val source = card.copy(printVariants = listOf(
            PokemonPrintVariant("normal", "Normal", "Standard", 21L),
            PokemonPrintVariant("holo", "Holo", "Standard", 42L),
        ))
        val selected = catalogResultForVisualCard(source, product.copy(variant = "Cardmarket-Produkt"))
        assertEquals("Holo", selected.finish)
        assertEquals("Standard", selected.variant)
        assertEquals(42L, selected.productId)
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

    @Test fun changedOrRestoredDraftIdentityCannotReuseAnEarlierPriceLink() {
        val selected = catalogResultForVisualCard(card, product)
        assertTrue(selected.matchesManualCatalogSelection(card.name, set.name, "025/100"))
        assertFalse(selected.matchesManualCatalogSelection("Andere Karte", set.name, "025"))
        assertFalse(selected.matchesManualCatalogSelection(card.name, "Anderes Set", "025"))
        assertFalse(selected.matchesManualCatalogSelection(card.name, set.name, "026"))
    }

    @Test fun editedFinishOrSpecialVariantCannotReuseTheSelectedPrintingProduct() {
        val selected = product.copy(finish = "Holo", variant = "Standard")
        assertTrue(selected.matchesManualPrinting("holo", " standard "))
        assertFalse(selected.matchesManualPrinting("Normal", "Standard"))
        assertFalse(selected.matchesManualPrinting("Reverse Holo", "Standard"))
        assertFalse(selected.matchesManualPrinting("Holo", "GameStop"))
        assertFalse(selected.copy(finish = null).matchesManualPrinting("Holo", "Standard"))
        assertFalse(selected.copy(variant = null).matchesManualPrinting("Holo", "Standard"))
    }
}
