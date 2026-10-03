package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class PokemonVisualCatalogSearchTest {
    @Test fun newlyAuditedMissingPromoNeedsNoEnglishImageLookupAndKeepsGermanSearchIdentity() = runBlocking {
        val fixture = Fixture { url -> when (url) {
            searchUrl("Meganie") -> "[${brief("mep-001", "Meganie", "001", null)}]"
            else -> error("Audited image must not add a metadata request: $url")
        } }
        val card = fixture.catalog.searchCards("Meganie").cards.single()
        assertEquals("Meganie", card.name)
        assertEquals("mep-001", card.id)
        assertEquals("mep", card.set.id)
        assertEquals("001", card.sourceLocalId)
        assertEquals("001", card.number)
        assertEquals("https://limitlesstcg.nyc3.cdn.digitaloceanspaces.com/tpci/MEP/MEP_001_R_DE_SM.png",
            card.smallImageUrl)
        assertTrue(card.largeImageUrl.isNotBlank())
        assertTrue(card.hasGermanLocalizedName)
        assertNull(card.marketSearchName)
        assertTrue(fixture.requests.none { it.contains("/en/") || it.contains("/cards/") })
        assertSame(card, fixture.catalog.searchCards("Meganie").cards.single())
    }

    @Test fun germanNamesImagesAndSourceCollectorIdentityStayTogether() = runBlocking {
        val fixture = Fixture { url -> when (url) {
            searchUrl("Glurak") -> "[${brief("base1-4", "Glurak", "4")}]"
            searchUrl("Bisaflor") -> "[${brief("base1-15", "Bisaflor", "15")}]"
            else -> error("Unexpected request: $url")
        } }
        val glurak = fixture.catalog.searchCards("Glurak").cards.single()
        val bisaflor = fixture.catalog.searchCards("Bisaflor").cards.single()
        assertEquals("Glurak", glurak.name)
        assertEquals("Bisaflor", bisaflor.name)
        assertEquals("4", glurak.number)
        assertEquals("15", bisaflor.sourceLocalId)
        assertEquals("base1", glurak.set.id)
        assertEquals("Basis", glurak.set.name)
        assertEquals(102, glurak.set.printedTotal)
        assertEquals("https://images.example/base1-4/low.webp", glurak.smallImageUrl)
        assertEquals("https://images.example/base1-15/high.webp", bisaflor.largeImageUrl)
        assertTrue(glurak.hasGermanLocalizedName && bisaflor.hasGermanLocalizedName)
        assertNull(glurak.marketSearchName)
        assertTrue(fixture.requests.none { it.contains("/en/") || it.contains("/cards/") || it.contains("/sets/") })
    }

    @Test fun secretsHyphenatedClassicAndPromosKeepTheirExactSet() = runBlocking {
        val fixture = Fixture { "[" + listOf(
            brief("sv03.5-199", "Glurak-ex", "199"),
            brief("30th-c-001", "Glurak", "001", null),
            brief("mep-001", "Glurak Promo", "001"),
        ).joinToString() + "]" }
        val cards = fixture.catalog.searchCards("Glurak").cards
        assertEquals(listOf("sv03.5", "30th-c", "mep"), cards.map { it.set.id })
        assertEquals("199", cards[0].number)
        assertEquals(165, cards[0].set.printedTotal) // secret is above the printed denominator
        assertEquals("4/102", cards[1].number)
        assertEquals("001", cards[1].sourceLocalId)
        assertEquals("https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/30TH-C/30TH-C_DE_1.png", cards[1].smallImageUrl)
        assertEquals("001", cards[2].number)
        assertTrue(fixture.requests.none { it.contains("/en/") })
    }

    @Test fun unknownInconsistentAndPocketIdentitiesAreExcludedWithoutPrefixGuessing() = runBlocking {
        val fixture = Fixture { "[" + listOf(
            brief("unknown-4", "Glurak", "4"),
            brief("base1-4", "Glurak", "5"),
            brief("30th-c-001", "Glurak", "c-001"), // must not resolve as 30th
            brief("A1-036", "Glurak", "036"), // index has no serie: membership still excludes it
            brief("digital-1", "Glurak", "1"), // explicitly declared tcgp is excluded too
            brief("base1-15", "Bisaflor", "15"),
        ).joinToString() + "]" }
        assertEquals(listOf("base1-15"), fixture.catalog.searchCards("Glurak").cards.map { it.id })
    }

    @Test fun filteredFullPageStillOffersNextPageAndDoesNotDropRawContinuation() = runBlocking {
        val fixture = Fixture { url -> when (url) {
            searchUrl("Glurak", 1) -> (1..40).joinToString(prefix = "[", postfix = "]") {
                brief("unknown-$it", "Glurak", "$it")
            }
            searchUrl("Glurak", 2) -> "[${brief("base1-4", "Glurak", "4")}]"
            else -> error("Unexpected request: $url")
        } }
        val first = fixture.catalog.searchCards("Glurak")
        assertTrue(first.cards.isEmpty())
        assertTrue(first.hasMore)
        val second = fixture.catalog.searchCards("Glurak", 2)
        assertEquals("base1-4", second.cards.single().id)
        assertFalse(second.hasMore)
    }

    @Test fun trimmedSearchIsCachedByPageAndInvalidationClearsBothSmallIndexes() = runBlocking {
        val fixture = Fixture { "[${brief("base1-4", "Glurak", "4")}]" }
        val first = fixture.catalog.searchCards("  Glurak  ")
        assertSame(first, fixture.catalog.searchCards("Glurak"))
        fixture.catalog.searchCards("Glurak", 2)
        assertEquals(4, fixture.requests.size) // two small indexes + two pages
        fixture.catalog.invalidate()
        fixture.catalog.searchCards("Glurak")
        assertEquals(7, fixture.requests.size)
        assertEquals(2, fixture.requests.count { it == "$DE/series/tcgp" })
    }

    @Test fun searchCacheRetainsOnlyTwentyFourPages() = runBlocking {
        val fixture = Fixture { "[]" }
        (1..25).forEach { fixture.catalog.searchCards("Glurak", it) }
        fixture.catalog.searchCards("Glurak", 2) // retained
        assertEquals(27, fixture.requests.size)
        fixture.catalog.searchCards("Glurak", 1) // oldest entry was evicted
        assertEquals(28, fixture.requests.size)
    }

    @Test fun nameFilterIsEncodedAsOneValueAndBlankOrInvalidInputsDoNotRead() = runBlocking {
        val expected = "$DE/cards?name=like%3ANidoran%E2%99%80%20%26%20Mew%2B%2F%3F%25&pagination:page=1&pagination:itemsPerPage=40"
        val fixture = Fixture { url -> assertEquals(expected, url); "[]" }
        assertEquals(PokemonCardSearchPage(emptyList(), false), fixture.catalog.searchCards("  "))
        assertEquals(PokemonCardSearchPage(emptyList(), false), fixture.catalog.searchCards(" G "))
        for ((query, page) in listOf("x".repeat(101) to 1, "Glurak" to 0, "" to -1)) {
            var rejected = false
            try { fixture.catalog.searchCards(query, page) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
        assertTrue(fixture.requests.isEmpty())
        fixture.catalog.searchCards("  Nidoran♀ & Mew+/?%  ")
        assertEquals(expected, fixture.requests.last())
    }

    @Test fun maintainedGermanSupplementsAppearOnlyOnFirstPageAndDeduplicateById() = runBlocking {
        val fixture = Fixture { url -> when (url) {
            searchUrl("Mew", 1) -> "[${brief("mep-105", "Mew", "105")}]"
            searchUrl("Mew", 2), searchUrl("Greninja", 1) -> "[]"
            searchUrl("Quajutsu", 1) -> "[]"
            else -> error("Unexpected request: $url")
        } }
        val first = fixture.catalog.searchCards("Mew").cards
        assertEquals(1, first.count { it.id == "mep-105" })
        assertTrue(first.map { it.id }.containsAll(listOf("30th-rgb-r", "30th-rgb-g", "30th-rgb-b", "mep-104")))
        assertTrue(first.all { it.smallImageUrl.isNotBlank() })
        assertTrue(fixture.catalog.searchCards("Mew", 2).cards.isEmpty())
        assertTrue(fixture.catalog.searchCards("Greninja").cards.isEmpty())
        assertEquals("Quajutsu-ex", fixture.catalog.searchCards("Quajutsu").cards.single().name)
    }

    @Test fun oneSameIdImageBatchCannotReplaceGermanNamesOrAcceptForeignOrInconsistentCards() = runBlocking {
        val fallbackUrl = "$EN/cards?id=eq%3Abase1-4%7Cbase1-15&pagination:page=1&pagination:itemsPerPage=40"
        val fixture = Fixture { url -> when (url) {
            searchUrl("Glurak") -> "[${brief("base1-4", "Glurak", "4", null)},${brief("base1-15", "Bisaflor", "15", null)}]"
            fallbackUrl -> "[" + listOf(
                brief("base1-4", "Charizard", "4", "https://english.example/base1/4"),
                brief("base1-15", "Venusaur", "999", "https://wrong.example/999"),
                brief("base1-99", "Unrequested", "99", "https://wrong.example/99"),
            ).joinToString() + "]"
            else -> error("Unexpected request: $url")
        } }
        val cards = fixture.catalog.searchCards("Glurak").cards
        assertEquals(listOf("Glurak", "Bisaflor"), cards.map { it.name })
        assertEquals("https://english.example/base1/4/low.webp", cards[0].smallImageUrl)
        assertTrue(cards[1].smallImageUrl.isEmpty())
        assertTrue(cards.all { it.marketSearchName == null && it.hasGermanLocalizedName })
        assertEquals(1, fixture.requests.count { it.startsWith(EN) })
        assertTrue(fixture.requests.none { it.contains("/cards/") })
    }

    @Test fun verifiedGermanPngAvoidsEnglishBatchAndPreservesValidUserImage() = runBlocking {
        val fixture = Fixture { url -> when {
            url == searchUrl("Glurak") -> "[" + listOf(
                brief("sv02-269", "Glurak", "269", "https://assets.tcgdex.net/de/sv/sv02/269"),
                brief("base1-4", "Glurak", "4", "file:///fixture/card.png"),
            ).joinToString() + "]"
            else -> error("Unexpected request: $url")
        } }
        val cards = fixture.catalog.searchCards("Glurak").cards
        assertEquals("https://assets.tcgdex.net/de/sv/sv02/269/low.png", cards[0].smallImageUrl)
        assertEquals("https://assets.tcgdex.net/de/sv/sv02/269/high.png", cards[0].largeImageUrl)
        assertTrue(fixture.requests.none { it.startsWith(EN) })
        assertEquals("file:///fixture/card.png", cards[1].smallImageUrl)
    }

    @Test fun imageFailureIsOptionalButCancellationPropagatesWithoutCachingAPartialPage() = runBlocking {
        var cancelled = true
        val fixture = Fixture { url -> when {
            url.startsWith("$DE/cards?") -> "[${brief("base1-4", "Glurak", "4", null)}]"
            url.startsWith("$EN/cards?") -> if (cancelled) throw CancellationException("cancelled image batch") else throw IOException("offline")
            else -> error("Unexpected request: $url")
        } }
        var observed = false
        try { fixture.catalog.searchCards("Glurak") } catch (_: CancellationException) { observed = true }
        assertTrue(observed)
        cancelled = false
        assertEquals("Glurak", fixture.catalog.searchCards("Glurak").cards.single().name)
        assertEquals(2, fixture.requests.count { it == searchUrl("Glurak") })
    }

    @Test fun requiredPocketIndexFailureAndSearchCancellationCannotProduceSuccessfulResults() = runBlocking {
        val requests = mutableListOf<String>()
        val invalidIndex = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            requests += url
            Buffer().writeUtf8(if (url.endsWith("/sets")) SETS else "{\"id\":\"tcgp\"}")
        }
        var rejected = false
        try { invalidIndex.searchCards("Glurak") } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertTrue(requests.none { it.contains("/cards?") })
        val cancelled = Fixture { throw CancellationException("cancelled German page") }
        var observed = false
        try { cancelled.catalog.searchCards("Glurak") } catch (_: CancellationException) { observed = true }
        assertTrue(observed)
    }

    private class Fixture(page: (String) -> String) {
        val requests = CopyOnWriteArrayList<String>()
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            requests += url
            Buffer().writeUtf8(when (url) {
                "$DE/sets" -> SETS
                "$DE/series/tcgp" -> """{"id":"tcgp","sets":[{"id":"A1","name":"Pocket"}]}"""
                else -> page(url)
            })
        }
    }

    private companion object {
        const val DE = "https://api.tcgdex.net/v2/de"
        const val EN = "https://api.tcgdex.net/v2/en"
        val SETS = """[
            {"id":"base1","name":"Basis","logo":"https://images.example/base1/logo","cardCount":{"official":102,"total":102}},
            {"id":"sv03.5","name":"151","logo":"https://images.example/sv03.5/logo","cardCount":{"official":165,"total":207}},
            {"id":"sv02","name":"Entwicklungen in Paldea","logo":"https://images.example/sv02/logo","cardCount":{"official":193,"total":279}},
            {"id":"30th","name":"30 Jahre","logo":"https://images.example/30th/logo","cardCount":{"official":128,"total":158}},
            {"id":"30th-c","name":"Klassiker","logo":"https://images.example/30th-c/logo","cardCount":{"official":0,"total":30}},
            {"id":"mep","name":"Mega-Promos","logo":"https://images.example/mep/logo","cardCount":{"official":0,"total":88}},
            {"id":"A1","name":"Unschlagbare Gene","logo":"https://images.example/A1/logo","cardCount":{"official":226,"total":286}},
            {"id":"digital","name":"Digital","logo":"https://images.example/digital/logo","cardCount":{"official":1,"total":1},"serie":{"id":"tcgp","name":"Pocket"}}
        ]"""
        fun searchUrl(query: String, page: Int = 1) =
            "$DE/cards?name=${encodeCatalogPathSegment("like:$query")}&pagination:page=$page&pagination:itemsPerPage=40"

        fun brief(id: String, name: String, number: String, image: String? = "https://images.example/$id") =
            """{"id":"$id","name":"$name","localId":"$number","image":${image?.let { "\"$it\"" } ?: "null"}}"""
    }
}
