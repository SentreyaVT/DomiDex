package com.dominik.cardvault.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in read-only check of the real catalog and image CDN; no user database or Firebase. */
@RunWith(AndroidJUnit4::class)
class CatalogImagesLiveInstrumentedTest {
    @Test fun exactSourceCardsAndAvailableImagesLoadAcrossOldPromoGalleryAndCurrentSets() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCatalogImages") == "true")
        val catalog = PokemonVisualCatalog()
        catalog.invalidate()
        val sets = catalog.sets()
        assertTrue(sets.isNotEmpty())
        for (id in listOf("base1", "svp", "me03")) {
            val set = sets.single { it.id == id }
            val cards = catalog.cards(set)
            assertTrue("$id has no cards", cards.isNotEmpty())
            assertEquals("$id duplicates", cards.size, cards.map { it.id }.distinct().size)
            assertTrue("$id wrong set", cards.all { it.set.id == id })
            val image = cards.firstOrNull { it.largeImageUrl.isNotBlank() }
            assertNotNull("$id missing every source image", image)
            assertImageLoads(image!!.largeImageUrl)
        }
        // These real sources currently supply card identities but no artwork.
        // They must stay selectable; never manufacture image addresses from their names.
        for (id in listOf("mep", "swsh9tg")) {
            val cards = catalog.cards(sets.single { it.id == id })
            assertTrue("$id missing selectable cards", cards.isNotEmpty())
            assertTrue("$id wrong identity", cards.all { it.set.id == id })
        }
        assertTrue(catalog.cards(sets.single { it.id == "mep" }).any { it.id == "mep-071" })
        assertNotNull("Verified base1 English logo fallback", sets.single { it.id == "base1" }.logoUrl)
        assertImageLoads(sets.single { it.id == "base1" }.logoUrl!!)
    }

    private suspend fun assertImageLoads(url: String) = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            assertEquals("Source image unavailable", 200, connection.responseCode)
            assertTrue("Not an image", connection.contentType.orEmpty().startsWith("image/"))
            connection.inputStream.use { assertTrue("Empty image", it.read() >= 0) }
        } finally { connection.disconnect() }
    }
}
