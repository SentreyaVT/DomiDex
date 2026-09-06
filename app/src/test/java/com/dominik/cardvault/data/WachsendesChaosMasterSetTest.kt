package com.dominik.cardvault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WachsendesChaosMasterSetTest {
    @Test
    fun `keeps every confirmed normal and reverse printing as a separate slot`() {
        val cards = WachsendesChaosMasterSet.cards

        assertEquals(194, cards.size)
        assertEquals(122, cards.map { it.number }.distinct().size)
        assertEquals(69, cards.count { it.finish == "Normal" && it.variant == "Standard" })
        assertEquals(52, cards.count { it.finish == "Holo" && it.variant == "Standard" })
        assertEquals(1, cards.count { it.finish == "Holo" && it.variant == "Standard · Folie: Gold" })
        assertEquals(72, cards.count { it.finish == "Reverse Holo" && it.variant == "Standard" })
        assertTrue(cards.map { it.id }.distinct().size == cards.size)
        assertTrue(cards.all { it.sourceSetId == "me04" })
        assertTrue(cards.all { it.sourceCardId == "me04-${it.number.substringBefore('/')}" })
        assertEquals(122, cards.map { it.sourceCardId }.distinct().size)
    }

    @Test
    fun `uses source declared base finishes for early middle and secret cards`() {
        val cards = WachsendesChaosMasterSet.cards

        assertEquals(listOf("Normal", "Reverse Holo"), cards.filter { it.sourceCardId == "me04-001" }.map { it.finish })
        assertEquals(listOf("Holo"), cards.filter { it.sourceCardId == "me04-003" }.map { it.finish })
        assertEquals(listOf("Normal", "Reverse Holo"), cards.filter { it.sourceCardId == "me04-050" }.map { it.finish })
        assertEquals(listOf("Holo"), cards.filter { it.sourceCardId == "me04-087" }.map { it.finish })
        assertEquals(listOf("Holo"), cards.filter { it.sourceCardId == "me04-122" }.map { it.finish })
        assertEquals(listOf("Standard · Folie: Gold"), cards.filter { it.sourceCardId == "me04-122" }.map { it.variant })
    }
}
