package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonCatalogMetadataCache
import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.model.CatalogSearchResult
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real detail parser, maintained bridge, binder printing policy and scanner/linker helpers.
 * Fixtures reproduce the public snapshot's generated-ID shape, not current market prices.
 * Binder assertions cover the actual policy's identity inputs; no Room/UI execution is claimed.
 */
class GeneratedPrintingIdentityTest {
    private val source = card("swsh1", "10", 202)
    private val reviewed = card("30th", "151", 128)
    private val firstProduct = 900_001L
    private val secondProduct = 900_002L
    private val reviewedProduct = 908_353L

    @Test fun generatedNormalAndReverseRemainSeparateUnpricedBinderAndScannerIdentities() = runBlocking {
        val normal = printing("generated", "Normal")
        val reverse = printing("generated", "Reverse")
        val parsed = detail(source, listOf(normal, reverse), rootFields =
            """"thirdParty":{"cardmarket":$firstProduct},"pricing":{"cardmarket":{"idProduct":$firstProduct,"trend":999}}""")

        assertEquals(listOf("Normal", "Reverse Holo"), parsed.printVariants.map { it.finish })
        assertEquals(listOf("Standard", "Standard"), parsed.printVariants.map { it.variant })
        assertEquals("generated", parsed.printVariants.first().id)
        assertEquals(listOf("generated", "generated"), parsed.printVariants.map { it.sourceVariantId })
        assertEquals(2, parsed.printVariants.map { it.id }.distinct().size)
        assertDistinctUnpricedIdentities(parsed)
        assertEquals(parsed.printVariants, detail(source, listOf(normal, reverse)).printVariants)
    }

    @Test fun generatedHoloAndReverseRetainFirstExistingIdInBothSourceOrders() = runBlocking {
        val holo = printing("generated", "Holo")
        val reverse = printing("generated", "Reverse")
        for (variants in listOf(listOf(holo, reverse), listOf(reverse, holo))) {
            val parsed = detail(source, variants)

            assertEquals(2, parsed.printVariants.size)
            assertEquals(setOf("Holo", "Reverse Holo"), parsed.printVariants.map { it.finish }.toSet())
            assertEquals("generated", parsed.printVariants.first().id)
            assertNotEquals("generated", parsed.printVariants.last().id)
            assertDistinctUnpricedIdentities(parsed)
            assertEquals(parsed.printVariants, detail(source, variants).printVariants)
        }
    }

    @Test fun alreadyDistinctSourceIdsAndTheirExactLinksRemainUnchanged() = runBlocking {
        val parsed = detail(source, listOf(
            printing("ordinary", "Normal", firstProduct),
            printing("reverse", "Reverse", secondProduct),
        ))

        assertEquals(listOf(
            PokemonPrintVariant("ordinary", "Normal", "Standard", firstProduct),
            PokemonPrintVariant("reverse", "Reverse Holo", "Standard", secondProduct),
        ), parsed.printVariants)
        assertEquals(listOf(firstProduct, secondProduct), matches(parsed).map { it.productId })
        assertEquals(listOf(2.35, 4.75), matches(parsed).map { it.trendPriceEur })
    }

    @Test fun identicalPhysicalDescriptorsDeduplicateFirstWinsWithoutChangingTheExistingId() = runBlocking {
        val first = printing("generated", "Normal", firstProduct)
        val duplicate = printing("generated", "Normal", secondProduct)
        val parsed = detail(source, listOf(first, first, duplicate))

        assertEquals(listOf(PokemonPrintVariant("generated", "Normal", "Standard", firstProduct)), parsed.printVariants)
        assertEquals(listOf(firstProduct), matches(parsed).map { it.productId })
    }

    @Test fun allOriginalIdsAreReservedBeforeAllocatingASyntheticSiblingId() = runBlocking {
        val normal = printing("generated", "Normal")
        val reverse = printing("generated", "Reverse")
        val base = detail(source, listOf(normal, reverse))
        assertEquals(2, base.printVariants.size)
        val candidate = base.printVariants.single { it.finish == "Reverse Holo" }.id
        assertNotEquals("generated", candidate)
        // Discover the implementation's candidate instead of duplicating its encoding/hash.
        // A source ID equal to it must win even when that source record occurs afterwards.
        val explicit = printing(candidate, "Holo", firstProduct)
        for (variants in listOf(listOf(normal, reverse, explicit), listOf(explicit, normal, reverse))) {
            val parsed = detail(source, variants)
            assertEquals(3, parsed.printVariants.size)
            assertEquals(3, parsed.printVariants.map { it.id }.distinct().size)
            assertEquals("generated", parsed.printVariants.single { it.finish == "Normal" }.id)
            assertEquals(candidate, parsed.printVariants.single { it.finish == "Holo" }.id)
            assertEquals(firstProduct, parsed.printVariants.single { it.finish == "Holo" }.cardmarketProductId)
            assertNotEquals(candidate, parsed.printVariants.single { it.finish == "Reverse Holo" }.id)
            assertNull(parsed.printVariants.single { it.finish == "Reverse Holo" }.cardmarketProductId)
            assertEquals(parsed.printVariants, detail(source, variants).printVariants)
        }
    }

