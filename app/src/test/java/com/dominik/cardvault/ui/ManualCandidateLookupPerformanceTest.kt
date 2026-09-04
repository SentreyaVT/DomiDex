package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonCatalogMetadataCache
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionRepository
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ManualCandidateLookupPerformanceTest {
    @Test fun emptyPriceCatalogueSearchesOnlyTheExactCandidateName() = runBlocking {
        val fixture = Fixture(emptyMap())
        val result = fixture.resolve()
        assertEquals(fixture.referenceResult(), result)
        assertEquals(listOf("Alpha"), fixture.queries)
        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates.all { !it.card.hasMarketProduct })
        assertEquals(ScannerDecision.AMBIGUOUS, result.decision.decision)
    }

    @Test fun substringOnlyCandidateSkipsAnOtherwiseResolvableExpansion() = runBlocking {
        val products = names.associateWith { name -> listOf(product(name, "44")) }.toMutableMap()
        products["Alpha"] = listOf(product("Alpha unrelated", "44"))
        val fixture = Fixture(products)
        val result = fixture.resolve()
        assertEquals(fixture.referenceResult(), result)
        assertEquals(listOf("Alpha"), fixture.queries)
        assertTrue(result.candidates.all { !it.card.hasMarketProduct })
    }

    @Test fun compatibleCandidateRetainsFullExpansionEvidenceAndExactPrices() = runBlocking {
        val fixture = Fixture(names.associateWith { name -> listOf(product(name, "44")) })
        val result = fixture.resolve()
        assertEquals(fixture.referenceResult(), result)
        assertEquals(names.toSet(), fixture.queries.toSet())
        assertEquals(names.size, fixture.queries.size)
        assertTrue(result.candidates.all { it.card.hasMarketProduct })
        assertEquals(listOf(1.25, 3.5), result.candidates.map { it.card.trendPriceEur })
    }

    @Test fun runnerUpExpansionEvidenceStillPreventsAmbiguousPriceLinking() = runBlocking {
        val fixture = Fixture(names.associateWith { name -> listOf(product(name, "44"), product(name, "45")) })
        val result = fixture.resolve()
        assertEquals(fixture.referenceResult(), result)
        assertEquals(names.size, fixture.queries.size)
        assertFalse(result.candidates.any { it.card.hasMarketProduct })
    }

    private class Fixture(private val products: Map<String, List<CatalogSearchResult>>) {
        val queries = mutableListOf<String>()
        private val analysis = withManualScanIdentity(null, "sv08.5", "PRE", "001/004")
        private val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            Buffer().writeUtf8(when {
                url.endsWith("/sets") -> """[{"id":"sv08.5","name":"Prismatische Entwicklungen","abbreviation":{"official":"PRE"},"cardCount":{"official":4,"total":4}}]"""
                url.contains("/sets/sv08.5") -> names.mapIndexed { index, name ->
                    val number = (index + 1).toString().padStart(3, '0')
                    """{"id":"sv08.5-$number","name":"$name","localId":"$number"}"""
                }.joinToString(prefix = "{\"cards\":[", postfix = "]}")
                url.contains("/cards/") -> """{"variants_detailed":[
                    {"variantId":"normal","type":"normal","size":"Standard"},
                    {"variantId":"holo","type":"holo","size":"Holo"}
                ]}"""
                else -> error("Unexpected request: $url")
            })
        }

        @Suppress("UNCHECKED_CAST")
        private val repository = Proxy.newProxyInstance(
            CollectionRepository::class.java.classLoader,
            arrayOf(CollectionRepository::class.java),
        ) { _, method, arguments ->
            check(method.name == "searchCatalog") { "Unexpected repository operation: ${method.name}" }
            val query = arguments!![0] as String
            assertEquals(500, arguments[1])
            queries += query
            products[query].orEmpty()
        } as CollectionRepository

        suspend fun resolve(): ScanCandidateResolution = CardScanCandidateResolver.resolve(analysis, repository, catalog)

        /** The previous ordering always gathered every set name before checking the selected card. */
        suspend fun referenceResult(): ScanCandidateResolution {
            val exactCard = catalog.findExact("sv08.5", "001/004").single()
            val allCards = catalog.cards(exactCard.set)
            val allSearches = allCards.map { it.marketSearchName ?: it.name }.distinct()
                .associateWith { products[it].orEmpty() }
            val expansion = resolveCardmarketExpansionId(allCards, allSearches)
            val matched = visualCardmarketProducts(exactCard, allCards, allSearches["Alpha"].orEmpty(), expansion).firstOrNull()
            val candidates = exactCard.printVariants.map { variant ->
                val score = CardRecognitionRanker.score(analysis, scannerCatalogResult(exactCard, variant, null))
                ScanCandidate(scannerCatalogResult(exactCard, variant, matched), score)
            }
            return ScanCandidateResolution(
                candidates,
                constrainManualWholeImageDecision(analysis, ScannerDecisionPolicy.decide(candidates.map { it.score })),
            )
        }
    }

    private companion object {
        val names = listOf("Alpha", "Beta", "Gamma", "Delta")
        fun product(name: String, expansion: String) = CatalogSearchResult(
            productId = ("$name-$expansion".hashCode().toLong() and 0x7fff_ffffL) + 1,
            name = name,
            expansionId = expansion,
            trendPriceEur = 1.25,
            currency = "EUR",
            holoTrendPriceEur = 3.5,
        )
    }
}
