package com.dominik.cardvault

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.RawPriceSnapshotEntity
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceCardmarketLookupInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val source = CardmarketSyncContract.SOURCE

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java,
        ).build()
        repository = RoomCollectionRepository(database, MutableStateFlow("source-lookup-test-owner"))
    }

    @After fun closeDatabase() = database.close()

    @Test fun exactSourceIdPreservesFullArtProductAndBothPriceFields() = runBlocking {
        val dao = database.cardVaultDao()
        val fullArt = product(805531L, "Friends in Paldea (Full Art)", "5944")
        dao.insertCatalogCards(listOf(
            fullArt,
            product(805503L, "Friends in Paldea", "5944"),
            product(42L, "Friends in Paldea (Full Art)", "unrelated-expansion"),
            fullArt.copy(id = "other-source-805531", externalSource = "OTHER_SOURCE"),
        ))
        // Deterministic fixture prices; product identity is the source-declared sv08.5-137 id.
        dao.insertRawPrices(listOf(
            price(805531L, 1_000L, 100L, 200L),
            price(805531L, 2_000L, 2_439L, 4_152L),
            price(805503L, 2_000L, 5L, 10L),
            price(42L, 2_000L, 9_999L, 8_888L),
            price(805531L, 3_000L, 99_999L, 99_999L).copy(source = "OTHER_SOURCE"),
        ))

        val result = repository.catalogProducts(listOf(805531L)).single()

        assertEquals(805531L, result.productId)
        assertEquals("Friends in Paldea (Full Art)", result.name)
        assertEquals("5944", result.expansionId)
        assertEquals("Prismatic Evolutions", result.visualSetName)
        assertEquals("", result.collectorNumber)
        assertEquals("UNKNOWN", result.language)
        assertEquals("UNKNOWN", result.finish)
        assertEquals("Cardmarket-Produkt", result.variant)
        assertEquals("FULL_ART", result.rarity)
        assertEquals("https://example.test/805531.webp", result.imageUrl)
        assertEquals("EUR", result.currency)
        assertEquals(24.39, result.trendPriceEur!!, .001)
        assertEquals(41.52, result.holoTrendPriceEur!!, .001)
        assertTrue(dao.observeCollection("source-lookup-test-owner").first().isEmpty())
    }

    @Test fun missingSourceProductNeverUsesAnotherSameNamePrinting() = runBlocking {
        val dao = database.cardVaultDao()
        dao.insertCatalogCards(listOf(
            product(805503L, "Friends in Paldea", "5944"),
            product(805531L, "Friends in Paldea (Full Art)", "5944")
                .copy(id = "other-source-805531", externalSource = "OTHER_SOURCE"),
        ))

        assertTrue(repository.catalogProducts(listOf(805531L)).isEmpty())
        assertTrue(repository.catalogProducts(emptyList()).isEmpty())
        assertTrue(repository.catalogProducts(listOf(0L, -1L)).isEmpty())
    }

    @Test fun unpricedProductRetainsIdentityAndSubsequentLookupReadsLatestPrice() = runBlocking {
        val dao = database.cardVaultDao()
        dao.insertCatalogCard(product(805531L, "Friends in Paldea (Full Art)", "5944"))

        val unpriced = repository.catalogProducts(listOf(805531L, 805531L)).single()
        assertEquals(805531L, unpriced.productId)
        assertNull(unpriced.trendPriceEur)
        assertNull(unpriced.holoTrendPriceEur)

        dao.insertRawPrices(listOf(price(805531L, 1_000L, 532L, 0L)))
        val priced = repository.catalogProducts(listOf(805531L)).single()
        assertEquals(5.32, priced.trendPriceEur!!, .001)
        assertNull(priced.holoTrendPriceEur)

        dao.insertRawPrices(listOf(price(805531L, 2_000L, 600L, null)))
        val updated = repository.catalogProducts(listOf(805531L)).single()
        assertEquals(6.0, updated.trendPriceEur!!, .001)
        assertNull(updated.holoTrendPriceEur)
    }

    private fun product(id: Long, name: String, expansion: String) = CatalogCardEntity(
        id = "cm-pokemon-$id", tcg = "POKEMON", setId = expansion,
        setName = "Prismatic Evolutions", name = name, collectorNumber = "",
        language = "UNKNOWN", finish = "UNKNOWN", variant = "Cardmarket-Produkt",
        createdAtEpochMillis = 1_000L, externalSource = source, externalProductId = id,
        rarity = "FULL_ART", imageUrl = "https://example.test/$id.webp",
    )

    private fun price(id: Long, timestamp: Long, regular: Long, reverse: Long?) = RawPriceSnapshotEntity(
        cardId = "cm-pokemon-$id", source = source, currency = "EUR",
        lowValueMinor = null, averageValueMinor = null, trendValueMinor = regular,
        marketValueMinor = regular, holoTrendValueMinor = reverse,
        fetchedAtEpochMillis = timestamp, effectiveAtEpochMillis = timestamp,
    )
}
