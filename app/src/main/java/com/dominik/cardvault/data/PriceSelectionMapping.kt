package com.dominik.cardvault.data

import com.dominik.cardvault.data.local.*
import com.dominik.cardvault.model.*

internal fun CollectionPriceSelectionEntity.selectionOrNull(): CardPriceSelection? {
    if (cleared) return null
    return CardPriceSelection(
        sourceSetId ?: return null, sourceCardId ?: return null, collectorNumber ?: return null,
        finish ?: return null, variant ?: return null,
        CardLanguage.entries.firstOrNull { it.name == language } ?: return null,
        productId ?: return null, CardPriceMetric.fromWire(metric) ?: return null,
        amountMinor ?: return null, currency ?: return null, sourceAt ?: return null, selectedAt ?: return null,
    ).takeIf(CardPriceSelection::isValid)
}

internal fun CardPriceSelection.asEntity(entry: CollectionEntryEntity, revision: Long, updatedAt: Long) =
    CollectionPriceSelectionEntity(
        entry.id, entry.ownerUid, entry.syncId, false, sourceSetId, sourceCardId, collectorNumber,
        finish, variant, language.name, productId, metric.wireValue, amountMinor, currency, sourceAt,
        selectedAt, updatedAt, if (entry.ownerUid == GUEST_OWNER_UID) SYNC_LOCAL_ONLY else SYNC_PENDING, revision,
    )

internal fun CollectionSyncProjection.priceIdentityCard() = CollectionCard(
    entryId, "source-$sourceCardId", name, setName, collectorNumber, CardLanguage.fromStorage(language),
    finish, variant, condition, quantity, 0.0, 0.0, null, notes, null, null, 0L,
    externalProductId = externalProductId, sourceSetId = sourceSetId, sourceCardId = sourceCardId,
)

internal fun CatalogSearchProjection.priceAlternatives(): List<CardPriceAlternative> {
    val at = priceSourceAt?.takeIf { it > 0L } ?: return emptyList()
    if (currency != "EUR") return emptyList()
    return listOf(
        CardPriceMetric.NORMAL_AVG_SELL to averageValueMinor,
        CardPriceMetric.NORMAL_LOW to lowValueMinor,
        CardPriceMetric.NORMAL_AVG_1D to average1DayValueMinor,
        CardPriceMetric.NORMAL_AVG_7D to average7DayValueMinor,
        CardPriceMetric.NORMAL_AVG_30D to average30DayValueMinor,
        CardPriceMetric.REVERSE_HOLO_AVG_SELL to holoAverageValueMinor,
        CardPriceMetric.REVERSE_HOLO_LOW to holoLowValueMinor,
    ).mapNotNull { (metric, amount) ->
        amount?.takeIf { it > 0L }?.let { CardPriceAlternative(metric, externalProductId, it, "EUR", at) }
    }
}
