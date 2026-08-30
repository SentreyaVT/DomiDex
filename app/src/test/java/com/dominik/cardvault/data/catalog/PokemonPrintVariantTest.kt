package com.dominik.cardvault.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PokemonPrintVariantTest {
    @Test
    fun `maps normal holo and reverse source keys to distinct binder printings`() {
        val printings = listOf("normal", "holofoil", "reverseHolofoil").mapNotNull(::pokemonPrintVariantForSourceKey)

        assertEquals(listOf("Normal", "Holo", "Reverse Holo"), printings.map { it.finish })
        assertEquals(listOf("Standard", "Holo", "Reverse Holo"), printings.map { it.variant })
    }

    @Test
    fun `does not invent a slot for an unknown source key`() {
        assertNull(pokemonPrintVariantForSourceKey("etchedFoil"))
    }
}
