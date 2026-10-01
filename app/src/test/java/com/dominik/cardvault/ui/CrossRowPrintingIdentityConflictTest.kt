package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonCatalogMetadataCache
import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.data.catalog.verifiedCardmarketProductId
import com.dominik.cardvault.model.CatalogSearchResult
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real detail parser -> maintained bridge -> actual printing linker, with local payloads.
 * Models the frozen snapshot's indistinguishable cross-row conflicts, not live card prices.
 * Includes individually consistent rows and declarations split across duplicate rows.
 */
class CrossRowPrintingIdentityConflictTest {
    private val source = card("cross-row-proof", "001")
    private val reviewed = card("30th", "151")
    private val firstId = 900_001L
    private val secondId = 900_002L
    private val reverseId = 900_003L
    private val stampedId = 900_004L
    private val reviewedId = 908_353L

    @Test fun internallyConsistentRowsWithConflictingProductsStayUnpricedInEitherOrder() = runBlocking {
        // Two ordinary products and three identically stamped products mirror the failure shapes.
        val cases = listOf(
            Triple("Standard", "", listOf(firstId, secondId)),
            Triple("Standard · Stempel: player-rewards-program", "\"stamp\":[\"player-rewards-program\"]",
                listOf(firstId, secondId, stampedId)),
        )
        for ((descriptor, extra, productIds) in cases) {
            val rows = productIds.map { printing("Holo", it, extra) }
            for (orderedRows in listOf(rows, rows.reversed())) {
                val parsed = detail(source, orderedRows)
                assertEquals(1, parsed.printVariants.size)
                assertEquals("duplicate", parsed.printVariants.single().id)
                assertEquals("duplicate", parsed.printVariants.single().sourceVariantId)
                assertEquals("Holo", parsed.printVariants.single().finish)
                assertEquals(descriptor, parsed.printVariants.single().variant)
                assertUnlinked(parsed, descriptor)
            }
        }
    }

    @Test fun crossRowConflictDoesNotSuppressDifferentFinishOrStampedSiblings() = runBlocking {
        val rows = listOf(
            printing("Holo", firstId),
            printing("Holo", secondId),
            printing("Reverse", reverseId),
            printing("Holo", stampedId, "\"stamp\":\"Event\""),
        )
        for (orderedRows in listOf(rows, rows.reversed())) {
            val parsed = detail(source, orderedRows)
            val linked = matches(parsed)
            assertEquals(3, parsed.printVariants.size)
            assertEquals(3, parsed.printVariants.map { it.id }.distinct().size)
            assertTrue(parsed.printVariants.all { it.sourceVariantId == "duplicate" })
            assertUnlinked(parsed, "Standard")

            val reverse = parsed.printVariants.single { it.finish == "Reverse Holo" }
            val stamped = parsed.printVariants.single { it.variant == "Standard · Stempel: Event" }
            assertEquals(reverseId, reverse.cardmarketProductId)
            assertEquals(stampedId, stamped.cardmarketProductId)
            assertEquals(setOf(reverseId, stampedId), linked.map { it.productId }.toSet())
            assertEquals(2, linked.size)
            assertEquals(4.75, linked.single { it.productId == reverseId }.trendPriceEur!!, 0.0)
            assertEquals(2.35, linked.single { it.productId == stampedId }.trendPriceEur!!, 0.0)
        }
    }

    @Test fun maintainedRegistryCannotRestoreCrossRowConflictAfterCachedReload() = runBlocking {
        assertEquals("The fixture must exercise a real maintained bridge", reviewedId,
            verifiedCardmarketProductId(reviewed.id))
        val rows = listOf(printing("Holo", reviewedId), printing("Holo", secondId),
            printing("Reverse", reverseId))
        for (orderedRows in listOf(rows, rows.reversed())) {
            val cache = PokemonCatalogMetadataCache()
            val parsed = detail(reviewed, orderedRows, cache)
            val unexpectedLoads = mutableListOf<String>()
            val cachedCatalog = PokemonVisualCatalog(cache) { url ->
                unexpectedLoads += url
                error("Cached detail must not reload or fall back to another language: $url")
            }
            val renamed = cachedCatalog.withDeclaredPrintVariants(reviewed.copy(name = "Current display name"))

            assertTrue(unexpectedLoads.isEmpty())
            assertEquals("Current display name", renamed.name)
            assertEquals(parsed.printVariants, renamed.printVariants)
            for (value in listOf(parsed, renamed)) {
                assertEquals(2, value.printVariants.size)
                assertUnlinked(value, "Standard")
                assertEquals(reverseId, value.printVariants.single { it.finish == "Reverse Holo" }.cardmarketProductId)
                assertEquals(listOf(reverseId), matches(value).map { it.productId })
            }
        }
    }

