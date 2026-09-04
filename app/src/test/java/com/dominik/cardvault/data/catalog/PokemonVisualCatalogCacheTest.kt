package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PokemonVisualCatalogCacheTest {
    @Test fun catalogueInstancesShareOnlyRequestedMetadataAndRefreshExplicitly() = runBlocking {
        val fixture = Fixture()
        val cache = PokemonCatalogMetadataCache()
        val first = PokemonVisualCatalog(cache, fixture::read)
        val second = PokemonVisualCatalog(cache, fixture::read)
        first.warmUp()
        assertEquals(2, fixture.requests.values.sumOf { it.get() })
        assertSame(first.sets(), second.sets(" "))
        val initial = first.findExact("sv08.5", "068/131")
        assertEquals(initial, second.findExact("PRE", "68/131"))
        assertEquals(6, fixture.requests.values.sumOf { it.get() })
        assertSame(first.cards(initial.first().set), second.cards(initial.first().set, " "))
        second.invalidate()
        assertEquals(initial, first.findExact("sv08.5", "068/131"))
        assertEquals(12, fixture.requests.values.sumOf { it.get() })
    }

    @Test fun exactIndexKeepsCollisionsPromoRulesAndSourceIds() = runBlocking {
        val fixture = Fixture()
        val catalogue = PokemonVisualCatalog(PokemonCatalogMetadataCache(), fixture::read)
        val ordinary = catalogue.findExact("sv08.5", "068/131")
        assertEquals(listOf("sv08.5-068", "sv08.5-68"), ordinary.map { it.id })
        assertEquals("Deutsch", ordinary.first().name)
        assertEquals("English", ordinary.first().marketSearchName)
        assertFalse(ordinary.last().hasGermanLocalizedName)
        assertEquals(listOf("svp-173"), catalogue.findExact("svp", "SVP173").map { it.id })
        assertTrue(catalogue.findExact("sv08.5", "SVP173").isEmpty())
    }

    @Test fun parallelLookupsCoalesceBothLocalesAndDetailRequests() = runBlocking {
        val fixture = Fixture()
        val bothLocales = CountDownLatch(2)
        val catalogue = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            if (url.endsWith("/sets/sv08.5")) {
                bothLocales.countDown()
                check(bothLocales.await(5, TimeUnit.SECONDS)) { "Locale requests did not overlap" }
            }
            fixture.read(url)
        }
        val results = (1..8).map { async { catalogue.findExact("sv08.5", "068/131") } }.awaitAll()
        assertTrue(results.all { it == results.first() })
        assertEquals(6, fixture.requests.values.sumOf { it.get() })
        assertTrue(fixture.requests.values.all { it.get() == 1 })
    }

    @Test fun cachedDetailsEnrichTheCurrentCallerWithoutRestoringOldIdentity() = runBlocking {
        var reads = 0
        val catalogue = PokemonVisualCatalog(PokemonCatalogMetadataCache()) {
            reads++
            Buffer().writeUtf8("""{"rarity":"Rare","variants_detailed":[{"variantId":"holo","type":"holo","size":"Standard"}]}""")
        }
        val original = card()
        catalogue.withDeclaredPrintVariants(original)
        val updated = original.copy(name = "Current name", marketSearchName = "Current English", largeImageUrl = "current-image")
        val detailed = catalogue.withDeclaredPrintVariants(updated)
        assertEquals("Current name", detailed.name)
        assertEquals("Current English", detailed.marketSearchName)
        assertEquals("current-image", detailed.largeImageUrl)
        assertEquals("Rare", detailed.rarity)
        assertEquals("holo", detailed.printVariants.single().id)
        assertEquals(1, reads)
    }

    @Test fun optionalLocaleFailureFallsBackButCancellationPropagates() = runBlocking {
        val fixture = Fixture()
        val set = card().set
        val unavailable = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            if (url.contains("/en/")) error("offline")
            fixture.read(url)
        }
        assertEquals(listOf("sv08.5-068", "sv08.5-069"), unavailable.cards(set).map { it.id })
        val cancelled = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            if (url.contains("/en/")) throw CancellationException("cancelled")
            fixture.read(url)
        }
        var cancellationObserved = false
        try {
            cancelled.cards(set)
        } catch (_: CancellationException) {
            cancellationObserved = true
        }
        assertTrue(cancellationObserved)
    }

    private fun card() = PokemonVisualCard(
        "sv08.5-068", "Original", "068", null, "", "",
        PokemonSet("sv08.5", "Prismatische Entwicklungen", "Deutsch · TCGdex", 131, 180, "", null, "PRE"),
    )

    private class Fixture {
        val requests = ConcurrentHashMap<String, AtomicInteger>()
        fun read(url: String): BufferedSource {
            requests.computeIfAbsent(url) { AtomicInteger() }.incrementAndGet()
            val json = when {
                url.endsWith("/sets") -> """[
                    {"id":"sv08.5","name":"Prismatische Entwicklungen","cardCount":{"official":131,"total":180}},
                    {"id":"svp","name":"Promos","cardCount":{"official":0,"total":300}}
                ]"""
                url.endsWith("/sets/svp") -> """{"cards":[{"id":"svp-173","name":"Promo","localId":"173"}]}"""
                url.endsWith("/de/sets/sv08.5") -> """{"cards":[
                    {"id":"sv08.5-068","name":"Deutsch","localId":"068"},
                    {"id":"sv08.5-069","name":"Andere Karte","localId":"069"}
                ]}"""
                url.endsWith("/en/sets/sv08.5") -> """{"cards":[
                    {"id":"sv08.5-068","name":"English","localId":"068"},
                    {"id":"sv08.5-68","name":"English Fallback","localId":"68"}
                ]}"""
                url.contains("/cards/") -> """{"rarity":"Rare","variants_detailed":[]}"""
                else -> error("Unexpected source request: $url")
            }
            return Buffer().writeUtf8(json)
        }
    }
}
