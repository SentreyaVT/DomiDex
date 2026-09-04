package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class PokemonCatalogImagesTest {
    private val set = PokemonSet("base1", "Grundset", "Deutsch", 102, 102, "", null, null)
    private fun card(id: String = "base1-1", image: String = "") = PokemonVisualCard(
        id, "Deutscher Name", "1", "Rare", image, image, set,
        listOf(PokemonPrintVariant("holo", "Holo", "Holo")),
    )

    @Test fun missingImageUsesOnlySameSourceIdAndKeepsGermanIdentityAndVariants() {
        val german = card()
        val english = german.copy(name = "English", smallImageUrl = "en-low", largeImageUrl = "en-high", hasGermanLocalizedName = false)
        val result = mergeLocalizedSetCards(listOf(german), listOf(english)).single()
        assertEquals("en-low", result.smallImageUrl)
        assertEquals("en-high", result.largeImageUrl)
        assertEquals(german.name, result.name)
        assertEquals(german.set, result.set)
        assertEquals(german.printVariants, result.printVariants)
        assertTrue(result.hasGermanLocalizedName)
    }

    @Test fun existingGermanImagesAreNeverReplacedAndDifferentIdCannotSupplyAnImage() {
        val german = card(image = "de-image")
        assertEquals("de-image", mergeLocalizedSetCards(listOf(german), listOf(card(image = "en-image"))).single().largeImageUrl)
        val unresolved = mergeLocalizedSetCards(listOf(card()), listOf(card("other-1", "wrong-image"))).first()
        assertEquals("", unresolved.largeImageUrl)
    }

    @Test fun setLogoFallbackPreservesGermanSetAndDoesNotAddOtherSets() {
        val results = fillMissingSetLogos(listOf(set), listOf(set.copy(name = "Base Set", logoUrl = "en-logo"), set.copy(id = "other")))
        assertEquals(listOf(set.copy(logoUrl = "en-logo")), results)
        assertEquals("de-logo", fillMissingSetLogos(listOf(set.copy(logoUrl = "de-logo")), listOf(set.copy(logoUrl = "en-logo"))).single().logoUrl)
        assertNull(fillMissingSetLogos(listOf(set), listOf(set.copy(id = "other", logoUrl = "wrong"))).single().logoUrl)
    }

    @Test fun blankLogoUsesOfficialSymbolInsteadOfInvalidDotPng() = runBlocking {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) {
            Buffer().writeUtf8("""[{"id":"base2","name":"Dschungel","logo":" ","symbol":"https://assets.tcgdex.net/univ/base/base2/symbol"}]""")
        }
        assertEquals("https://assets.tcgdex.net/univ/base/base2/symbol.png", catalog.sets().single().logoUrl)
    }

    @Test fun imageBaseAndAlreadyQualifiedFileUrlsAreBothSupported() = runBlocking {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) {
            Buffer().writeUtf8("""{"cards":[
                {"id":"base1-1","name":"A","localId":"1","image":"https://assets.tcgdex.net/de/base/base1/1/"},
                {"id":"base1-2","name":"B","localId":"2","image":"https://assets.tcgdex.net/de/base/base1/2/high.webp"}
            ]}""")
        }
        val cards = catalog.cards(set)
        assertEquals("https://assets.tcgdex.net/de/base/base1/1/low.webp", cards.first().smallImageUrl)
        assertEquals("https://assets.tcgdex.net/de/base/base1/1/high.webp", cards.first().largeImageUrl)
        assertEquals("https://assets.tcgdex.net/de/base/base1/2/high.webp", cards.last().largeImageUrl)
    }

    @Test fun detailImageRepairsMissingBriefWithoutChangingIdentity() = runBlocking {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) {
            Buffer().writeUtf8("""{"image":"https://assets.tcgdex.net/de/base/base1/1"}""")
        }
        val fixed = catalog.withDeclaredPrintVariants(card())
        assertEquals("https://assets.tcgdex.net/de/base/base1/1/high.webp", fixed.largeImageUrl)
        assertEquals(card().printVariants, fixed.printVariants)
        assertEquals("kept", catalog.withDeclaredPrintVariants(card(image = "kept")).largeImageUrl)
    }

    @Test fun unavailableGermanSetStillLoadsExactEnglishSourceWithoutCachingTotalFailure() = runBlocking {
        var offline = true
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            if (offline || url.contains("/de/")) error("offline")
            Buffer().writeUtf8("""{"cards":[{"id":"base1-1","name":"English","localId":"1","image":"https://assets.tcgdex.net/en/base/base1/1"}]}""")
        }
        try { catalog.cards(set); fail("Both failures must remain retryable") } catch (_: IllegalStateException) { }
        offline = false
        val result = catalog.cards(set).single()
        assertEquals("base1-1", result.id)
        assertEquals(set, result.set)
        assertFalse(result.hasGermanLocalizedName)
        assertTrue(result.largeImageUrl.endsWith("/high.webp"))
    }
}
