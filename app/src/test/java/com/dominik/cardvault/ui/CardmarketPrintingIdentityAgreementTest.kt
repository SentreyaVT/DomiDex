package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonCatalogMetadataCache
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.model.CatalogSearchResult
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Actual detail parser -> registry attachment -> actual printing linker, without network or Room.
 * Synthetic prices only expose an unsafe identity becoming priceable; they are not market evidence.
 * Pricing-only identity acceptance remains a separate policy question: these tests do not enable it.
 */
class CardmarketPrintingIdentityAgreementTest {
    private val source = visualCard("identity-proof", "001", 1)
    private val reviewed = visualCard("30th", "151", 128)
    private val firstId = 900_001L
    private val secondId = 900_002L
    private val reviewedProductId = 908_353L // Existing maintained identity for 30th-151.

    @Test fun matchingPrintingDeclarationsKeepTheExactProductAndLocalGuidePrice() = runBlocking {
        val detailed = detail(
            source,
            """"thirdParty":{"cardmarket":$firstId},"pricing":{"cardmarket":{"idProduct":$firstId,"trend":999.0}}""",
            rootFields = """"thirdParty":{"cardmarket":$secondId},"pricing":{"cardmarket":{"idProduct":$secondId}}""",
        )

        assertLinked(detailed, firstId)
    }

    @Test fun thirdPartyOnlyPrintingDeclarationStillLinksWithoutRootInheritance() = runBlocking {
        val detailed = detail(
            source,
            """"thirdParty":{"cardmarket":$firstId}""",
            rootFields = """"thirdParty":{"cardmarket":$secondId},"pricing":{"cardmarket":{"idProduct":$secondId}}""",
        )

        assertLinked(detailed, firstId)
    }

    @Test fun rootOnlyDeclarationsCannotCreateAPrintingLink() = runBlocking {
        val detailed = detail(
            source,
            rootFields = """"thirdParty":{"cardmarket":$firstId},"pricing":{"cardmarket":{"idProduct":$firstId,"trend":999.0}}""",
        )

        assertUnlinked(detailed)
    }

    @Test fun absentPrintingDeclarationsStillAllowTheExistingReviewedRegistryBridge() = runBlocking {
        val detailed = detail(reviewed)

        assertLinked(detailed, reviewedProductId)
    }

    @Test fun contradictoryPrintingDeclarationsCannotLinkEitherPricedCandidate() = runBlocking {
        val detailed = detail(
            source,
            """"thirdParty":{"cardmarket":$firstId},"pricing":{"cardmarket":{"idProduct":$secondId,"trend":999.0}}""",
        )

        assertUnlinked(detailed)
    }

    @Test fun exactDecimalAndExponentIdsAreComparedWithoutImportingProviderPrices() = runBlocking {
        for (sameId in listOf("900001.0", "9.00001e5")) {
            assertLinked(detail(source,
                """"thirdParty":{"cardmarket":$firstId},"pricing":{"cardmarket":{"idProduct":$sameId,"trend":999.0}}""",
            ), firstId)
        }
        for (otherId in listOf("900002.0", "9.00002e5")) {
            assertUnlinked(detail(source,
                """"thirdParty":{"cardmarket":$firstId},"pricing":{"cardmarket":{"idProduct":$otherId,"trend":999.0}}""",
            ))
        }
    }

    @Test fun invalidOptionalPricingMetadataDoesNotTriggerAnEnglishFallbackOrChangeExistingLinkPolicy() = runBlocking {
        for (pricing in listOf(
            "null", "[]", "\"invalid\"", "{}", """{"cardmarket":null}""", """{"cardmarket":[]}""",
            """{"cardmarket":{"idProduct":null}}""", """{"cardmarket":{"idProduct":"900002"}}""",
            """{"cardmarket":{"idProduct":0}}""", """{"cardmarket":{"idProduct":-1}}""",
            """{"cardmarket":{"idProduct":900002.5}}""", """{"cardmarket":{"idProduct":9223372036854775808}}""",
        )) {
            assertLinked(detail(source, """"thirdParty":{"cardmarket":$firstId},"pricing":$pricing"""), firstId)
        }
    }