    @Test fun duplicatesAgreeingOnOneProductStillDeduplicateWithTheOriginalIdentity() = runBlocking {
        val row = printing("Holo", firstId)
        val parsed = detail(source, listOf(row, row, row))
        assertEquals(listOf(PokemonPrintVariant("duplicate", "Holo", "Standard", firstId)), parsed.printVariants)
        val linked = matches(parsed)
        assertEquals(listOf(firstId), linked.map { it.productId })
        assertEquals(2.35, linked.single().trendPriceEur!!, 0.0)
    }

    @Test fun aMissingDuplicateLinkCanUseTheOneUnambiguousDeclarationOfThatExactPrinting() = runBlocking {
        val missing = """{"variantId":"duplicate","type":"Holo","size":"Standard"}"""
        val declared = printing("Holo", firstId)
        for (rows in listOf(listOf(missing, declared), listOf(declared, missing))) {
            val parsed = detail(source, rows)
            assertEquals(listOf(PokemonPrintVariant("duplicate", "Holo", "Standard", firstId)), parsed.printVariants)
            assertEquals(listOf(firstId), matches(parsed).map { it.productId })
        }
        val allMissing = detail(source, listOf(missing, missing))
        assertNull(allMissing.printVariants.single().cardmarketProductId)
        assertTrue(matches(allMissing).isEmpty())
    }

    private fun assertUnlinked(card: PokemonVisualCard, descriptor: String) {
        // Run the actual linker even on RED; no assertion depends only on a fabricated projection.
        val linked = matches(card)
        val printing = card.printVariants.single { it.finish == "Holo" && it.variant == descriptor }
        assertNull("Conflicting cross-row evidence must stay unpriced: ${card.id} / $descriptor",
            printing.cardmarketProductId)
        assertNull(visualCardmarketProductForPrinting(printing, linked))
        assertTrue(linked.none { it.finish == "Holo" && it.variant == descriptor })
    }

    @Test fun splitDeclarationsStillExposeConflictsWithoutAcceptingPricingOnlyLinks() = runBlocking {
        val thirdPartyOnly = """{"variantId":"duplicate","type":"Holo","size":"Standard","thirdParty":{"cardmarket":$firstId}}"""
        val pricingOnly = """{"variantId":"duplicate","type":"Holo","size":"Standard","pricing":{"cardmarket":{"idProduct":$secondId}}}"""
        for (rows in listOf(listOf(thirdPartyOnly, pricingOnly), listOf(pricingOnly, thirdPartyOnly))) {
            assertUnlinked(detail(source, rows), "Standard")
        }
        assertUnlinked(detail(source, listOf(pricingOnly)), "Standard")
        val agreeingPricingOnly = pricingOnly.replace(secondId.toString(), firstId.toString())
        for (rows in listOf(listOf(thirdPartyOnly, agreeingPricingOnly), listOf(agreeingPricingOnly, thirdPartyOnly))) {
            assertEquals(firstId, detail(source, rows).printVariants.single().cardmarketProductId)
        }
    }

    private suspend fun detail(
        card: PokemonVisualCard,
        rows: List<String>,
        cache: PokemonCatalogMetadataCache = PokemonCatalogMetadataCache(),
    ): PokemonVisualCard {
        val expectedUrl = "https://api.tcgdex.net/v2/de/cards/${card.id}"
        val calls = mutableListOf<String>()
        val catalog = PokemonVisualCatalog(cache) { url ->
            calls += url
            check(url == expectedUrl) { "Unexpected local-fixture request: $url" }
            // A root-level product must not rescue an ambiguous printing either.
            Buffer().writeUtf8("""{"id":"${card.id}","thirdParty":{"cardmarket":$reverseId},
                "pricing":{"cardmarket":{"idProduct":$reverseId}},
                "variants_detailed":[${rows.joinToString(",")}]}""")
        }
        val parsed = catalog.withDeclaredPrintVariants(card)
        assertEquals(listOf(expectedUrl), calls)
        assertEquals(card.id, parsed.id)
        assertEquals(card.name, parsed.name)
        assertEquals(card.number, parsed.number)
        assertEquals(card.set, parsed.set)
        return parsed
    }

    private fun printing(type: String, productId: Long, extra: String = ""): String {
        val suffix = extra.takeIf(String::isNotBlank)?.let { ",$it" }.orEmpty()
        return """{"variantId":"duplicate","type":"$type","size":"Standard"$suffix,
            "thirdParty":{"cardmarket":$productId},"pricing":{"cardmarket":{"idProduct":$productId}}}"""
    }

    private fun matches(card: PokemonVisualCard) = visualCardmarketProducts(card, listOf(card),
        listOf(firstId, secondId, reverseId, stampedId, reviewedId).map { id ->
            CatalogSearchResult(id, "Synthetic product $id", "synthetic-expansion", 2.35, "EUR",
                holoTrendPriceEur = 4.75)
        })

    private fun card(setId: String, number: String) = PokemonVisualCard(
        "$setId-$number", "Synthetic source card", number, null, "", "",
        PokemonSet(setId, "Synthetic set", "Test", 128, 200, "", null, null),
    )
}
