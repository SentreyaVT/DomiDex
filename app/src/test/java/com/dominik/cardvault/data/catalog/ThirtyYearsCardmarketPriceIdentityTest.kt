package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ThirtyYearsCardmarketPriceIdentityTest {
    private val main = PokemonSet("30th", "30 Jahre", "Mega-Entwicklung", 128, 161, "", null, "30C")

    @Test fun allReviewedAnniversaryMappingsRemainUniqueAfterDetailEnrichment() = runBlocking {
        val ids = (1..158).map { "30th-${it.toString().padStart(3, '0')}" } +
            (1..30).map { "30th-c-${it.toString().padStart(3, '0')}" }
        val mapped = ids.mapNotNull { id -> verifiedCardmarketProductId(id)?.let { id to it } }
        assertEquals(158, mapped.count { it.first.startsWith("30th-") && !it.first.startsWith("30th-c-") })
        assertEquals(30, mapped.count { it.first.startsWith("30th-c-") })
        assertEquals(188, mapped.size)
        assertEquals(188, mapped.map { it.second }.distinct().size)
        for (size in listOf("Standard", "standard")) {
            val catalog = fixture(size)
            for ((id, product) in mapped) {
                assertEquals(id, product, catalog.withDeclaredPrintVariants(card(id)).printVariants.single().cardmarketProductId)
            }
        }
    }

    @Test fun reviewedNewSetIdentitiesSurviveGermanAndEnglishGeneratedDetails() = runBlocking {
        // Numeric IDs are reviewed catalog bridges, not a sequence extrapolated from collector numbers.
        val expected = mapOf(
            "30th-001" to 907607L, "30th-010" to 907736L, "30th-023" to 907630L,
            "30th-065" to 907672L, "30th-124" to 907731L,
            "30th-c-001" to 907940L, "30th-c-017" to 907961L, "30th-c-030" to 907968L,
        )
        for (size in listOf("Standard", "standard")) {
            val catalog = fixture(size)
            for ((id, productId) in expected) {
                val card = card(id)
                repeat(2) { // The second read exercises cached details as well.
                    val detailed = catalog.withDeclaredPrintVariants(card)
                    assertEquals("$id / $size", productId, detailed.printVariants.single().cardmarketProductId)
                    assertEquals(card.id, detailed.id)
                    assertEquals(card.set.id, detailed.set.id)
                    assertEquals(size, detailed.printVariants.single().variant)
                    assertEquals("Normal", detailed.printVariants.single().finish)
                }
            }
        }
    }

    @Test fun englishFallbackRetainsReviewedIdentityWithoutChangingGermanName() = runBlocking {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            if (url.contains("/de/")) throw java.io.IOException("German detail unavailable")
            Buffer().writeUtf8("""{"variants_detailed":[{"variantId":"generated","type":"normal","size":"standard"}]}""")
        }
        val result = catalog.withDeclaredPrintVariants(card("30th-065").copy(name = "Mew"))
        assertEquals(907672L, result.printVariants.single().cardmarketProductId)
        assertEquals("Mew", result.name)
    }

    @Test fun regularBridgeNeverLeaksIntoStampedFoilOrSubtypePrintings() = runBlocking {
        for (extra in listOf("\"stamp\":\"Pokemon Center\"", "\"foil\":\"Cosmos\"", "\"subtype\":\"First Edition\"")) {
            for (id in listOf("30th-065", "30th-151") + remainingClassic.keys) {
                val result = fixture("standard", extra).withDeclaredPrintVariants(card(id))
                assertNull(id, result.printVariants.single().cardmarketProductId)
            }
        }
    }

    @Test fun numberedBaseSpecialIllustrationAndFuturisticCardsKeepDistinctProducts() = runBlocking {
        val expected = mapOf(
            "30th-064" to 907671L, "30th-151" to 908353L, "30th-157" to 907761L,
            "30th-066" to 907673L, "30th-152" to 908354L, "30th-158" to 907762L,
            "30th-112" to 907719L, "30th-143" to 908352L, "30th-144" to 907751L,
        )
        for (size in listOf("Standard", "standard")) {
            val catalog = fixture(size)
            for ((id, productId) in expected) {
                val result = catalog.withDeclaredPrintVariants(card(id))
                assertEquals(id, productId, result.printVariants.single().cardmarketProductId)
            }
        }
        assertEquals(expected.size, expected.values.distinct().size)
    }

    @Test fun newSarBridgePreservesDeclaredReverseAndExplicitPrintingIdentity() = runBlocking {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { _ ->
            Buffer().writeUtf8("""{"variants_detailed":[
                {"variantId":"ordinary","type":"normal","size":"standard"},
                {"variantId":"source-reverse","type":"reverse","size":"standard","thirdParty":{"cardmarket":123456}},
                {"variantId":"source-stamped","type":"holo","size":"standard","stamp":"Pokemon Center","thirdParty":{"cardmarket":234567}}
            ]}""")
        }
        val result = catalog.withDeclaredPrintVariants(card("30th-151")).printVariants
        assertEquals(908353L, result.single { it.id == "ordinary" }.cardmarketProductId)
        val reverse = result.single { it.id == "source-reverse" }
        assertEquals("Reverse Holo", reverse.finish)
        assertEquals(123456L, reverse.cardmarketProductId)
        val stamped = result.single { it.id == "source-stamped" }
        assertTrue(stamped.variant.contains("Stempel: Pokemon Center"))
        assertEquals(234567L, stamped.cardmarketProductId)
    }

    @Test fun exactUpstreamProductTakesPrecedenceAndUnknownPrintingsStayUnlinked() = runBlocking {
        val explicit = fixture("standard", "\"thirdParty\":{\"cardmarket\":123456}")
            .withDeclaredPrintVariants(card("30th-065"))
        assertEquals(123456L, explicit.printVariants.single().cardmarketProductId)
        // No extrapolated Classic slot or unrelated-set identity can borrow a namesake.
        for (id in listOf("30th-c-031", "sv08-065")) {
            assertNull(id, verifiedCardmarketProductId(id))
        }
    }

    @Test fun sevenNumberVerifiedClassicBridgesPreserveHistoricCollectorsAndExplicitLinks() = runBlocking {
        for (size in listOf("Standard", "standard")) {
            val catalog = fixture(size)
            for ((id, expected) in remainingClassic) {
                val printedCollector = classic30thPrintedCollectorForLocalId(id.substringAfterLast('-'))!!
                val original = card(id).copy(number = printedCollector, sourceLocalId = id.substringAfterLast('-'))
                val detailed = catalog.withDeclaredPrintVariants(original)
                assertEquals(id, expected, detailed.printVariants.single().cardmarketProductId)
                assertEquals(printedCollector, detailed.number)
                assertEquals(original.sourceLocalId, detailed.sourceLocalId)
                assertEquals("30th-c", detailed.set.id)
                assertEquals("6601", detailed.set.cardmarketExpansionId)
                assertEquals(size, detailed.printVariants.single().variant)
                val explicit = fixture(size, "\"thirdParty\":{\"cardmarket\":123456}")
                    .withDeclaredPrintVariants(original)
                assertEquals(123456L, explicit.printVariants.single().cardmarketProductId)
            }
        }
        assertEquals(7, remainingClassic.values.distinct().size)
        assertNotEquals(remainingClassic.getValue("30th-c-019"), remainingClassic.getValue("30th-c-020"))
    }

    private val remainingClassic = mapOf(
        "30th-c-002" to 907946L, "30th-c-005" to 907941L,
        "30th-c-018" to 907953L, "30th-c-019" to 907954L,
        "30th-c-020" to 907955L, "30th-c-023" to 907959L,
        "30th-c-025" to 907948L,
    )

    private fun fixture(size: String, extra: String = "") = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { _ ->
        Buffer().writeUtf8("""{"variants_detailed":[{"variantId":"generated","type":"normal","size":"$size"${if (extra.isBlank()) "" else ",$extra"}}]}""")
    }

    private fun card(id: String) = PokemonVisualCard(
        id, "Testkarte", id.substringAfterLast('-'), null, "", "",
        if (id.startsWith("30th-c-")) main.copy(id = "30th-c", printedTotal = 0, total = 30) else main,
    )
}
