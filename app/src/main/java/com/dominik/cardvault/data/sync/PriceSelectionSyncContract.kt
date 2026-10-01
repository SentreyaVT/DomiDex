package com.dominik.cardvault.data.sync

import com.dominik.cardvault.data.local.CollectionPriceSelectionEntity
import com.dominik.cardvault.data.local.SYNC_CLEAN
import com.dominik.cardvault.data.selectionOrNull

internal const val PRICE_SELECTION_SCHEMA_VERSION = 1L
internal const val MAX_PRICE_SELECTION_INTEGER = 9_007_199_254_740_991L

internal val PRICE_SELECTION_VALUE_FIELDS = setOf(
    "sourceSetId", "sourceCardId", "collectorNumber", "finish", "variant", "language", "productId",
    "metric", "amountMinor", "currency", "sourceAt", "selectedAt",
)

/** The collection document stays v26-compatible; only this additive sidecar carries the choice. */
internal fun CollectionPriceSelectionEntity.priceSelectionPayload(mutationId: String): Map<String, Any?> = mapOf(
    "schemaVersion" to PRICE_SELECTION_SCHEMA_VERSION,
    "ownerUid" to ownerUid,
    "entrySyncId" to entrySyncId,
    "updatedAtClient" to updatedAtEpochMillis,
    "lastMutationId" to mutationId,
    "cleared" to cleared,
    "sourceSetId" to sourceSetId.takeUnless { cleared },
    "sourceCardId" to sourceCardId.takeUnless { cleared },
    "collectorNumber" to collectorNumber.takeUnless { cleared },
    "finish" to finish.takeUnless { cleared },
    "variant" to variant.takeUnless { cleared },
    "language" to language.takeUnless { cleared },
    "productId" to productId.takeUnless { cleared },
    "metric" to metric.takeUnless { cleared },
    "amountMinor" to amountMinor.takeUnless { cleared },
    "currency" to currency.takeUnless { cleared },
    "sourceAt" to sourceAt.takeUnless { cleared },
    "selectedAt" to selectedAt.takeUnless { cleared },
)

/** Rejects incomplete/foreign/fractional payloads before they enter Room. */
internal fun parsePriceSelectionSidecar(
    data: Map<String, Any?>,
    ownerUid: String,
    entrySyncId: String,
    collectionEntryId: Long,
): CollectionPriceSelectionEntity? {
    fun positiveInteger(key: String): Long? {
        val value = data[key] as? Number ?: return null
        val numeric = value.toDouble()
        val integer = value.toLong()
        return integer.takeIf { numeric.isFinite() && it in 1L..MAX_PRICE_SELECTION_INTEGER && numeric == it.toDouble() }
    }
    if (positiveInteger("schemaVersion") != PRICE_SELECTION_SCHEMA_VERSION ||
        data["ownerUid"] != ownerUid || data["entrySyncId"] != entrySyncId ||
        !entrySyncId.matches(Regex("[A-Za-z0-9._-]{1,128}")) ||
        (data["lastMutationId"] as? String)?.matches(Regex("[A-Za-z0-9._-]{1,128}")) != true
    ) return null
    val revision = positiveInteger("revision") ?: return null
    val updatedAt = positiveInteger("updatedAtClient") ?: return null
    val cleared = data["cleared"] as? Boolean ?: return null
    if (!data.keys.containsAll(PRICE_SELECTION_VALUE_FIELDS)) return null
    if (cleared && PRICE_SELECTION_VALUE_FIELDS.any { data[it] != null }) return null
    val entity = CollectionPriceSelectionEntity(
        collectionEntryId, ownerUid, entrySyncId, cleared,
        data["sourceSetId"] as? String, data["sourceCardId"] as? String,
        data["collectorNumber"] as? String, data["finish"] as? String, data["variant"] as? String,
        data["language"] as? String, if (cleared) null else positiveInteger("productId"),
        data["metric"] as? String, if (cleared) null else positiveInteger("amountMinor"),
        data["currency"] as? String, if (cleared) null else positiveInteger("sourceAt"),
        if (cleared) null else positiveInteger("selectedAt"), updatedAt, SYNC_CLEAN, revision,
    )
    return entity.takeIf { cleared || it.selectionOrNull() != null }
}
