package com.dominik.cardvault
import com.dominik.cardvault.model.SampleCollectionRepository
import org.junit.Assert.*
import org.junit.Test
class PortfolioSummaryTest {
    private val portfolio = SampleCollectionRepository.portfolio()
    @Test fun totalsIncludeQuantities() { assertEquals(267.70, portfolio.totalValue, 0.001); assertEquals(5, portfolio.totalCards) }
    @Test fun historyProducesPositiveChange() { assertTrue(portfolio.changePercent > 0.0) }
}
