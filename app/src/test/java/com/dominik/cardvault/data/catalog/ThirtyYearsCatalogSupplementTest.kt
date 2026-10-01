package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ThirtyYearsCatalogSupplementTest {
    private val main = PokemonSet("30th", "30 Jahre", "Mega-Entwicklung", 128, 158, "2026-09-16", null, "30C")
    private val promos = PokemonSet("mep", "MEP Black Star Promos", "Mega-Entwicklung", 0, 88, "2025-09-26", null, "MEP")

    @Test fun rgbCardsHaveDistinctPrintedIdentitiesAndArtworkNotNumericInventedSlots() {
        val cards = supplementalThirtyYearsCards(main)
        val rgb = cards.filter { it.number.endsWith("/RGB") }
        assertEquals(setOf("R/RGB", "G/RGB", "B/RGB"), rgb.map { it.number }.toSet())
        assertEquals(3, rgb.map { it.id }.distinct().size)
        assertEquals(3, rgb.map { it.largeImageUrl }.distinct().size)
        assertTrue(rgb.all { it.largeImageUrl.startsWith("https://") })
        assertTrue(rgb.all { it.name == "Mew" && it.set.id == "30th" && it.set.printedTotal == 128 })
        assertTrue(rgb.none { it.id in setOf("30th-065", "30th-158", "30th-159", "30th-160", "30th-161") })
        assertEquals(mapOf("R/RGB" to 909509L, "G/RGB" to 909510L, "B/RGB" to 909511L),
            rgb.associate { it.number to it.printVariants.single().cardmarketProductId })
        assertTrue(rgb.none { it.printVariants.single().cardmarketProductId in setOf(907672L, 907762L, 908354L) })
    }

    @Test fun allSeventeenAnniversaryBlackStarPromosKeepTheirActualNumbers() {
        val cards = supplementalThirtyYearsCards(promos)
        assertEquals((94..110).map { it.toString().padStart(3, '0') }.toSet(), cards.map { it.number }.toSet())
        assertEquals(17, cards.map { it.id }.distinct().size)
        assertTrue(cards.all { it.set.id == "mep" && it.rarity == "Promo" })
        assertEquals("Mew", cards.single { it.number == "105" }.name)
        assertEquals("Mewtu", cards.single { it.number == "104" }.name)
        assertEquals("Nidorina", cards.single { it.number == "101" }.name)
        assertTrue(cards.all { it.largeImageUrl.startsWith("https://") })
    }

    @Test fun supplementIsIdempotentAndLeavesOtherSetsAndExistingCardsAlone() {
        val regular = PokemonVisualCard("30th-065", "Mew", "065", "Rare", "old-small", "old-large", main)
        val once = mergeThirtyYearsCatalogSupplement(main, listOf(regular))
        val twice = mergeThirtyYearsCatalogSupplement(main, once)
        assertEquals(once, twice)
        assertEquals(regular, twice.single { it.id == regular.id })
        val other = main.copy(id = "sv03")
        assertEquals(listOf(regular), mergeThirtyYearsCatalogSupplement(other, listOf(regular)))
    }

    @Test fun laterUpstreamPrintingDoesNotCreateDuplicateCollectorSlot() {
        val source = PokemonVisualCard("mep-105", "Mew", "105", "Promo", "source-small", "source-large", promos,
            printVariants = listOf(PokemonPrintVariant("upstream-holo", "Holo", "Standard", 895621L)), sourceLocalId = "105")
        val merged = mergeThirtyYearsCatalogSupplement(promos, listOf(source))
        assertEquals(1, merged.count { it.number == "105" })
        assertEquals(source, merged.single { it.number == "105" })
    }

    @Test fun exactRgbAndPromoLookupWorksWithoutMissingDetailRequests() = runBlocking {
        var detailRequests = 0
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            val json = when {
                url.endsWith("/sets") -> """[
                    {"id":"30th","name":"30 Jahre","cardCount":{"official":128,"total":158},"abbreviation":{"official":"30C"}},
                    {"id":"30th-c","name":"30 Jahre: Klassische Sammlung","cardCount":{"official":0,"total":30},"abbreviation":{"official":"30C"}},
                    {"id":"mep","name":"MEP Black Star Promos","cardCount":{"official":0,"total":88},"abbreviation":{"official":"MEP"}}
                ]"""
                url.contains("/sets/") -> """{"cards":[]}"""
                else -> { detailRequests++; error("Supplement attempted nonexistent detail request: $url") }
            }
            Buffer().writeUtf8(json)
        }
        for (letter in listOf("R", "G", "B")) {
            val card = catalog.findExact("30C", "$letter/RGB").single()
            assertEquals("$letter/RGB", card.number)
            assertEquals("30th", card.set.id)
            assertEquals(mapOf("R" to 909509L, "G" to 909510L, "B" to 909511L)[letter],
                card.printVariants.single().cardmarketProductId)
        }
        assertTrue(catalog.findExact("30C", "R").isEmpty()) // shared code stays conservative
        assertTrue(catalog.findExact("30C", "R/128").isEmpty())
        assertTrue(catalog.findExact("30th-c", "R/RGB").isEmpty())
        assertTrue(catalog.findExact("30C", "Y/RGB").isEmpty())
        for (number in 94..110) {
            val card = catalog.findExact("MEP", "MEP$number").single()
            assertEquals(number.toString().padStart(3, '0'), card.number)
            assertEquals("mep", card.set.id)
        }
        assertEquals(0, detailRequests)
        assertEquals(161, catalog.sets().single { it.id == "30th" }.total)
        assertEquals(128, catalog.sets().single { it.id == "30th" }.printedTotal)
        assertTrue(catalog.sets().single { it.id == "mep" }.total >= 105)
    }
}
