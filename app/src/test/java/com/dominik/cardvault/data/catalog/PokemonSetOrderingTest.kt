package com.dominik.cardvault.data.catalog

import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonSetOrderingTest {
    @Test fun currentMegaAndScarletVioletSetsSortAheadOfOlderErasWithoutDates() {
        val mega = set("me04")
        val scarletViolet = set("sv03")
        val swordShield = set("swsh12")
        val classic = set("base1")

        assertTrue(catalogSetRecencyKey(mega) > catalogSetRecencyKey(scarletViolet))
        assertTrue(catalogSetRecencyKey(scarletViolet) > catalogSetRecencyKey(swordShield))
        assertTrue(catalogSetRecencyKey(swordShield) > catalogSetRecencyKey(classic))
    }

    private fun set(id: String) = PokemonSet(id, id, "Deutsch · TCGdex", 100, 100, "", null, null)
}
