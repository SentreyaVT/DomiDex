package com.dominik.cardvault
import com.dominik.cardvault.model.*
import org.junit.Assert.*
import org.junit.Test
class PortfolioSummaryTest {
    private val portfolio = PortfolioSummary(
        cards = listOf(card(1, 38.4), card(2, 7.85), card(1, 182.0), card(1, 31.6)),
        history = listOf(PortfolioPoint("Mär", 232.0), PortfolioPoint("Aug", 267.7)),
    )
    @Test fun totalsIncludeQuantities() { assertEquals(267.70, portfolio.totalValue, 0.001); assertEquals(5, portfolio.totalCards) }
    @Test fun historyProducesPositiveChange() { assertTrue(portfolio.changePercent > 0.0) }
    @Test fun unknownPricesAreReportedSeparatelyFromTheNumericTotal() {
        val mixed = PortfolioSummary(
            cards = listOf(card(2, 12.5), card(3, 0.0).copy(priceUpdatedAtEpochMillis = null)),
            history = emptyList(),
        )
        assertEquals(2, mixed.pricedCards)
        assertEquals(3, mixed.unpricedCards)
        assertTrue(mixed.hasPricedCards)
        assertEquals(25.0, mixed.totalValue, .001)
    }

    private fun card(quantity: Int, value: Double) = CollectionCard(1, "id-$value", "Karte", "Set", "1", CardLanguage.GERMAN, "Normal", "Standard", "Sehr gut", quantity, value, 0.0, 1, "", null, null, 0xFF000000)
}
