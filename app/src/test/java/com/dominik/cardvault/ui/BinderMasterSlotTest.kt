package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BinderMasterSlotTest {
    @Test fun confirmedNormalAndReversePrintingsKeepTwoOrderedSlots() {
        val normal = card(entryId = 11, finish = "Normal", variant = "Standard")
        val reverse = card(entryId = 0, finish = "Reverse Holo", variant = "Reverse Holo")

        val slots = arrangeBinderCards(
            setName = "Obsidian Flammen",
            cards = listOf(normal),
            capacity = 197,
            references = listOf(normal.copy(entryId = 0), reverse),
        )

        assertEquals(2, slots.size)
        assertSame(normal, slots[0])
        assertEquals("Reverse Holo", slots[1]?.finish)
        assertEquals(0, slots[1]?.quantity)
    }

    private fun card(entryId: Long, finish: String, variant: String) = CollectionCard(
        entryId = entryId,
        id = "sv03-001-$finish",
        name = "Raupy",
        set = "Obsidian Flammen",
        number = "001",
        language = CardLanguage.GERMAN,
        finish = finish,
        variant = variant,
        condition = "Nicht im Besitz",
        quantity = 0,
        currentValueEur = 0.0,
        changePercent = 0.0,
        priceUpdatedAtEpochMillis = null,
        notes = "",
        acquisitionPriceEur = null,
        acquisitionDateEpochDay = null,
        accent = 0,
        imageUrl = "",
    )
}
