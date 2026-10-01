package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonVisualCatalogThirtyYearsTest {
    @Test fun sharedPrintedCodeResolvesMainSecretAndHistoricClassicNumbers() = runBlocking {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache(), ::fixture)

        val mew = catalog.findExact("30C", "065/128").single()
        assertEquals("30th-065", mew.id)
        assertEquals("6601", mew.set.cardmarketExpansionId)
        assertEquals(listOf(907_672L), mew.printVariants.mapNotNull { it.cardmarketProductId }.distinct())
        assertEquals(listOf("30th-158"), catalog.findExact("30C", "158/128").map { it.id })
        val classic = catalog.findExact("30C", "089/149").single()
        assertEquals("30th-c-017", classic.id)
        assertEquals("89/149", classic.number)
        assertEquals("017", classic.sourceLocalId)
        assertEquals(listOf("30th-c-017"), catalog.findExact("30th-c", "017").map { it.id })
        assertTrue(catalog.findExact("30C", "089").isEmpty())
    }

    @Test fun classicCardsReceiveOfficialImagesAndSetsRemainSeparate() = runBlocking {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache(), ::fixture)
        val sets = catalog.sets()

        val anniversarySets = sets.filter { it.id.startsWith("30th") }
        assertEquals(listOf("30th", "30th-c"), anniversarySets.map { it.id })
        assertEquals(listOf(161, 30), anniversarySets.map { it.total })
        assertTrue(anniversarySets.all { it.logoUrl.orEmpty().contains("30th_logo_169_de.png") })
        val classic = catalog.cards(sets.single { it.id == "30th-c" }).single()
        assertEquals("89/149", classic.number)
        assertTrue(classic.largeImageUrl.endsWith("30TH-C_DE_17.png"))
    }

    @Test fun missingSetCodeKeepsClassicAlongsideHistoricOriginalSetCandidate() = runBlocking {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache(), ::fixture)

        val matches = catalog.findByPrintedTotalAndCollectorNumber("089/149").map { it.id }
        assertEquals(setOf("historic-089", "30th-c-017"), matches.toSet())
        assertEquals("30th-c-017", matches.first())
    }

    private fun fixture(url: String): BufferedSource {
        val json = when {
            url.endsWith("/sets") -> """[
                {"id":"30th","name":"30 Jahre","releaseDate":"2026-09-16","serie":{"id":"me","name":"Mega-Entwicklung"},"abbreviation":{"official":"30C"},"cardCount":{"official":128,"total":158}},
                {"id":"30th-c","name":"30 Jahre: Klassische Sammlung","releaseDate":"2026-09-16","serie":{"id":"me","name":"Mega-Entwicklung"},"abbreviation":{"official":"30C"},"cardCount":{"official":0,"total":30}},
                {"id":"historic","name":"Historisches Set","releaseDate":"2018-01-01","serie":{"id":"sm","name":"Sonne & Mond"},"abbreviation":{"official":"HIS"},"cardCount":{"official":149,"total":149}}
            ]"""
            url.endsWith("/sets/30th-c") -> """{
                "abbreviation":{"official":"30C"},"serie":{"id":"me"},
                "cards":[{"id":"30th-c-017","localId":"017","name":"Solgaleo GX","rarity":"Keine"}]
            }"""
            url.endsWith("/sets/30th") -> """{
                "abbreviation":{"official":"30C"},"serie":{"id":"me"},
                "cards":[
                    {"id":"30th-065","localId":"065","name":"Mew","rarity":"Rare"},
                    {"id":"30th-158","localId":"158","name":"Zukunftskarte","rarity":"Futuristic Rare"}
                ]
            }"""
            url.endsWith("/sets/historic") -> """{
                "abbreviation":{"official":"HIS"},"serie":{"id":"sm"},
                "cards":[{"id":"historic-089","localId":"089","name":"Historisches Solgaleo","rarity":"Rare"}]
            }"""
            url.contains("/cards/") -> """{"rarity":"Rare","variants_detailed":[]}"""
            else -> error("Unexpected fixture request: $url")
        }
        return Buffer().writeUtf8(json)
    }
}