    @Test fun conflictingGeneratedPrintingCannotBlockItsValidSiblingInEitherSourceOrder() = runBlocking {
        val conflict = printing("generated", "Holo", firstProduct, secondProduct)
        val valid = printing("generated", "Reverse", secondProduct, secondProduct)
        for (variants in listOf(listOf(conflict, valid), listOf(valid, conflict))) {
            val parsed = detail(source, variants)
            assertEquals(2, parsed.printVariants.size)
            assertNull(parsed.printVariants.single { it.finish == "Holo" }.cardmarketProductId)
            assertEquals(secondProduct, parsed.printVariants.single { it.finish == "Reverse Holo" }.cardmarketProductId)
            val linked = matches(parsed)
            assertEquals(listOf(secondProduct), linked.map { it.productId })
            assertEquals("Reverse Holo", linked.single().finish)
            assertEquals(4.75, linked.single().trendPriceEur!!, 0.0)
        }
    }

    @Test fun reviewedBridgeAndConflictStayAttachedToTheirOwnDescriptorIncludingCachedReload() = runBlocking {
        val ordinary = printing("generated", "Holo")
        val conflict = printing("generated", "Reverse", firstProduct, secondProduct)
        for (variants in listOf(listOf(ordinary, conflict), listOf(conflict, ordinary))) {
            val cache = PokemonCatalogMetadataCache()
            val parsed = detail(reviewed, variants, cache = cache)
            val calls = mutableListOf<String>()
            val cachedCatalog = PokemonVisualCatalog(cache) { url ->
                calls += url
                error("The already-loaded detail must not issue another request: $url")
            }
            val renamed = cachedCatalog.withDeclaredPrintVariants(reviewed.copy(name = "Updated display name"))

            assertTrue(calls.isEmpty())
            assertEquals("Updated display name", renamed.name)
            assertEquals(parsed.printVariants, renamed.printVariants)
            for (card in listOf(parsed, renamed)) {
                assertEquals(2, card.printVariants.size)
                assertEquals(reviewedProduct, card.printVariants.single { it.finish == "Holo" }.cardmarketProductId)
                assertNull(card.printVariants.single { it.finish == "Reverse Holo" }.cardmarketProductId)
                val linked = matches(card)
                assertEquals(listOf(reviewedProduct), linked.map { it.productId })
                assertEquals("Holo", linked.single().finish)
                assertEquals(2.35, linked.single().trendPriceEur!!, 0.0)
            }
        }
    }

    @Test fun unlinkedGeneratedSiblingNeverInheritsTheOtherPrintingPrice() = runBlocking {
        val priced = printing("generated", "Normal", firstProduct, firstProduct)
        val unlinked = printing("generated", "Reverse")
        for (variants in listOf(listOf(priced, unlinked), listOf(unlinked, priced))) {
            val parsed = detail(source, variants)
            assertEquals(2, parsed.printVariants.size)
            val reverse = parsed.printVariants.single { it.finish == "Reverse Holo" }
            assertNull(reverse.cardmarketProductId)
            assertNull(visualCardmarketProductForPrinting(reverse, matches(parsed)))
            assertEquals(listOf(firstProduct), matches(parsed).map { it.productId })
        }
    }

    @Test fun aConflictingDuplicateStillBlocksThatDescriptorButNotItsDifferentFinishSibling() = runBlocking {
        val ordinary = printing("generated", "Holo")
        val conflict = printing("generated", "Holo", reviewedProduct, secondProduct)
        val reverse = printing("generated", "Reverse", firstProduct, firstProduct)
        for (variants in listOf(listOf(ordinary, conflict, reverse), listOf(reverse, conflict, ordinary))) {
            val parsed = detail(reviewed, variants)
            assertEquals(2, parsed.printVariants.size)
            assertNull(parsed.printVariants.single { it.finish == "Holo" }.cardmarketProductId)
            assertEquals(firstProduct, parsed.printVariants.single { it.finish == "Reverse Holo" }.cardmarketProductId)
            assertEquals(listOf(firstProduct), matches(parsed).map { it.productId })
        }
    }

