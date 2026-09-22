package com.dominik.cardvault

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.CollectionEntryEntity
import com.dominik.cardvault.data.local.RawPriceSnapshotEntity
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated existing-owner rows: quarantine prices without rewriting identities or quantities. */
@RunWith(AndroidJUnit4::class)
class ReviewedCardmarketIdentityInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val owner = "reviewed-price-fixture"
    private val source = CardmarketSyncContract.SOURCE

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
        repository = RoomCollectionRepository(database, MutableStateFlow(owner))
    }
    @After fun closeDatabase() = database.close()

    @Test fun bothContradictionsLoseOnlyTheirPriceAndHistoryWhileSiblingsStayPriced() = runBlocking {
        val dao = database.cardVaultDao()
        val cases = listOf(
            Triple(670816L, "swsh11-201", "swsh11-130"),
            Triple(781858L, "sv03-062", "sv03-057"),
        )
        val original = mutableMapOf<Long, CollectionEntryEntity>()
        for ((product, wrong, correct) in cases) {
            dao.insertCatalogCard(catalog("cm-pokemon-$product", product))
            dao.insertRawPrices(listOf(price("cm-pokemon-$product", 100L, 1_000L), price("cm-pokemon-$product", 200L, 2_000L)))
            for (identity in listOf(wrong, correct)) {
                val row = entry("cm-pokemon-$product", identity, product)
                val id = dao.insertCollectionEntry(row)
                original[id] = row.copy(id = id)
            }
        }
        val projection = dao.observeCollection(owner).first()
        assertEquals(4, projection.size)
        val portfolio = repository.portfolio.first()
        assertEquals(8, portfolio.totalCards)
        assertEquals(4, portfolio.pricedCards)
        assertEquals(4, portfolio.unpricedCards)
        assertEquals(8.0, portfolio.totalValue, .001)
        assertEquals(listOf(4.0, 8.0), portfolio.history.map { it.value })
        for ((_, wrong, correct) in cases) {
            val bad = projection.single { it.sourceCardId == wrong }
            assertNull(bad.marketValueMinor)
            assertNull(bad.priceUpdatedAtEpochMillis)
            assertEquals("", bad.priceCardId)
            assertTrue(repository.priceHistory(portfolio.cards.single { it.sourceCardId == wrong }).first().isEmpty())
            assertEquals(listOf(1.0, 2.0), repository.priceHistory(portfolio.cards.single { it.sourceCardId == correct }).first().map { it.valueEur })
        }
        assertEquals(8.0, repository.binders.first().sumOf { it.marketValueEur }, .001)
        for ((id, row) in original) assertEquals(row, dao.getCollectionEntry(id, owner))
    }

    @Test fun legacyCatalogProductLinkAndUppercasePrefixedIdentityCannotLeakThroughFallbackHistory() = runBlocking {
        val dao = database.cardVaultDao()
        val id = "TCGDEX-SV03-SV03-062-cosmos"
        dao.insertCatalogCard(catalog(id, 781858L))
        dao.insertRawPrices(listOf(price(id, 23L, 1_000L)))
        val original = entry(id, " ", null)
        val entryId = dao.insertCollectionEntry(original)
        val row = dao.observeCollection(owner).first().single()
        assertNull(row.marketValueMinor)
        assertEquals("", row.priceCardId)
        assertTrue(dao.observeCardPriceHistory(entryId, id, source, false, owner).first().isEmpty())
        val portfolio = repository.portfolio.first()
        assertEquals(0.0, portfolio.totalValue, .001)
        assertTrue(portfolio.history.all { it.value == 0.0 })
        assertTrue(repository.priceHistory(portfolio.cards.single()).first().isEmpty())
        assertEquals(0.0, repository.binders.first().single().marketValueEur, .001)
        assertEquals(original.copy(id = entryId), dao.getCollectionEntry(entryId, owner))
    }

    @Test fun ordinaryPalafinPriceAndReverseStaySeparateAndUnchanged() = runBlocking {
        val dao = database.cardVaultDao()
        val id = "cm-pokemon-725142"
        dao.insertCatalogCard(catalog(id, 725142L))
        dao.insertRawPrices(listOf(price(id, 2L, 1_000L)))
        dao.insertCollectionEntry(entry(id, "SV03-062", 725142L))
        dao.insertCollectionEntry(entry(id, "SV03-062", 725142L).copy(finishOverride = "Reverse Holo", variantOverride = "Reverse Holo"))
        val cards = repository.portfolio.first().cards
        assertEquals(.02, cards.single { it.finish == "Holo" }.currentValueEur, .001)
        assertEquals(.11, cards.single { it.finish == "Reverse Holo" }.currentValueEur, .001)
        assertEquals(.26, repository.portfolio.first().totalValue, .001)
    }

    private fun catalog(id: String, product: Long) = CatalogCardEntity(
        id = id, tcg = "POKEMON", setId = "fixture", setName = "Fixture", name = "No name matching",
        collectorNumber = "062", language = "GERMAN", finish = "Holo", variant = "Holo",
        createdAtEpochMillis = 1_000L, externalSource = source, externalProductId = product,
    )
    private fun entry(id: String, identity: String, product: Long?) = CollectionEntryEntity(
        cardId = id, quantity = 2, condition = "Near Mint", acquisitionPriceMinor = 789L,
        acquisitionCurrency = "EUR", acquisitionDateEpochDay = 20_000L, notes = "Retain my metadata",
        frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = 1_000L, updatedAtEpochMillis = 2_000L,
        externalProductSource = source, externalProductId = product, ownerUid = owner, binderId = "fixture-binder",
        sourceSetId = identity.substringBeforeLast('-'), sourceCardId = identity,
    )
    private fun price(id: String, regular: Long, timestamp: Long) = RawPriceSnapshotEntity(
        cardId = id, source = source, currency = "EUR", lowValueMinor = null, averageValueMinor = null,
        trendValueMinor = regular, marketValueMinor = regular, holoTrendValueMinor = 11L,
        fetchedAtEpochMillis = timestamp, effectiveAtEpochMillis = timestamp,
    )
}
