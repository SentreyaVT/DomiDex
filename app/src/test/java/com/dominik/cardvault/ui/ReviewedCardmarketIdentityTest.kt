package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonCatalogMetadataCache
import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.data.catalog.isQuarantinedCardmarketIdentity
import com.dominik.cardvault.model.CatalogSearchResult
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ReviewedCardmarketIdentityTest {
    @Test fun reviewedSourceContradictionsAreBlockedWithoutAReplacement() {
        assertTrue(isQuarantinedCardmarketIdentity("swsh11-201", 670816L))
        assertTrue(isQuarantinedCardmarketIdentity(" SV03-062 ", 781858L))
        assertFalse(isQuarantinedCardmarketIdentity("swsh11-130", 670816L))
        assertFalse(isQuarantinedCardmarketIdentity("sv03-057", 781858L))
        assertFalse(isQuarantinedCardmarketIdentity("sv03-062", 725142L))
        assertFalse(isQuarantinedCardmarketIdentity(null, 781858L))
        assertTrue(isQuarantinedCardmarketIdentity("TCGDEX-SWSH11-SWSH11-201-rainbow", 670816L))
        assertFalse(isQuarantinedCardmarketIdentity("swsh11-2010", 670816L))
    }

    @Test fun parserRetainsPrintingButRemovesOnlyRejectedIdIncludingCachedMetadata() = runBlocking {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { _ -> Buffer().writeUtf8("""{
          "variants_detailed":[
            {"variantId":"ordinary","type":"Holo","size":"Standard","thirdParty":{"cardmarket":725142}},
            {"variantId":"cosmos","type":"Holo","size":"Standard","foil":"Cosmos","thirdParty":{"cardmarket":781858}},
            {"variantId":"reverse","type":"Reverse","size":"Standard","thirdParty":{"cardmarket":725142}}
          ]}
        """) }
        repeat(2) {
            val result = catalog.withDeclaredPrintVariants(card("sv03-062", "Delfinator", 781858))
            assertEquals(3, result.printVariants.size)
            assertEquals(725142L, result.printVariants.first { it.id == "ordinary" }.cardmarketProductId)
            assertEquals(725142L, result.printVariants.first { it.id == "reverse" }.cardmarketProductId)
            assertNull(result.printVariants.first { it.id == "cosmos" }.cardmarketProductId)
            assertEquals("sv03-062", result.id)
        }
    }

    @Test fun linkerRejectsStaleUnfilteredObjectsButKeepsTheActualSiblingProduct() {
        val product = CatalogSearchResult(productId = 781858, name = "Frogadier [Strafe]", expansionId = "5385", imageUrl = null, trendPriceEur = 0.23, currency = "EUR")
        val rejected = card("sv03-062", "Delfinator", 781858)
        assertTrue(visualCardmarketProducts(rejected, listOf(rejected), listOf(product)).isEmpty())
        val correct = card("sv03-057", "Amphizel", 781858)
        assertEquals(0.23, visualCardmarketProducts(correct, listOf(correct), listOf(product)).single().trendPriceEur!!, 0.0)
    }

    private fun card(id: String, name: String, productId: Long) = PokemonVisualCard(
        id = id, name = name, number = id.substringAfterLast('-'), rarity = null,
        smallImageUrl = "", largeImageUrl = "",
        set = PokemonSet(id.substringBeforeLast('-'), "Testset", "Test", 197, 230, "", null, null),
        printVariants = listOf(PokemonPrintVariant("cosmos", "Holo", "Standard · Folie: Cosmos", productId)),
    )
}
