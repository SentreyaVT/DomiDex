package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Regression inputs are published, downloaded German files from the 2026-10-04 audit. */
class MissingCardArtworkTest {
    private val empty = PokemonCardArtwork("", "")

    @Test fun missingMeganiumPromoReceivesItsExactPublishedGermanImage() {
        val image = resolvePokemonCardArtwork("mep", "mep-001", "001", empty)
        assertEquals("https://limitlesstcg.nyc3.cdn.digitaloceanspaces.com/tpci/MEP/MEP_001_R_DE_SM.png",
            image.smallImageUrl)
        assertTrue(image.largeImageUrl.isNotBlank())
    }

    @Test fun missingScarletVioletMetalEnergyKeepsItsPrintedEnergyEdition() {
        val image = resolvePokemonCardArtwork("sve", "sve-024", "024", empty)
        assertEquals("https://limitlesstcg.nyc3.cdn.digitaloceanspaces.com/tpci/SVE/SVE_024_R_DE_SM.png",
            image.smallImageUrl)
        assertTrue(image.largeImageUrl.isNotBlank())
    }

    @Test fun everyAdditionalIdentityIsUniqueAndDoesNotShadowAnExistingRepair() {
        val old = auditedPokemonCardArtworkOverrides()
        val additions = additionalPokemonCardArtworkOverrides()
        val keys = (old + additions).map {
            it.sourceSetId.lowercase(Locale.ROOT) to it.sourceCardId.lowercase(Locale.ROOT)
        }
        assertEquals(keys.size, keys.toSet().size)
        additions.forEach { entry ->
            assertEquals("${entry.sourceSetId}-${entry.sourceLocalId}", entry.sourceCardId)
            assertTrue(entry.unavailableUrls.isEmpty())
            assertTrue(entry.replaceableUrls.isEmpty())
        }
    }

    @Test fun everyAdditionalImageResolvesThroughTheRealRegistryAndIsIdempotent() {
        additionalPokemonCardArtworkOverrides().forEach { entry ->
            val result = resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, entry.sourceLocalId, empty)
            assertEquals(entry.sourceCardId, entry.replacement, result)
            assertEquals(entry.sourceCardId, result,
                resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, entry.sourceLocalId, result))
            assertEquals(entry.sourceCardId, result,
                resolvePokemonCardArtwork(entry.sourceSetId.lowercase(Locale.ROOT),
                    entry.sourceCardId.lowercase(Locale.ROOT), null, empty))
            assertTrue(result.smallImageUrl.startsWith("https://"))
            assertTrue(result.largeImageUrl.startsWith("https://"))
            assertFalse(entry.sourceCardId,
                needsPokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, entry.sourceLocalId, result))
        }
    }

    @Test fun contradictorySetOrLocalIdCannotBorrowNewImages() {
        additionalPokemonCardArtworkOverrides().forEach { entry ->
            assertEquals(entry.sourceCardId, empty,
                resolvePokemonCardArtwork("not-the-source-set", entry.sourceCardId, entry.sourceLocalId, empty))
            assertEquals(entry.sourceCardId, empty,
                resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, "different-local-id", empty))
            assertEquals(entry.sourceCardId, empty,
                resolvePokemonCardArtwork(entry.sourceSetId, null, entry.sourceLocalId, empty))
        }
    }

    @Test fun everyAdditionalRepairPreservesExistingImagesAndOnlyFillsEmptyQualities() {
        val stored = PokemonCardArtwork("content://user/own-card", "https://example.org/corrected-card.png")
        additionalPokemonCardArtworkOverrides().forEach { entry ->
            assertEquals(entry.sourceCardId, stored,
                resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, entry.sourceLocalId, stored))
            val lowOnly = PokemonCardArtwork(stored.smallImageUrl, "")
            assertEquals(entry.sourceCardId, PokemonCardArtwork(stored.smallImageUrl,
                requireNotNull(entry.replacement).largeImageUrl),
                resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, entry.sourceLocalId, lowOnly))
            val highOnly = PokemonCardArtwork("", stored.largeImageUrl)
            assertEquals(entry.sourceCardId, PokemonCardArtwork(entry.replacement.smallImageUrl, stored.largeImageUrl),
                resolvePokemonCardArtwork(entry.sourceSetId, entry.sourceCardId, entry.sourceLocalId, highOnly))
        }
    }

    @Test fun pocketSetLoadsAnExactFallbackWithoutReplacingGermanCardIdentityOrVariants() = runBlocking {
        val set = PokemonSet("B2a", "Paldeas Wunder", "Pokémon TCG Pocket", 131, 131, "", null, null)
        val requests = CopyOnWriteArrayList<String>()
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            requests += url
            check(url == "https://api.tcgdex.net/v2/de/sets/B2a" ||
                url == "https://api.tcgdex.net/v2/en/sets/B2a")
            val name = if (url.contains("/de/")) "Espinodon" else "Baxcalibur"
            Buffer().writeUtf8("""{"cards":[{"id":"B2a-130","name":"$name","localId":"130","image":null}]}""")
        }
        val result = catalog.cards(set).single()
        assertEquals("B2a-130", result.id)
        assertEquals("130", result.sourceLocalId)
        assertEquals("130", result.number)
        assertEquals("Espinodon", result.name)
        assertEquals(set, result.set)
        assertTrue(result.hasGermanLocalizedName)
        assertEquals(listOf(PokemonPrintVariant("normal", "Normal", "Standard")), result.printVariants)
        val expected = "https://www.serebii.net/tcgpocket/paldeanwonders/130.jpg"
        assertEquals(PokemonCardArtwork(expected, expected), result.artwork())
        assertEquals(result, catalog.cards(set).single())
        assertEquals(2, requests.size) // Existing DE/EN set fetches only; no per-card request.
    }
}
