package com.dominik.cardvault.data.sync

import com.dominik.cardvault.data.local.CollectionPriceSelectionEntity
import com.dominik.cardvault.data.selectionOrNull
import org.junit.Assert.*
import org.junit.Test

class PriceSelectionSyncContractTest {
    private val entity = CollectionPriceSelectionEntity(
        4L, "owner-a", "entry-a", false, "me03", "me03-090", "090/088", "Holo", "Holo",
        "GERMAN", 877507L, "normal_avg_sell", 532L, "EUR", 1_000L, 2_000L, 3_000L,
    )
    private fun payload() = entity.priceSelectionPayload("mutation-a") + ("revision" to 1L)
    private fun parse(data: Map<String, Any?>) = parsePriceSelectionSidecar(data, "owner-a", "entry-a", 8L)

    @Test fun stableWireRoundTripKeepsEverySnapshotFieldAndLocalIdsAreIndependent() {
        val restored = parse(payload())!!
        assertEquals(8L, restored.collectionEntryId)
        assertEquals(entity.selectionOrNull(), restored.selectionOrNull())
        assertEquals(1L, restored.remoteRevision)
        assertEquals("CLEAN", restored.syncState)
        assertEquals("users/owner-a/collectionPriceSelections/entry-a", collectionPriceSelectionPath("owner-a", "entry-a"))
        assertFalse(payload().containsKey("deleted"))
    }

    @Test fun clearExplicitlyNullsEveryChoiceFieldAndCanLaterBeSelectedAgain() {
        val clear = entity.copy(cleared = true).priceSelectionPayload("clear-a") + ("revision" to 2L)
        assertTrue(PRICE_SELECTION_VALUE_FIELDS.all { clear.containsKey(it) && clear[it] == null })
        assertTrue(parse(clear)!!.cleared)
        assertNull(parse(clear)!!.selectionOrNull())
        assertNull(parse(clear + ("productId" to 877507L)))
        assertEquals(PrivatePushAction.APPLY, privatePushAction(2, 2, false, false, false))
        assertNotNull(parse(payload() + ("revision" to 3L))!!.selectionOrNull())
    }

    @Test fun partialForeignNonIntegralAndUnsafePayloadsAreRejected() {
        listOf(
            payload() - "sourceAt", payload() + ("ownerUid" to "other"),
            payload() + ("entrySyncId" to "other"), payload() + ("schemaVersion" to 2L),
            payload() + ("amountMinor" to 1.5), payload() + ("amountMinor" to Double.NaN),
            payload() + ("amountMinor" to Long.MAX_VALUE), payload() + ("productId" to 0L),
            payload() + ("metric" to "trend"), payload() + ("language" to "Deutsch"),
            payload() + ("sourceAt" to null), payload() + ("selectedAt" to -1L),
            payload() + ("finish" to "Reverse Holo"), payload() + ("currency" to "USD"),
        ).forEach { assertNull(it.toString(), parse(it)) }
    }
}
