package com.dominik.cardvault.ui

/**
 * Source policy shared by the UI and later server importers. It deliberately keeps
 * active listings separate from verifiable sold/guide prices: an asking price is
 * useful comparison context, but not a portfolio valuation.
 */
internal enum class CollectibleKind { SINGLE_CARD, ELITE_TRAINER_BOX, BOOSTER_PACK, BOOSTER_BOX, SEALED_OTHER }
internal enum class MarketEvidence { PRICE_GUIDE, SOLD_COMPARABLES, ACTIVE_LISTINGS, MANUAL }
internal data class MarketSourceDescriptor(val label: String, val evidence: MarketEvidence, val contributesToValue: Boolean)

internal object MarketPricePolicy {
    val cardmarket = MarketSourceDescriptor("Cardmarket-Trendpreis", MarketEvidence.PRICE_GUIDE, true)
    fun valuationLabel(kind: CollectibleKind) = when (kind) {
        CollectibleKind.SINGLE_CARD -> "Trendpreis nur bei exakter Karten-Zuordnung"
        else -> "Sealed nur mit eigener Produkt-ID und eigener Preisquelle"
    }
}