    @Test fun reusedIdWithTheSameFinishStillKeepsEachExactPhysicalDescriptor() = runBlocking {
        val ordinary = printing("generated", "Holo")
        val specialForms = listOf(
            "Jumbo" to """{"variantId":"generated","type":"Holo","size":"Jumbo","thirdParty":{"cardmarket":$firstProduct}}""",
            "Standard · Untertyp: Gold" to """{"variantId":"generated","type":"Holo","size":"Standard","subtype":"Gold","thirdParty":{"cardmarket":$firstProduct}}""",
            "Standard · Stempel: Event" to """{"variantId":"generated","type":"Holo","size":"Standard","stamp":["Event"],"thirdParty":{"cardmarket":$firstProduct}}""",
            "Standard · Folie: Cosmos" to """{"variantId":"generated","type":"Holo","size":"Standard","foil":"Cosmos","thirdParty":{"cardmarket":$firstProduct}}""",
        )
        for ((descriptor, special) in specialForms) {
            for (variants in listOf(listOf(ordinary, special), listOf(special, ordinary))) {
                val parsed = detail(source, variants)
                assertEquals(2, parsed.printVariants.size)
                assertEquals(listOf("Holo", "Holo"), parsed.printVariants.map { it.finish })
                assertEquals(setOf("Standard", descriptor), parsed.printVariants.map { it.variant }.toSet())
                assertEquals("generated", parsed.printVariants.first().id)
                assertEquals(2, parsed.printVariants.map { it.id }.distinct().size)
                assertNull(parsed.printVariants.single { it.variant == "Standard" }.cardmarketProductId)
                assertEquals(firstProduct, parsed.printVariants.single { it.variant == descriptor }.cardmarketProductId)
                val candidates = parsed.printVariants.map { scannerCatalogResult(parsed, it, null) }
                assertEquals(2, candidates.map { it.productId }.distinct().size)
                assertEquals(2, candidates.map { it.scanIdentityKey() }.distinct().size)
                val linked = matches(parsed)
                assertEquals(listOf(firstProduct), linked.map { it.productId })
                assertEquals(descriptor, linked.single().variant)
                assertEquals(2.35, linked.single().trendPriceEur!!, 0.0)
                assertEquals(parsed.printVariants, detail(source, variants).printVariants)
            }
        }
    }

    private fun assertDistinctUnpricedIdentities(card: PokemonVisualCard) {
        val slots = card.binderPrintVariants()
        assertEquals(2, slots.size)
        assertEquals(2, slots.map { it.id }.distinct().size)
        val candidates = slots.map { scannerCatalogResult(card, it, null) }
        assertEquals(2, candidates.map { it.productId }.distinct().size)
        assertEquals(2, candidates.map { it.scanIdentityKey() }.distinct().size)
        assertEquals(2, candidates.map { it.scanSessionIdentityKey() }.distinct().size)
        candidates.forEach {
            assertFalse(it.hasMarketProduct)
            assertTrue(it.productId < 0)
            assertNull(it.trendPriceEur)
            assertEquals(card.id, it.sourceCardId)
            assertEquals(card.set.id, it.sourceSetId)
        }
        assertTrue(matches(card).isEmpty())
        card.printVariants.forEach { assertNull(it.cardmarketProductId) }
    }

    private suspend fun detail(
        card: PokemonVisualCard,
        variants: List<String>,
        rootFields: String = "",
        cache: PokemonCatalogMetadataCache = PokemonCatalogMetadataCache(),
    ): PokemonVisualCard {
        val expected = "https://api.tcgdex.net/v2/de/cards/${card.id}"
        val calls = mutableListOf<String>()
        val suffix = rootFields.takeIf { it.isNotBlank() }?.let { ",$it" }.orEmpty()
        val catalog = PokemonVisualCatalog(cache) { url ->
            calls += url
            check(url == expected) { "Unexpected fixture request: $url" }
            Buffer().writeUtf8("""{"id":"${card.id}"$suffix,"variants_detailed":[${variants.joinToString(",")}]}""")
        }
        val parsed = catalog.withDeclaredPrintVariants(card)
        assertEquals(listOf(expected), calls)
        assertEquals(card.id, parsed.id)
        assertEquals(card.set, parsed.set)
        return parsed
    }

    private fun printing(id: String, type: String, product: Long? = null, pricing: Long? = null): String {
        val thirdParty = product?.let { ",\"thirdParty\":{\"cardmarket\":$it}" }.orEmpty()
        val price = pricing?.let { ",\"pricing\":{\"cardmarket\":{\"idProduct\":$it,\"trend\":999}}" }.orEmpty()
        return """{"variantId":${quote(id)},"type":${quote(type)},"size":"Standard"$thirdParty$price}"""
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun products() = listOf(firstProduct, secondProduct, reviewedProduct).map { id ->
        CatalogSearchResult(productId = id, name = "Synthetic product $id", expansionId = "fixture",
            trendPriceEur = 2.35, currency = "EUR", holoTrendPriceEur = 4.75)
    }

    private fun matches(card: PokemonVisualCard) = visualCardmarketProducts(card, listOf(card), products())

    private fun card(setId: String, number: String, printedTotal: Int) = PokemonVisualCard(
        id = "$setId-$number", name = "Source fixture", number = number, rarity = null,
        smallImageUrl = "", largeImageUrl = "",
        set = PokemonSet(setId, "Source fixture set", "Test", printedTotal, 250, "", null, null),
    )
}
