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
        assertEquals(122, cards.count { it.finish == "Normal" && it.variant == "Standard" })
        assertEquals(72, cards.count { it.finish == "Reverse Holo" && it.variant == "Reverse Holo" })
        assertTrue(cards.map { it.id }.distinct().size == cards.size)
    }
}
