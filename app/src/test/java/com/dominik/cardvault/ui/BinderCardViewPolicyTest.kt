package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard
import com.dominik.cardvault.model.PrivateBinderCard
import org.junit.Assert.assertEquals
import org.junit.Test

class BinderCardViewPolicyTest {
    private fun card(
        id: Long,
        name: String,
        number: String,
        value: Double = 0.0,
        created: Long? = null,
        entryId: Long = id,
    ) = CollectionCard(
        entryId = entryId, id = "card-$id", name = name, set = "Testset", number = number,
        language = CardLanguage.GERMAN, finish = "Normal", variant = "Standard", condition = "Near Mint",
        quantity = 1, currentValueEur = value, changePercent = 0.0,
        priceUpdatedAtEpochMillis = value.takeIf { it > 0 }?.let { 1L }, notes = "",
        acquisitionPriceEur = null, acquisitionDateEpochDay = null, accent = 0L,
        createdAtEpochMillis = created,
    )

    @Test fun `missing-only filters before it orders and never exposes owned cards`() {
        val owned = card(1, "Alpha", "001", value = 50.0, created = 100)
        val missingNewestNumber = card(2, "Zulu", "099", entryId = 0)
        val missingFirstNumber = card(3, "Beta", "002", entryId = 0)

        val result = orderedSetBinderCards(listOf(owned, missingNewestNumber, missingFirstNumber), SetBinderCardSort.NUMBER_ASC, onlyMissing = true)

        assertEquals(listOf("002", "099"), result.filterNotNull().map { it.number })
        assertEquals(listOf(0L, 0L), result.filterNotNull().map { it.entryId })
    }

    @Test fun `set last-added uses real creation evidence and puts unknown legacy values last`() {
        val old = card(1, "Alt", "001", created = 10)
        val fresh = card(2, "Neu", "002", created = 20)
        val legacy = card(3, "Legacy", "003", created = null)

        assertEquals(
            listOf("Neu", "Alt", "Legacy"),
            orderedSetBinderCards(listOf(old, legacy, fresh), SetBinderCardSort.LAST_ADDED, onlyMissing = false)
                .filterNotNull().map { it.name },
        )
    }

    @Test fun `price order treats no reliable price as unavailable instead of zero`() {
        val high = card(1, "High", "001", value = 22.0)
        val none = card(2, "Keine Quelle", "002", value = 0.0)
        val low = card(3, "Low", "003", value = 2.0)

        assertEquals(
            listOf("High", "Low", "Keine Quelle"),
            orderedSetBinderCards(listOf(low, none, high), SetBinderCardSort.VALUE_DESC, false).filterNotNull().map { it.name },
        )
    }

    @Test fun `promo prefixes use the printed numeric component like the web binder`() {
        val promo = card(1, "Promo", "TG01")
        val ordinary = card(2, "Basis", "002")
        assertEquals(
            listOf("TG01", "002"),
            orderedSetBinderCards(listOf(ordinary, promo), SetBinderCardSort.NUMBER_ASC, false)
                .filterNotNull().map { it.number },
        )
    }

    @Test fun `private sort is a view and keeps saved positions as a separate choice`() {
        val alpha = PrivateBinderCard("a", "b", 8, card(1, "Alpha", "010"), createdAtEpochMillis = 10, updatedAtEpochMillis = 40)
        val zulu = PrivateBinderCard("z", "b", 1, card(2, "Zulu", "002"), createdAtEpochMillis = 30, updatedAtEpochMillis = 20)

        assertEquals(listOf("z", "a"), orderedPrivateBinderCards(listOf(alpha, zulu), PrivateBinderCardSort.SAVED_POSITION).map { it.placementId })
        assertEquals(listOf("z", "a"), orderedPrivateBinderCards(listOf(alpha, zulu), PrivateBinderCardSort.LAST_ADDED).map { it.placementId })
        assertEquals(listOf("a", "z"), orderedPrivateBinderCards(listOf(alpha, zulu), PrivateBinderCardSort.LAST_ADDED_ASC).map { it.placementId })
        assertEquals(listOf("a", "z"), orderedPrivateBinderCards(listOf(alpha, zulu), PrivateBinderCardSort.LAST_EDITED).map { it.placementId })
        assertEquals(listOf("z", "a"), orderedPrivateBinderCards(listOf(alpha, zulu), PrivateBinderCardSort.LAST_EDITED_ASC).map { it.placementId })
        assertEquals(8L, alpha.position)
    }
}
