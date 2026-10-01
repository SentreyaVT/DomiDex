package com.dominik.cardvault.model

import com.dominik.cardvault.data.catalog.isQuarantinedCardmarketIdentity
import java.util.Locale

/** Stable wire values shared with Web; these are never presented as trend prices. */
enum class CardPriceMetric(val wireValue: String, val label: String, val reverse: Boolean = false) {
    NORMAL_AVG_SELL("normal_avg_sell", "Cardmarket-Durchschnitt"),
    NORMAL_LOW("normal_low", "Cardmarket-Niedrigpreis"),
    NORMAL_AVG_1D("normal_avg_1d", "Cardmarket Ø 1 Tag"),
    NORMAL_AVG_7D("normal_avg_7d", "Cardmarket Ø 7 Tage"),
    NORMAL_AVG_30D("normal_avg_30d", "Cardmarket Ø 30 Tage"),
    REVERSE_HOLO_AVG_SELL("reverse_holo_avg_sell", "Cardmarket Reverse-Durchschnitt", true),
    REVERSE_HOLO_LOW("reverse_holo_low", "Cardmarket Reverse-Niedrigpreis", true);

    fun matchesFinish(finish: String) = reverse == finish.contains("reverse", ignoreCase = true)
    companion object { fun fromWire(value: String?) = entries.firstOrNull { it.wireValue == value } }
}

data class CardPriceAlternative(
    val metric: CardPriceMetric,
    val productId: Long,
    val amountMinor: Long,
    val currency: String,
    val sourceAt: Long,
) {
    val valueEur get() = amountMinor / 100.0
    fun toSelection(card: CollectionCard, selectedAt: Long = System.currentTimeMillis()) = CardPriceSelection(
        requireNotNull(card.sourceSetId), requireNotNull(card.sourceCardId), card.number,
        card.finish, card.variant, card.language, productId, metric, amountMinor, currency, sourceAt, selectedAt,
    )
}

/** An explicitly confirmed source snapshot, scoped to exactly one physical printing. */
data class CardPriceSelection(
    val sourceSetId: String,
    val sourceCardId: String,
    val collectorNumber: String,
    val finish: String,
    val variant: String,
    val language: CardLanguage,
    val productId: Long,
    val metric: CardPriceMetric,
    val amountMinor: Long,
    val currency: String,
    val sourceAt: Long,
    val selectedAt: Long,
) {
    val valueEur get() = amountMinor / 100.0
    fun isValid(): Boolean = sourceSetId.isNotBlank() && sourceSetId.length <= 120 &&
        sourceCardId.isNotBlank() && sourceCardId.length <= 120 &&
        collectorNumber.isNotBlank() && collectorNumber.length <= 80 &&
        finish.isNotBlank() && finish.length <= 80 && variant.isNotBlank() && variant.length <= 80 &&
        productId in 1L..MAX_PRICE_INTEGER && amountMinor in 1L..MAX_PRICE_INTEGER && currency == "EUR" &&
        sourceAt in 1L..MAX_PRICE_INTEGER && selectedAt in 1L..MAX_PRICE_INTEGER &&
        metric.matchesFinish(finish) && !isQuarantinedCardmarketIdentity(sourceCardId, productId)

    fun matches(card: CollectionCard): Boolean = isValid() &&
        sourceSetId.equals(card.sourceSetId, true) && sourceCardId.equals(card.sourceCardId, true) &&
        collectorPriceIdentity(collectorNumber) == collectorPriceIdentity(card.number) &&
        finish.trim().equals(card.finish.trim(), true) && variant.trim().equals(card.variant.trim(), true) &&
        language == card.language && productId == card.externalProductId &&
        !isQuarantinedCardmarketIdentity(card.sourceCardId ?: card.id, card.externalProductId)
}

/** Retains explicit denominators and letters; 001/198 equals 1/198, never 1/102. */
internal fun collectorPriceIdentity(value: String): String = value.trim().uppercase(Locale.ROOT)
    .replace(Regex("\\s+"), "").split('/').joinToString("/") { it.toLongOrNull()?.toString() ?: it }

fun CatalogSearchResult.priceAlternativesFor(finish: String): List<CardPriceAlternative> {
    if (isQuarantinedCardmarketIdentity(sourceCardId.orEmpty(), productId)) return emptyList()
    val trend = if (finish.contains("reverse", true)) holoTrendPriceEur else trendPriceEur
    if (trend != null && trend.isFinite() && trend > 0.0) return emptyList()
    return priceAlternatives.filter {
        hasMarketProduct && it.productId == productId && it.amountMinor > 0L && it.currency == "EUR" &&
            it.sourceAt > 0L && it.metric.matchesFinish(finish)
    }
}

private const val MAX_PRICE_INTEGER = 9_007_199_254_740_991L
