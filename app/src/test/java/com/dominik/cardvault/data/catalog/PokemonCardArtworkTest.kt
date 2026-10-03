package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class PokemonCardArtworkTest {
    private val empty = PokemonCardArtwork("", "")
    private val original = PokemonCardArtwork("https://de.example/card-low.webp", "https://de.example/card-high.webp")
    private val replacement = PokemonCardArtwork("https://verified.example/low.webp", "https://verified.example/high.webp")
    private fun candidate(set: String = "base1", id: String = "base1-4", local: String? = "4") =
        PokemonCardArtworkCandidate(set, id, local, replacement)

    @Test fun validPrimaryAlwaysWinsUnlessItsExactUrlWasAuditedAsReplaceable() {
        val registry = PokemonCardArtworkRegistry(listOf(
            PokemonCardArtworkOverride("base1", "base1-4", "4", replacement = replacement),
        ))
        assertEquals(original, resolvePokemonCardArtwork("base1", "base1-4", "4", original, candidate(), registry))
        val userPhoto = PokemonCardArtwork("file:///private/front.png", "content://user/photo")
        assertEquals(userPhoto, resolvePokemonCardArtwork("base1", "base1-4", "4", userPhoto, candidate(), registry))
        assertEquals(replacement, resolvePokemonCardArtwork("base1", "base1-4", "4", empty, registry = registry))
    }

    @Test fun onlySameSetCardAndLocalIdentityCanSupplyFallback() {
        assertEquals(replacement, resolvePokemonCardArtwork("base1", "base1-4", "4", empty, candidate()))
        for (other in listOf(candidate("base2"), candidate(id = "base1-5"), candidate(local = "5"))) {
            assertEquals(empty, resolvePokemonCardArtwork("base1", "base1-4", "4", empty, other))
        }
        assertEquals(empty, resolvePokemonCardArtwork("base2", "base1-4", "4", empty, candidate()))
        assertEquals(empty, resolvePokemonCardArtwork("base1", "base1-4", "5", empty, candidate()))
        assertEquals(original, resolvePokemonCardArtwork(null, "base1-4", null, original, candidate()))
        assertEquals(empty, resolvePokemonCardArtwork("30th", "30th-c-001", "c-001", empty))
    }

    @Test fun knownBadUrlsAreExactAndSpecificToOneSourceCard() {
        val broken = "https://assets.tcgdex.net/de/sv/sv02/269"
        val bad = PokemonCardArtwork("$broken/low.webp", "$broken/high.webp")
        val verifiedGerman = PokemonCardArtwork("$broken/low.png", "$broken/high.png")
        val en = PokemonCardArtworkCandidate("sv02", "sv02-269", "269", replacement)
        assertEquals(verifiedGerman, resolvePokemonCardArtwork("sv02", "sv02-269", "269", bad, en))
        assertEquals(verifiedGerman, resolvePokemonCardArtwork("sv02", "sv02-269", "269", bad))
        assertEquals(bad, resolvePokemonCardArtwork("sv02", "sv02-268", "268", bad, candidate("sv02", "sv02-268", "268")))
        val corrected = PokemonCardArtwork("$broken-fixed/low.webp", "$broken-fixed/high.webp")
        assertEquals(corrected, resolvePokemonCardArtwork("sv02", "sv02-269", "269", corrected, en))
    }

    @Test fun auditedGermanReplacementCanUpgradeOnlyTheListedEnglishFallback() {
        val english = PokemonCardArtwork("https://en.example/4/low.webp", "https://en.example/4/high.webp")
        val registry = PokemonCardArtworkRegistry(listOf(
            PokemonCardArtworkOverride("base1", "base1-4", "4",
                replaceableUrls = setOf(english.smallImageUrl, english.largeImageUrl), replacement = replacement),
        ))
        assertEquals(replacement, resolvePokemonCardArtwork("base1", "base1-4", null, english, candidate(), registry))
        val corrected = english.copy(largeImageUrl = "https://en.example/4-corrected/high.webp")
        assertEquals(corrected.copy(smallImageUrl = replacement.smallImageUrl),
            resolvePokemonCardArtwork("base1", "base1-4", null, corrected, registry = registry))
        assertEquals(english, resolvePokemonCardArtwork("base1", "base1-5", "5", english, registry = registry))
    }

    @Test fun brokenEnglishEx14HighUsesVerifiedSameCardPngWithoutInventingGermanArtwork() {
        val base = "https://assets.tcgdex.net/en/ex/ex14/97"
        val primary = PokemonCardArtwork("$base/low.webp", "$base/high.webp")
        assertEquals(PokemonCardArtwork("$base/low.webp", "$base/high.png"),
            resolvePokemonCardArtwork("ex14", "ex14-97", "97", primary))
        assertEquals(PokemonCardArtwork("$base/low.png", "$base/high.png"),
            resolvePokemonCardArtwork("ex14", "ex14-97", "97", empty))
        assertEquals(primary, resolvePokemonCardArtwork("ex14", "ex14-96", "96", primary))
    }

    /** Integration/structural contract; independent audit hashes establish source provenance separately. */
    @Test fun everyAuditedRegistryEntryIsUniqueWiredAndIdempotent() {
        val entries = auditedPokemonCardArtworkOverrides()
        assertTrue("The audited registry must not be empty", entries.isNotEmpty())
        val identities = entries.map { it.sourceSetId.lowercase(Locale.ROOT) to it.sourceCardId.lowercase(Locale.ROOT) }
        assertEquals("No case-insensitive duplicate identity", entries.size, identities.toSet().size)
        entries.forEach { entry ->
            val expected = checkNotNull(entry.replacement) { "No audited replacement for ${entry.sourceCardId}" }
            assertTrue(entry.sourceCardId, expected.smallImageUrl.isNotBlank() && expected.largeImageUrl.isNotBlank())
            val replaced = entry.unavailableUrls + entry.replaceableUrls
            assertFalse(entry.sourceCardId, expected.smallImageUrl in replaced || expected.largeImageUrl in replaced)
            val resolved = resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, null, empty)
            assertEquals(entry.sourceCardId, expected, resolved)
            assertEquals(entry.sourceCardId, expected,
                resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, entry.sourceLocalId, empty))
            assertEquals(entry.sourceCardId, expected,
                resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, null, resolved))
            assertFalse(entry.sourceCardId,
                needsPokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, null, resolved))
        }
    }

    @Test fun auditedUrlReplacementPolicyIsExactAndNeverTouchesUserPhotos() {
        val entries = auditedPokemonCardArtworkOverrides()
        assertTrue("Exercise real listed language upgrades", entries.any { it.replaceableUrls.isNotEmpty() })
        val userPhoto = PokemonCardArtwork("file:///private/front.png", "content://user/photo")
        entries.forEach { entry ->
            assertEquals(entry.sourceCardId, userPhoto,
                resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, null, userPhoto))
            assertEquals(entry.sourceCardId, original,
                resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, null, original))
            entry.replaceableUrls.forEach { url ->
                val listed = PokemonCardArtwork(url, url)
                assertEquals(entry.sourceCardId, entry.replacement,
                    resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, null, listed))
                val corrected = PokemonCardArtwork("$url?user-corrected=1", "$url?user-corrected=1")
                assertEquals(entry.sourceCardId, corrected,
                    resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, null, corrected))
                assertEquals(entry.sourceCardId, listed,
                    resolvePokemonCardArtwork(entry.sourceSetId + "-unrelated", entry.sourceCardId, null, listed))
            }
        }
    }

    @Test fun explicitSpecialLocalIdentityWorksWithoutGuessingFromPrintedNumbers() {
        val registry = PokemonCardArtworkRegistry(listOf(
            PokemonCardArtworkOverride("30th", "30th-rgb-r", "R/RGB", replacement = replacement),
        ))
        assertEquals(replacement, resolvePokemonCardArtwork("30TH", "30TH-RGB-R", "R/RGB", empty, registry = registry))
        assertEquals(replacement, resolvePokemonCardArtwork("30th", "30th-rgb-r", null, empty, registry = registry))
        assertEquals(empty, resolvePokemonCardArtwork("30th", "30th-rgb-r", "G/RGB", empty, registry = registry))
        val classic = resolvePokemonCardArtwork("30th-c", "30th-c-001", null, empty)
        assertTrue(classic.largeImageUrl.endsWith("30TH-C_DE_1.png"))
        assertEquals(empty, resolvePokemonCardArtwork("30th-c", "30th-c-001", "4/102", empty))
    }

    @Test fun registryRejectsDuplicateAndContradictorySourceKeysAndCopiesMutablePolicySets() {
        val mutable = mutableSetOf(original.smallImageUrl)
        val entry = PokemonCardArtworkOverride("base1", "base1-4", "4", mutable, replacement = replacement)
        val registry = PokemonCardArtworkRegistry(listOf(entry))
        mutable.clear()
        assertEquals(original.copy(smallImageUrl = replacement.smallImageUrl),
            resolvePokemonCardArtwork("base1", "base1-4", null, original, registry = registry))
        assertThrows(IllegalStateException::class.java) { PokemonCardArtworkRegistry(listOf(entry, entry.copy(sourceSetId = "BASE1"))) }
        assertThrows(IllegalArgumentException::class.java) {
            PokemonCardArtworkRegistry(listOf(entry.copy(sourceCardId = "base2-4")))
        }
    }

    @Test fun setMergeRejectsMismatchedArtworkWithoutChangingGermanMetadata() {
        val german = card()
        val english = german.copy(name = "Charizard", sourceLocalId = "5", smallImageUrl = "wrong-low", largeImageUrl = "wrong-high")
        val result = mergeLocalizedSetCards(listOf(german), listOf(english)).single()
        assertEquals(german.smallImageUrl, result.smallImageUrl)
        assertEquals(german.largeImageUrl, result.largeImageUrl)
        assertEquals(german.name, result.name)
        assertEquals(german.printVariants, result.printVariants)
    }

    @Test fun defaultSetDetailEnrichmentDoesNotStartAnExtraImageRequest() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            requests += url
            Buffer().writeUtf8("""{"rarity":"Selten","variants_detailed":[]}""")
        }
        val result = catalog.withDeclaredPrintVariants(card())
        assertEquals("Selten", result.rarity)
        assertEquals("", result.largeImageUrl)
        assertEquals(listOf("$DE/base1-4"), requests)
    }

    @Test fun selectedMissingImageUsesOneCachedEnglishDetailWithoutTakingEnglishVariantsOrRarity() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            requests += url
            Buffer().writeUtf8(if (url.startsWith(DE)) """{"id":"base1-4","localId":"4","set":{"id":"base1"},"rarity":"Selten","variants_detailed":[]}"""
                else """{"id":"base1-4","localId":"4","set":{"id":"base1"},"image":"https://en.example/base1/4","rarity":"Rare","variants_detailed":[{"variantId":"foreign","type":"holo","size":"Standard","thirdParty":{"cardmarket":99}}]}""")
        }
        val originalCard = card()
        val first = catalog.withDeclaredPrintVariants(originalCard, loadMissingArtwork = true)
        val second = catalog.withDeclaredPrintVariants(originalCard.copy(name = "Aktuell"), loadMissingArtwork = true)
        assertEquals("Glurak", first.name)
        assertEquals("Aktuell", second.name)
        assertEquals("Selten", first.rarity)
        assertEquals(originalCard.printVariants, first.printVariants)
        assertEquals("https://en.example/base1/4/high.webp", first.largeImageUrl)
        assertEquals(listOf("$DE/base1-4", "$EN/base1-4"), requests)
        catalog.invalidate()
        catalog.withDeclaredPrintVariants(originalCard, loadMissingArtwork = true)
        assertEquals(4, requests.size)
    }

    @Test fun cachedGoodCallerDoesNotSuppressLaterMissingImageRepair() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            requests += url
            Buffer().writeUtf8(if (url.startsWith(DE)) "{}" else """{"image":"https://en.example/base1/4"}""")
        }
        val good = card().copy(smallImageUrl = "kept-low", largeImageUrl = "kept-high")
        assertEquals("kept-high", catalog.withDeclaredPrintVariants(good, loadMissingArtwork = true).largeImageUrl)
        assertEquals(1, requests.size)
        assertEquals("https://en.example/base1/4/high.webp", catalog.withDeclaredPrintVariants(card(), loadMissingArtwork = true).largeImageUrl)
        assertEquals(2, requests.size)
    }

    @Test fun selectedKnownBrokenImageUsesAuditedGermanPngWithoutEnglishRequest() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val bad = "https://assets.tcgdex.net/de/sv/sv02/269"
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            requests += url
            check(url == "$DE/sv02-269") { "Already audited PNG must not request an English image" }
            Buffer().writeUtf8("""{"image":"$bad"}""")
        }
        val source = card().copy(id = "sv02-269", number = "269", sourceLocalId = "269",
            set = card().set.copy(id = "sv02"), smallImageUrl = "$bad/low.webp", largeImageUrl = "$bad/high.webp")
        val result = catalog.withDeclaredPrintVariants(source, loadMissingArtwork = true)
        assertEquals("$bad/low.png", result.smallImageUrl)
        assertEquals("$bad/high.png", result.largeImageUrl)
        assertEquals(source.copy(smallImageUrl = result.smallImageUrl, largeImageUrl = result.largeImageUrl), result)
        assertEquals(listOf("$DE/sv02-269"), requests)
    }

    @Test fun mismatchedEnglishDetailCannotSupplyImageAndCancellationRemainsRetryable() = runBlocking {
        for (invalid in listOf("\"id\":\"base1-5\"", "\"localId\":\"5\"", "\"set\":{\"id\":\"base2\"}")) {
            val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
                Buffer().writeUtf8(if (url.startsWith(DE)) "{}" else "{$invalid,\"image\":\"https://wrong.example/card\"}")
            }
            assertEquals("", catalog.withDeclaredPrintVariants(card(), loadMissingArtwork = true).largeImageUrl)
        }
        var cancel = true
        var englishReads = 0
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            if (url.startsWith(EN)) {
                englishReads++
                if (cancel) throw CancellationException("cancelled artwork")
            }
            Buffer().writeUtf8(if (url.startsWith(DE)) "{}" else """{"image":"https://en.example/base1/4"}""")
        }
        var observed = false
        try { catalog.withDeclaredPrintVariants(card(), loadMissingArtwork = true) } catch (_: CancellationException) { observed = true }
        assertTrue(observed)
        cancel = false
        assertTrue(catalog.withDeclaredPrintVariants(card(), loadMissingArtwork = true).largeImageUrl.isNotEmpty())
        assertEquals(2, englishReads)
    }

    private fun card() = PokemonVisualCard("base1-4", "Glurak", "4", null, "", "",
        PokemonSet("base1", "Basis", "Deutsch", 102, 102, "", null, null), sourceLocalId = "4")

    private companion object {
        const val DE = "https://api.tcgdex.net/v2/de/cards"
        const val EN = "https://api.tcgdex.net/v2/en/cards"
    }
}
