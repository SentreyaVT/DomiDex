package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonCardmarketIdentityTest {
    private val set = PokemonSet("sv08.5", "Prismatische Entwicklungen", "Test", 131, 180, "", null, null)
    private val card = PokemonVisualCard(
        "sv08.5-137", "Freunde aus Paldea", "137", null,
        "source-small", "source-large", set, marketSearchName = "Friends in Paldea",
    )

    @Test fun detailPreservesDeclaredCardmarketProductAndGermanSourceIdentity() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            requestedUrls += url
            Buffer().writeUtf8("""{
                "id":"sv08.5-137",
                "rarity":"Ultra Rare",
                "variants_detailed":[{
                    "variantId":"full-art",
                    "type":"holo",
                    "size":"normal",
                    "thirdParty":{"otherService":{"id":12},"cardmarket":805531}
                }]
            }""")
        }

        val detailed = catalog.withDeclaredPrintVariants(card)

        assertEquals(listOf("https://api.tcgdex.net/v2/de/cards/sv08.5-137"), requestedUrls)
        assertEquals(card.id, detailed.id)
        assertEquals(card.name, detailed.name)
        assertEquals(card.number, detailed.number)
        assertEquals(card.set, detailed.set)
        assertEquals(card.marketSearchName, detailed.marketSearchName)
        assertEquals(card.largeImageUrl, detailed.largeImageUrl)
        assertEquals("Ultra Rare", detailed.rarity)
        assertEquals(PokemonPrintVariant("full-art", "Holo", "normal", 805531L), detailed.printVariants.single())
    }

    @Test fun absentNullAndNonPositiveIdsStayUnlinkedWithoutDroppingPrintings() = runBlocking {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) {
            Buffer().writeUtf8("""{"variants_detailed":[
                {"variantId":"missing-object","type":"normal","size":"normal"},
                {"variantId":"null-object","type":"holo","size":"normal","thirdParty":null},
                {"variantId":"missing-id","type":"holo","size":"normal","thirdParty":{"other":12}},
                {"variantId":"null-id","type":"holo","size":"normal","thirdParty":{"cardmarket":null}},
                {"variantId":"zero-id","type":"holo","size":"normal","thirdParty":{"cardmarket":0}},
                {"variantId":"negative-id","type":"holo","size":"normal","thirdParty":{"cardmarket":-1}}
            ]}""")
        }

        val detailed = catalog.withDeclaredPrintVariants(card)

        assertEquals(6, detailed.printVariants.size)
        detailed.printVariants.forEach { assertNull(it.cardmarketProductId) }
        assertEquals("Normal", detailed.printVariants.first().finish)
        assertTrue(detailed.printVariants.drop(1).all { it.finish == "Holo" })
    }

    @Test fun separateFinishAndEditionLinksSurviveDetailCacheReuse() = runBlocking {
        var requests = 0
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) {
            requests++
            // Synthetic ids exercise per-printing identity; this fixture declares no live prices.
            Buffer().writeUtf8("""{"variants_detailed":[
                {"variantId":"regular","type":"normal","size":"Standard","thirdParty":{"cardmarket":900001}},
                {"variantId":"reverse","type":"reverse holo","size":"Reverse Holo","thirdParty":{"cardmarket":900002}},
                {"variantId":"edition","type":"holo","size":"1. Edition","thirdParty":{"cardmarket":900003}}
            ]}""")
        }

        val first = catalog.withDeclaredPrintVariants(card)
        val renamed = catalog.withDeclaredPrintVariants(card.copy(name = "Aktualisierter Anzeigename"))

        assertEquals(1, requests)
        assertEquals(first.printVariants, renamed.printVariants)
        assertEquals("Aktualisierter Anzeigename", renamed.name)
        assertEquals(listOf(900001L, 900002L, 900003L), renamed.printVariants.map { it.cardmarketProductId })
        assertEquals(listOf("Normal", "Reverse Holo", "Holo"), renamed.printVariants.map { it.finish })
        assertEquals(listOf("Standard", "Reverse Holo", "1. Edition"), renamed.printVariants.map { it.variant })
    }
}
