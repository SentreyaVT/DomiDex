package com.dominik.cardvault.ui

import com.dominik.cardvault.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioValuationDescriptionTest {
    private val card = CollectionCard(
        entryId = 1, id = "me03-090", name = "Testkarte", set = "Testset", number = "090/088",
        language = CardLanguage.GERMAN, finish = "Holo", variant = "Standard", condition = "Near Mint",
        quantity = 2, currentValueEur = 0.0, changePercent = 0.0, priceUpdatedAtEpochMillis = null,
        notes = "", acquisitionPriceEur = null, acquisitionDateEpochDay = null, accent = 0,
        sourceSetId = "me03", sourceCardId = "me03-090", externalProductId = 42,
    )
    private val saved = CardPriceAlternative(CardPriceMetric.NORMAL_AVG_SELL, 42, 532, "EUR", 1_000)
        .toSelection(card, 2_000)

    @Test fun allComparisonsAreNotDescribedAsAllTrendPrices() {
        assertDescription("Bewertet: 2 von 2 Karten · Trendpreise: 0 · gewählte Vergleichswerte: 2",
            card.copy(priceSelection = saved))
    }

    @Test fun mixedFullyValuedPortfolioCountsQuantitiesByTheirRealSource() {
        assertDescription("Bewertet: 5 von 5 Karten · Trendpreise: 2 · gewählte Vergleichswerte: 3",
            card.copy(currentValueEur = 8.0, priceUpdatedAtEpochMillis = 3_000),
            card.copy(quantity = 3, priceSelection = saved))
    }

    @Test fun missingPricesRemainSeparateFromChosenComparisons() {
        assertDescription("Bewertet: 2 von 5 Karten · Trendpreise: 0 · gewählte Vergleichswerte: 2",
            card.copy(priceSelection = saved), card.copy(quantity = 3))
    }

    @Test fun laterTrendWinsOverSavedComparisonInDescriptionToo() {
        assertDescription("Cardmarket-Trendpreis für alle 2 Karten verfügbar",
            card.copy(currentValueEur = 8.0, priceUpdatedAtEpochMillis = 3_000, priceSelection = saved))
    }

    @Test fun invalidIdentityCannotCountAsAChosenComparison() {
        assertDescription("Bewertet: 0 von 2 Karten · Preis nicht verfügbar",
            card.copy(number = "091/088", priceSelection = saved))
    }

    @Test fun emptyPortfolioDoesNotClaimUniversalTrendCoverage() {
        assertEquals("Bewertet: 0 von 0 Karten · Preis nicht verfügbar", portfolioValuationDescription(PortfolioSummary.Empty))
    }

    private fun assertDescription(expected: String, vararg cards: CollectionCard) {
        assertEquals(expected, portfolioValuationDescription(PortfolioSummary(cards.toList(), emptyList())))
    }
}
