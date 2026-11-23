package com.dominik.cardvault.model

import org.junit.Assert.*
import org.junit.Test

class CardPriceSelectionTest {
    private val card = CollectionCard(
        1L, "source-me03-090", "Bauz", "Optimale Ordnung", "090/088", CardLanguage.GERMAN,
        "Holo", "Holo", "Near Mint", 2, 0.0, 0.0, null, "", null, null, 0L,
        externalProductId = 877507L, sourceSetId = "me03", sourceCardId = "me03-090",
    )
    private val selection = CardPriceSelection(
        "me03", "me03-090", "090/088", "Holo", "Holo", CardLanguage.GERMAN,
        877507L, CardPriceMetric.NORMAL_AVG_SELL, 532L, "EUR", 1_000L, 2_000L,
    )

    @Test fun noAutomaticSelectionAndSnapshotNeverBecomesATrend() {
        assertNull(card.effectiveValueEur)
        val selected = card.copy(priceSelection = selection)
        assertEquals(5.32, selected.effectiveValueEur!!, .0001)
        assertEquals(0.0, selected.currentValueEur, .0)
        assertNull(selected.priceUpdatedAtEpochMillis)
        assertNull(selected.trendValueEur)
        assertTrue(selected.usesPriceSelection)
        assertTrue(selected.valuationLabel.startsWith("Gewählter Vergleichswert"))
        val summary = PortfolioSummary(listOf(selected), emptyList())
        assertEquals(10.64, summary.totalValue, .0001)
        assertEquals(2, summary.alternativePricedCards)
        assertEquals(0, summary.trendPricedCards)
        assertEquals(0, summary.unpricedCards)
    }

    @Test fun realPositiveTrendAlwaysWinsAndInvalidNumbersNeverWin() {
        val selected = card.copy(priceSelection = selection, priceUpdatedAtEpochMillis = 3_000L)
        val priced = selected.copy(currentValueEur = 7.5)
        assertEquals(7.5, priced.effectiveValueEur!!, .0)
        assertFalse(priced.usesPriceSelection)
        assertEquals("Cardmarket-Trendpreis", priced.valuationLabel)
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertEquals(5.32, selected.copy(currentValueEur = invalid).effectiveValueEur!!, .0001)
        }
    }

    @Test fun priceStatusDistinguishesVerifiedStaleMissingAndConflictingEvidence() {
        val now = 10L * 24L * 60L * 60L * 1_000L
        assertEquals(CardPriceStatus.VERIFIED, card.copy(currentValueEur = 7.5, priceUpdatedAtEpochMillis = now - 60_000L).priceStatus(now))
        assertEquals(CardPriceStatus.STALE, card.copy(currentValueEur = 7.5, priceUpdatedAtEpochMillis = now - 8L * 24L * 60L * 60L * 1_000L).priceStatus(now))
        assertEquals(CardPriceStatus.NO_TREND_PRICE, card.priceStatus(now))
        assertEquals(CardPriceStatus.SOURCE_MISSING, card.copy(externalProductId = null).priceStatus(now))
        assertEquals(
            CardPriceStatus.CONFLICTING_MAPPING,
            card.copy(sourceCardId = "sv03-062", externalProductId = 781858L).priceStatus(now),
        )
    }

    @Test fun allIdentityDimensionsMustMatchIncludingPrintedDenominator() {
        assertTrue(selection.copy(collectorNumber = "90/88").matches(card))
        listOf(
            card.copy(sourceSetId = "other"), card.copy(sourceCardId = "me03-091"),
            card.copy(number = "090/100"), card.copy(number = "090"),
            card.copy(finish = "Reverse Holo"), card.copy(variant = "Stamped"),
            card.copy(language = CardLanguage.ENGLISH), card.copy(externalProductId = 877508L),
            card.copy(sourceSetId = null), card.copy(sourceCardId = null),
        ).forEach { wrong ->
            assertFalse(wrong.toString(), selection.matches(wrong))
            assertNull(wrong.copy(priceSelection = selection).effectiveValueEur)
        }
    }

    @Test fun quarantineAndWrongMetricFamilyStayUnpriced() {
        val quarantined = selection.copy(sourceSetId = "sv03", sourceCardId = "sv03-062", productId = 781858L)
        assertFalse(quarantined.isValid())
        assertFalse(selection.copy(metric = CardPriceMetric.REVERSE_HOLO_LOW).isValid())
        assertFalse(selection.copy(amountMinor = 0L).isValid())
        assertFalse(selection.copy(amountMinor = Long.MAX_VALUE).isValid())
        assertFalse(selection.copy(currency = "USD").isValid())
    }

    @Test fun reverseCandidatesNeverBorrowNormalMetricsOrNormalTrend() {
        val normal = CardPriceAlternative(CardPriceMetric.NORMAL_AVG_SELL, 877507L, 532L, "EUR", 1_000L)
        val reverse = normal.copy(metric = CardPriceMetric.REVERSE_HOLO_LOW, amountMinor = 700L)
        val result = CatalogSearchResult(877507L, "Bauz", "6443", 8.0, "EUR",
            priceAlternatives = listOf(normal, reverse))
        assertTrue(result.priceAlternativesFor("Holo").isEmpty())
        assertEquals(listOf(reverse), result.priceAlternativesFor("Reverse Holo"))
        assertTrue(result.copy(holoTrendPriceEur = 9.0).priceAlternativesFor("Reverse Holo").isEmpty())
    }
}
