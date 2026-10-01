package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A card-wide source product is not evidence for the requested collector/printing.
 * The 2026-09-25 source audit reproduced the same root product on different
 * collector numbers. These guards prevent a price-coverage fix from borrowing it.
 * Product IDs below are identity evidence, never hardcoded market prices.
 */
class PokemonCardLevelPriceIdentitySafetyTest {
    @Test fun sameRootIdOnDifferentCollectorNumbersCannotBecomeAPrintingLink() = runBlocking {
        // Fresh source: both Serpifeu cards expose 279739 at card level only.
        val catalog = catalog { id ->
            """{
                "id":"$id","thirdParty":{"cardmarket":279739},
                "pricing":{"cardmarket":{"idProduct":279739}},
                "variants_detailed":[{"variantId":"normal","type":"normal","size":"Standard"}]
            }"""
        }

        listOf("1", "2").forEach { number ->
            val detailed = catalog.withDeclaredPrintVariants(card("bw1", number, "Serpifeu", 114))
            assertEquals(number, detailed.number)
            assertNull(detailed.printVariants.single().cardmarketProductId)
        }
    }

    @Test fun aboveMainSecretDoesNotBorrowNamesakeOrdinaryRootProduct() = runBlocking {
        // Historical full audit: Secret Psychic Energy 162 and basic 168 both
        // expose 295475. A shared name/root must not resolve the secret printing.
        val catalog = catalog { id ->
            """{
                "id":"$id","thirdParty":{"cardmarket":295475},
                "pricing":{"cardmarket":{"idProduct":295475}},
                "variants_detailed":[{"variantId":"normal","type":"normal","size":"Standard"}]
            }"""
        }
        val secret = card("sm1", "162", "Psycho-Energie", 149)

        val detailed = catalog.withDeclaredPrintVariants(secret)

        assertEquals(secret.id, detailed.id)
        assertEquals("162", detailed.number)
        assertEquals(149, detailed.set.printedTotal)
        assertNull(detailed.printVariants.single().cardmarketProductId)
    }

    @Test fun exactFullArtVariantKeepsItsProductEvenWhenRootNamesakeDiffers() = runBlocking {
        val catalog = catalog { id ->
            """{
                "id":"$id","thirdParty":{"cardmarket":805503},
                "pricing":{"cardmarket":{"idProduct":805503}},
                "variants_detailed":[{
                    "variantId":"full-art","type":"Holo","size":"Standard",
                    "thirdParty":{"cardmarket":805531}
                }]
            }"""
        }

        val detailed = catalog.withDeclaredPrintVariants(card("sv08.5", "137", "Freunde aus Paldea", 131))

        assertEquals("137", detailed.number)
        assertEquals(PokemonPrintVariant("full-art", "Holo", "Standard", 805531L), detailed.printVariants.single())
    }

    @Test fun rootCannotFillMissingSpecialPrintingOrOverrideExplicitSpecialLink() = runBlocking {
        val catalog = catalog { id ->
            """{
                "id":"$id","thirdParty":{"cardmarket":733596},
                "variants_detailed":[
                    {"variantId":"regular","type":"Normal","size":"Standard","thirdParty":{"cardmarket":733596}},
                    {"variantId":"stamp","type":"Normal","size":"Standard","stamp":["Set-Logo"]},
                    {"variantId":"foil","type":"Reverse","size":"Standard","foil":"Kosmos"},
                    {"variantId":"subtype","type":"Holo","size":"Standard","subtype":"special"},
                    {"variantId":"jumbo","type":"Holo","size":"Jumbo"},
                    {"variantId":"explicit-stamp","type":"Normal","size":"Standard","stamp":["Set-Logo"],"thirdParty":{"cardmarket":720365}}
                ]
            }"""
        }

        val printings = catalog.withDeclaredPrintVariants(card("sv03.5", "001", "Bisasam", 165)).printVariants

        assertEquals(6, printings.size)
        assertEquals(733596L, printings.first().cardmarketProductId)
        printings.subList(1, 5).forEach { assertNull(it.cardmarketProductId) }
        assertEquals(720365L, printings.last().cardmarketProductId)
        assertEquals("Standard · Stempel: Set-Logo", printings.last().variant)
    }

    @Test fun reviewedAnniversarySarBridgeIsNotReplacedByCardWideBaseProduct() = runBlocking {
        val catalog = catalog { id ->
            """{
                "id":"$id","thirdParty":{"cardmarket":907761},
                "pricing":{"cardmarket":{"idProduct":907761}},
                "variants_detailed":[{"variantId":"sar","type":"Holo","size":"Standard"}]
            }"""
        }

        val detailed = catalog.withDeclaredPrintVariants(card("30th", "151", "Mewtwo-ex", 128))

        assertEquals(908353L, detailed.printVariants.single().cardmarketProductId)
    }

    @Test fun quarantinedExplicitProductIsNotRestoredThroughRootPricing() = runBlocking {
        val catalog = catalog { id ->
            """{
                "id":"$id","thirdParty":{"cardmarket":670816},
                "pricing":{"cardmarket":{"idProduct":670816}},
                "variants_detailed":[{
                    "variantId":"regular","type":"Holo","size":"Standard",
                    "thirdParty":{"cardmarket":670816}
                }]
            }"""
        }

        val detailed = catalog.withDeclaredPrintVariants(card("swsh11", "201", "Giratina VSTAR", 196))

        assertNull(detailed.printVariants.single().cardmarketProductId)
    }

    @Test fun cardWidePricingIdAloneCannotEstablishPrintingIdentity() = runBlocking {
        val catalog = catalog { id ->
            """{
                "id":"$id","pricing":{"cardmarket":{"idProduct":702298}},
                "variants_detailed":[
                    {"variantId":"normal","type":"Normal","size":"Standard"},
                    {"variantId":"reverse","type":"Reverse","size":"Standard"}
                ]
            }"""
        }

        val printings = catalog.withDeclaredPrintVariants(card("unreviewed-test", "001", "Unreviewed", 198)).printVariants

        assertEquals(listOf("Normal", "Reverse Holo"), printings.map(PokemonPrintVariant::finish))
        printings.forEach { assertNull(it.cardmarketProductId) }
    }

    private fun catalog(payload: (String) -> String): PokemonVisualCatalog =
        PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            Buffer().writeUtf8(payload(url.substringAfterLast('/')))
        }

    private fun card(setId: String, number: String, name: String, printedTotal: Int) = PokemonVisualCard(
        id = "$setId-$number",
        name = name,
        number = number,
        rarity = null,
        smallImageUrl = "",
        largeImageUrl = "",
        set = PokemonSet(setId, setId, "Test", printedTotal, 300, "", null, null),
    )
}
