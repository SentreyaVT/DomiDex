package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BinderMasterSlotTest {
    @Test fun cachedPrintingAndAddedCanonicalReferenceRepresentOneSlot() {
        listOf("Normal", "Holo", "Reverse Holo").forEach { finish ->
            val reference = sourceCard(finish)
            val canonicalReference = reference.copy(id = "tcgdex-sv03-sv03-001")
            val owned = canonicalReference.copy(entryId = 11, quantity = 1, condition = "Nicht geprüft")
            val references = listOf(reference, canonicalReference)

            val slots = arrangeBinderCards("Obsidian Flammen", listOf(owned), 197, references)

            assertEquals("Duplicate $finish reference must not leave a placeholder", listOf(owned), slots)
            assertEquals(listOf(reference), arrangeBinderCards("Obsidian Flammen", emptyList(), 197, references))
        }
    }

    @Test fun duplicateReferencesDoNotShiftSlotsOrMixPrintingsAndQuantities() {
        val references = listOf("Normal", "Holo", "Reverse Holo").map(::sourceCard)
        val reverse = references[2].copy(entryId = 11, quantity = 2, condition = "Nicht geprüft")
        val nextCard = references[0].copy(id = "next", sourceCardId = "sv03-002", number = "002")
        val duplicate = references[2].copy(id = "tcgdex-sv03-sv03-001")
        val slots = arrangeBinderCards("Obsidian Flammen", listOf(reverse), 197, references + duplicate + nextCard)

        assertEquals(listOf(references[0], references[1], reverse, nextCard), slots)
        assertEquals(2, slots[2]?.quantity)
        assertEquals("sv03", slots[2]?.sourceSetId)
        assertEquals("sv03-001", slots[2]?.sourceCardId)
    }

    @Test fun distinctEditionsAndSourceIdentitiesAreNotDeduplicated() {
        val reference = sourceCard("Holo")
        val special = reference.copy(id = "first-edition", variant = "1. Edition")
        val differentCard = reference.copy(id = "other-card", sourceCardId = "sv03-999")
        val differentSet = reference.copy(id = "other-set", sourceSetId = "sv02")
        val references = listOf(reference, special, differentCard, differentSet)
        val slots = arrangeBinderCards("Obsidian Flammen", emptyList(), 197, references)

        assertEquals(4, slots.size)
        assertEquals(references.toSet(), slots.toSet())
    }

    private fun sourceCard(finish: String) = card(0, finish, if (finish == "Normal") "Standard" else finish)
        .copy(id = "tcgdex-sv03-sv03-001-$finish", sourceSetId = "sv03", sourceCardId = "sv03-001")

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