    @Test fun contradictoryPrintingDeclarationsCannotBeRepairedByTheReviewedRegistry() = runBlocking {
        // Check both directions: one declared ID equals the real maintained bridge.
        // Merely setting a parser conflict to null must not restore that bridge downstream.
        for ((declared, priced) in listOf(reviewedProductId to secondId, secondId to reviewedProductId)) {
            val detailed = detail(
                reviewed,
                """"thirdParty":{"cardmarket":$declared},"pricing":{"cardmarket":{"idProduct":$priced,"trend":999.0}}""",
            )

            assertUnlinked(detailed)
        }
    }

    @Test fun cachedContradictionSurvivesRenamingAndReviewedRegistryReattachment() = runBlocking {
        val cache = PokemonCatalogMetadataCache()
        val calls = mutableListOf<String>()
        val expectedUrl = "https://api.tcgdex.net/v2/de/cards/${reviewed.id}"
        val firstCatalog = PokemonVisualCatalog(cache) { url ->
            calls += url
            check(url == expectedUrl) { "Unexpected fixture request: $url" }
            Buffer().writeUtf8("""{
                "id":"${reviewed.id}",
                "variants_detailed":[{"variantId":"ordinary","type":"Holo","size":"Standard",
                    "thirdParty":{"cardmarket":$reviewedProductId},
                    "pricing":{"cardmarket":{"idProduct":$secondId,"trend":999.0}}}]
            }""")
        }
        val first = firstCatalog.withDeclaredPrintVariants(reviewed)
        val renamedInput = reviewed.copy(name = "Renamed current display name")
        val secondCatalog = PokemonVisualCatalog(cache) { url ->
            calls += url
            error("A reused detail cache must not reload or try another language: $url")
        }
        val renamed = secondCatalog.withDeclaredPrintVariants(renamedInput)

        assertEquals(listOf(expectedUrl), calls)
        assertEquals(reviewed.name, first.name)
        assertEquals(renamedInput.name, renamed.name)
        assertEquals(reviewed.id, renamed.id)
        assertEquals(reviewed.number, renamed.number)
        assertEquals(reviewed.set, renamed.set)
        assertEquals(first.printVariants, renamed.printVariants)
        assertUnlinked(first)
        assertUnlinked(renamed)
    }

