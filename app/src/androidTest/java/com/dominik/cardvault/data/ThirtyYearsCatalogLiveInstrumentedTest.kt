package com.dominik.cardvault.data

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.data.catalog.supplementalThirtyYearsCards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/** Read-only live metadata/image checks. Never opens Firebase or the user's collection. */
@RunWith(AndroidJUnit4::class)
class ThirtyYearsCatalogLiveInstrumentedTest {
    @Test fun rgbAndAnniversaryPromosAreSelectableAndEverySupplementImageDecodes() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveThirtyYears") == "true")
        val catalog = PokemonVisualCatalog()
        catalog.invalidate()
        val sets = catalog.sets()
        val main = sets.single { it.id == "30th" }
        val promos = sets.single { it.id == "mep" }
        val allMain = catalog.cards(main)
        val allPromos = catalog.cards(promos)
        assertTrue(allMain.size >= 161)
        assertTrue(allPromos.size >= 105)
        assertEquals(allMain.size, allMain.map { it.id }.distinct().size)
        val supplemented = supplementalThirtyYearsCards(main) + supplementalThirtyYearsCards(promos)
        assertEquals(20, supplemented.size)
        for (card in supplemented) {
            val found = catalog.findExact(card.set.id, card.number).single()
            assertEquals(card.id, found.id)
            assertEquals(card.number, found.number)
            assertEquals(card.set.id, found.set.id)
            assertTrue(found.printVariants.isNotEmpty())
        }
        val slots = Semaphore(4)
        supplemented.map { card -> async(Dispatchers.IO) {
            slots.withPermit {
                val connection = URL(card.largeImageUrl).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    assertEquals("${card.id}: image HTTP", 200, connection.responseCode)
                    assertTrue("${card.id}: image type", connection.contentType.orEmpty().startsWith("image/"))
                    val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                    assertNotNull("${card.id}: real decodable card image", bitmap)
                    assertTrue("${card.id}: portrait card", bitmap!!.height > bitmap.width)
                    bitmap.recycle()
                } finally { connection.disconnect() }
            }
        } }.awaitAll()
    }
}
