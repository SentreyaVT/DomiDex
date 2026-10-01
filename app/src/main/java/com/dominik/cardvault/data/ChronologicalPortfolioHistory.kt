package com.dominik.cardvault.data

import com.dominik.cardvault.data.catalog.isQuarantinedCardmarketIdentity
import com.dominik.cardvault.data.local.CollectionProjection
import com.dominik.cardvault.data.local.OwnedPriceHistoryProjection
import com.dominik.cardvault.model.PortfolioPoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Reconstructs the existing historical valuation, not today's selected alternative price.
 * The sole caller supplies the DAO's globally ascending timestamp order. Equal-time input
 * order is retained: the last event for a product at that timestamp replaces earlier ones.
 *
 * Quantities, products and the running total deliberately use wrapping Long arithmetic,
 * matching the previous per-row Long sum. Conversion to Double happens only at emission.
 */
internal fun buildChronologicalPortfolioHistory(
    rows: List<CollectionProjection>,
    prices: List<OwnedPriceHistoryProjection>,
): List<PortfolioPoint> {
    val holdings = HashMap<String, HistoryHolding>()
    for (row in rows) {
        // Quarantine is row-specific: a sibling row may legitimately use the same product.
        if (isQuarantinedCardmarketIdentity(row.sourceCardId ?: row.cardId, row.externalProductId)) continue
        val holding = holdings.getOrPut(row.priceCardId) { HistoryHolding() }
        if (row.finish.contains("reverse", ignoreCase = true)) {
            holding.reverseQuantity += row.quantity.toLong()
        } else {
            holding.normalQuantity += row.quantity.toLong()
        }
    }

    val result = ArrayList<PortfolioPoint>()
    var totalMinor = 0L
    var index = 0
    while (index < prices.size) {
        val timestamp = prices[index].effectiveAtEpochMillis
        do {
            val event = prices[index]
            val holding = holdings[event.cardId]
            if (holding != null) {
                val normal = event.trendValueMinor?.takeIf { it > 0L } ?: 0L
                val reverse = event.holoTrendValueMinor?.takeIf { it > 0L } ?: 0L
                totalMinor += (normal - holding.normalPriceMinor) * holding.normalQuantity
                totalMinor += (reverse - holding.reversePriceMinor) * holding.reverseQuantity
                holding.normalPriceMinor = normal
                holding.reversePriceMinor = reverse
            }
            index++
        } while (index < prices.size && prices[index].effectiveAtEpochMillis == timestamp)

        // Even an unrelated/quarantined/zero-price event contributes its original timestamp.
        result += PortfolioPoint(
            Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate()
                .format(DateTimeFormatter.ofPattern("MMM")),
            totalMinor / 100.0,
        )
    }
    return result
}

private class HistoryHolding {
    var normalQuantity = 0L
    var reverseQuantity = 0L
    var normalPriceMinor = 0L
    var reversePriceMinor = 0L
}