    @Test fun conflictingPrintingDoesNotSuppressItsValidSiblingInEitherSourceOrder() = runBlocking {
        val conflict = """{"variantId":"ordinary","type":"Holo","size":"Standard",
            "thirdParty":{"cardmarket":$reviewedProductId},
            "pricing":{"cardmarket":{"idProduct":$secondId,"trend":999.0}}}"""
        val valid = """{"variantId":"reverse","type":"Reverse","size":"Standard",
            "thirdParty":{"cardmarket":$firstId},
            "pricing":{"cardmarket":{"idProduct":$firstId,"trend":999.0}}}"""
        for (variants in listOf(listOf(conflict, valid), listOf(valid, conflict))) {
            val calls = mutableListOf<String>()
            val expectedUrl = "https://api.tcgdex.net/v2/de/cards/${reviewed.id}"
            val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
                calls += url
                check(url == expectedUrl) { "Unexpected fixture request: $url" }
                Buffer().writeUtf8("""{"id":"${reviewed.id}","variants_detailed":[${variants.joinToString(",")}]}""")
            }
            val detailed = catalog.withDeclaredPrintVariants(reviewed)
            val matches = visualCardmarketProducts(detailed, listOf(detailed), candidates())

            assertEquals(listOf(expectedUrl), calls)
            assertEquals(2, detailed.printVariants.size)
            assertEquals(firstId, detailed.printVariants.single { it.id == "reverse" }.cardmarketProductId)
            assertEquals(listOf(firstId), matches.map { it.productId })
            assertEquals(reviewed.id, matches.single().sourceCardId)
            assertEquals(reviewed.set.id, matches.single().sourceSetId)
            assertEquals(reviewed.number, matches.single().collectorNumber)
            assertEquals("Reverse Holo", matches.single().finish)
            assertEquals("Standard", matches.single().variant)
            assertEquals(4.75, matches.single().trendPriceEur!!, 0.0)
            assertNull(detailed.printVariants.single { it.id == "ordinary" }.cardmarketProductId)
        }
    }

    private suspend fun detail(
        card: PokemonVisualCard,
        printingFields: String = "",
        rootFields: String = "",
    ): PokemonVisualCard {
        val calls = mutableListOf<String>()
        val expectedUrl = "https://api.tcgdex.net/v2/de/cards/${card.id}"
        val rootSuffix = rootFields.takeIf(String::isNotBlank)?.let { ",$it" }.orEmpty()
        val printingSuffix = printingFields.takeIf(String::isNotBlank)?.let { ",$it" }.orEmpty()
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            calls += url
            check(url == expectedUrl) { "Unexpected fixture request: $url" }
            Buffer().writeUtf8("""{
                "id":"${card.id}"$rootSuffix,
                "variants_detailed":[{"variantId":"ordinary","type":"Holo","size":"Standard"$printingSuffix}]
            }""")
        }

        val result = catalog.withDeclaredPrintVariants(card)

        assertEquals(listOf(expectedUrl), calls)
        assertEquals(card.id, result.id)
        assertEquals(card.name, result.name)
        assertEquals(card.number, result.number)
        assertEquals(card.set, result.set)
        assertEquals(1, result.printVariants.size)
        assertEquals("ordinary", result.printVariants.single().id)
        assertEquals("Holo", result.printVariants.single().finish)
        assertEquals("Standard", result.printVariants.single().variant)
        return result
    }

    private fun candidates() = listOf(firstId, secondId, reviewedProductId).map { id ->
        CatalogSearchResult(
            productId = id,
            name = "Synthetic priced candidate $id",
            expansionId = "synthetic-expansion",
            trendPriceEur = 2.35,
            currency = "EUR",
            holoTrendPriceEur = 4.75,
        )
    }

    private fun assertLinked(card: PokemonVisualCard, productId: Long) {
        val matches = visualCardmarketProducts(card, listOf(card), candidates())
        assertEquals(productId, card.printVariants.single().cardmarketProductId)
        assertEquals(listOf(productId), matches.map { it.productId })
        assertEquals(card.id, matches.single().sourceCardId)
        assertEquals(card.set.id, matches.single().sourceSetId)
        assertEquals(card.number, matches.single().collectorNumber)
        assertEquals("Holo", matches.single().finish)
        assertEquals("Standard", matches.single().variant)
        assertEquals(2.35, matches.single().trendPriceEur!!, 0.0)
    }

    private fun assertUnlinked(card: PokemonVisualCard) {
        // Exercise the real linker even while the parser assertion is intentionally RED.
        val matches = visualCardmarketProducts(card, listOf(card), candidates())
        assertNull("Conflicting or root-only evidence must stay unlinked: ${card.id}", card.printVariants.single().cardmarketProductId)
        assertTrue("No priced candidate may bypass the printing conflict: ${card.id}", matches.isEmpty())
    }

    private fun visualCard(setId: String, number: String, printedTotal: Int) = PokemonVisualCard(
        id = "$setId-$number",
        name = "Synthetic source card",
        number = number,
        rarity = null,
        smallImageUrl = "",
        largeImageUrl = "",
        set = PokemonSet(setId, "Synthetic source set", "Test", printedTotal, 158, "", null, null),
    )
}
