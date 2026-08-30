package com.dominik.cardvault.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PokemonSetRegistryTest {
    @Test fun printedModernCodeResolvesTheZeroPaddedGermanSourceSet() {
        val set = PokemonSet("sv03", "Obsidian Flammen", "Karmesin & Purpur", 197, 230, "2023/08/11", null, null)

        val resolved = PokemonSetRegistry.resolve("OBF", listOf(set))

        assertNotNull(resolved)
        assertEquals("sv03", resolved?.id)
        assertEquals("OBF", PokemonSetRegistry.metadata(set).setCode)
    }
}
