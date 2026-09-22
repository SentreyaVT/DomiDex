package com.dominik.cardvault.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class PokemonSetRegistryTest {
    @Test fun printedModernCodeResolvesTheZeroPaddedGermanSourceSet() {
        val set = PokemonSet("sv03", "Obsidian Flammen", "Karmesin & Purpur", 197, 230, "2023/08/11", null, null)

        val resolved = PokemonSetRegistry.resolve("OBF", listOf(set))

        assertNotNull(resolved)
        assertEquals("sv03", resolved?.id)
        assertEquals("OBF", PokemonSetRegistry.metadata(set).setCode)
    }

    @Test fun sharedAnniversaryCodeKeepsMainAndClassicSourceIdentities() {
        val main = PokemonSet("30th", "30 Jahre", "Mega-Entwicklung", 128, 158, "2026-09-16", null, "30C")
        val classic = PokemonSet("30th-c", "30 Jahre: Klassische Sammlung", "Mega-Entwicklung", 0, 30, "2026-09-16", null, "30C")
        PokemonSetRegistry.registerSourceSets(listOf(main, classic))

        assertEquals(listOf(main, classic), PokemonSetRegistry.resolveAll("30C", listOf(main, classic)))
        assertSame(classic, PokemonSetRegistry.resolve("30th-c", listOf(main, classic)))
    }
}
