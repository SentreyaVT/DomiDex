package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.*
import com.dominik.cardvault.model.*
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class OwnedCardPriceResolverTest {
    private val source = PokemonVisualCard("sv08.5-137", "Freunde aus Paldea", "137", null, "", "",
        PokemonSet("sv08.5", "Prismatische Entwicklungen", "", 131, 180, "", null, null),
        listOf(PokemonPrintVariant("full", "Holo", "Standard", 805531)))
    private val owned = CollectionCard(1, "owned", source.name, source.set.name, "137/131",
        CardLanguage.GERMAN, "Holo", "Standard", "Near Mint", 1, 0.0, 0.0, null, "", null, null, 0,
        sourceSetId = source.set.id, sourceCardId = source.id)
    private val market = CatalogSearchResult(805531, "Friends in Paldea", "5944", 24.0, "EUR", holoTrendPriceEur = 3.0)
    private var detailRequests = 0
    private val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) {
        check(it.endsWith("/cards/sv08.5-137")) { "Unexpected catalogue lookup: $it" }
        detailRequests++
        Buffer().writeUtf8("""{"variants_detailed":[]}""")
    }
    private fun repository(products: List<CatalogSearchResult>) = Proxy.newProxyInstance(
        CollectionRepository::class.java.classLoader, arrayOf(CollectionRepository::class.java),
    ) { _, method, args ->
        check(method.name == "catalogProducts") { "No name search/write allowed: ${method.name}" }
        val ids = args!![0] as List<*>
        products.filter { it.productId in ids }
    } as CollectionRepository

    @Test fun ownedAboveMainCardRepairsByExactPrintingOutsideAnniversarySet() = runBlocking {
        val result = ownedCardPriceProducts(owned, repository(listOf(market)), catalog, listOf(source)).single()
        assertEquals(805531L, result.productId)
        assertEquals(24.0, result.trendPriceEur!!, .0)
        assertEquals(source.id, result.sourceCardId)
    }

    @Test fun existingExactProductNeverPerformsSourceOrNameLookupAndUsesCorrectPriceField() = runBlocking {
        val linked = owned.copy(externalProductId = 805531, finish = "Reverse Holo", variant = "Reverse Holo")
        val result = ownedCardPriceProducts(linked, repository(listOf(market)), catalog, emptyList()).single()
        assertEquals(3.0, result.trendPriceEur!!, .0)
        assertEquals(0, detailRequests)
    }

    @Test fun missingExactProductDoesNotBorrowSameNameOrOtherPrinting() = runBlocking {
        assertTrue(ownedCardPriceProducts(owned.copy(externalProductId = 42), repository(listOf(market)), catalog, emptyList()).isEmpty())
        assertTrue(ownedCardPriceProducts(owned.copy(sourceSetId = null), repository(emptyList()), catalog, emptyList()).isEmpty())
    }

    @Test fun quarantinedStoredProductCannotResurfaceThroughOwnedCardPreview() = runBlocking {
        val wrong = owned.copy(sourceCardId = "swsh11-201", sourceSetId = "swsh11", externalProductId = 670816)
        assertTrue(ownedCardPriceProducts(wrong, repository(emptyList()), catalog, emptyList()).isEmpty())
    }

    @Test fun differenceWarningRequiresBothRelativeAndAbsoluteDifference() {
        fun option(amount: Long) = CardPriceAlternative(CardPriceMetric.NORMAL_LOW, 42, amount, "EUR", 1_000)
        assertFalse(alternativesDifferMaterially(listOf(option(10), option(30))))
        assertFalse(alternativesDifferMaterially(listOf(option(1_000), option(1_200))))
        assertTrue(alternativesDifferMaterially(listOf(option(200), option(532))))
    }
}
