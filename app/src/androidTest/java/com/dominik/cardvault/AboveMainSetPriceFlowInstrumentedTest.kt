package com.dominik.cardvault

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.RawPriceSnapshotEntity
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import com.dominik.cardvault.model.AddCardOutcome
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.ManualCardInput
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
class AboveMainSetPriceFlowInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val source = CardmarketSyncContract.SOURCE

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java,
        ).build()
        repository = RoomCollectionRepository(database, MutableStateFlow("price-test-owner"))
    }

    @After fun closeDatabase() = database.close()

    @Test fun aboveMainSetHoloKeepsProductTrendThroughAddTotalsAndHistory() = runBlocking {
        val dao = database.cardVaultDao()
        // The source pairing and current trend reproduce me03-090 / Cardmarket 877507.
        // The earlier observation is a fixture so both history points are deterministic.
        dao.insertCatalogCard(product(877507L, "Rowlet [Find a Friend | Tackle]").copy(setId = "6443"))
        dao.insertRawPrices(listOf(
            price(877507L, 1_000L, 500L, null),
            price(877507L, 2_000L, 532L, 0L),
        ))
        val input = specialInput()

        assertEquals(AddCardOutcome.CREATED, repository.addManualCard(input))
        assertEquals(AddCardOutcome.QUANTITY_INCREMENTED, repository.addManualCard(input))

        val row = dao.observeCollection("price-test-owner").first().single()
        assertEquals("me03", row.sourceSetId)
        assertEquals("me03-090", row.sourceCardId)
        assertEquals("090/088", row.collectorNumber)
        assertEquals("Holo", row.finish)
        assertEquals("Holo", row.variant)
        assertEquals(877507L, row.externalProductId)
        assertEquals("tcgdex-me03", row.binderId)
        assertEquals(2, row.quantity)
        assertEquals(532L, row.marketValueMinor)
        assertEquals(2_000L, row.priceUpdatedAtEpochMillis)

        val portfolio = repository.portfolio.first()
        assertEquals(10.64, portfolio.totalValue, .001)
        assertEquals(2, portfolio.pricedCards)
        assertEquals(0, portfolio.unpricedCards)
        assertEquals(listOf(10.0, 10.64), portfolio.history.map { it.value })
        assertEquals(6.4, portfolio.cards.single().changePercent, .001)
        val binder = repository.binders.first().single()
        assertEquals(10.64, binder.marketValueEur, .001)
        assertEquals(2_000L, binder.lastPriceUpdatedAtEpochMillis)
        val history = repository.priceHistory(portfolio.cards.single()).first()
        assertEquals(listOf(5.0, 5.32), history.map { it.valueEur })
        assertEquals(listOf(1_000L, 2_000L), history.map { it.timestampEpochMillis })
    }

    @Test fun regularAndReverseHoloKeepSeparatePricesAndCollectionEntries() = runBlocking {
        val dao = database.cardVaultDao()
        dao.insertCatalogCard(product(42L, "Price test card"))
        dao.insertRawPrices(listOf(price(42L, 1_000L, 532L, 914L)))
        val input = specialInput().copy(
            name = "Price test card", setName = "Price test set", collectorNumber = "001/100",
            externalProductId = 42L, sourceSetId = "price-test", sourceCardId = "price-test-001",
        )
        assertEquals(AddCardOutcome.CREATED, repository.addManualCard(input))
        assertEquals(AddCardOutcome.CREATED, repository.addManualCard(
            input.copy(finish = "Reverse Holo", variant = "Reverse Holo"),
        ))

        val portfolio = repository.portfolio.first()
        assertEquals(2, portfolio.cards.size)
        assertEquals(14.46, portfolio.totalValue, .001)
        assertEquals(14.46, portfolio.history.single().value, .001)
        assertEquals(14.46, repository.binders.first().single().marketValueEur, .001)
        val holo = portfolio.cards.single { it.finish == "Holo" }
        val reverse = portfolio.cards.single { it.finish == "Reverse Holo" }
        assertEquals(holo.sourceCardId, reverse.sourceCardId)
        assertTrue(holo.entryId != reverse.entryId)
        assertEquals(5.32, holo.currentValueEur, .001)
        assertEquals(9.14, reverse.currentValueEur, .001)
        assertEquals(5.32, repository.priceHistory(holo).first().single().valueEur, .001)
        assertEquals(9.14, repository.priceHistory(reverse).first().single().valueEur, .001)
    }

    @Test fun absentAndZeroReverseTrendsNeverUseRegularProductPrice() = runBlocking {
        val dao = database.cardVaultDao()
        dao.insertCatalogCard(product(42L, "Price test card"))
        val input = specialInput().copy(
            name = "Price test card", setName = "Price test set", collectorNumber = "001/100",
            externalProductId = 42L, sourceSetId = "price-test", sourceCardId = "price-test-001",
            finish = "Reverse Holo", variant = "Reverse Holo",
        )
        assertEquals(AddCardOutcome.CREATED, repository.addManualCard(input))

        listOf<Long?>(null, 0L).forEachIndexed { index, reverseTrend ->
            dao.insertRawPrices(listOf(price(42L, (index + 1) * 1_000L, 532L, reverseTrend)))
            val row = dao.observeCollection("price-test-owner").first().single()
            assertNull(row.marketValueMinor)
            assertNull(row.priceUpdatedAtEpochMillis)
            val portfolio = repository.portfolio.first()
            assertEquals(0, portfolio.pricedCards)
            assertEquals(1, portfolio.unpricedCards)
            assertEquals(0.0, portfolio.totalValue, .001)
            assertTrue(portfolio.history.all { it.value == 0.0 })
            val binder = repository.binders.first().single()
            assertEquals(0.0, binder.marketValueEur, .001)
            assertNull(binder.lastPriceUpdatedAtEpochMillis)
            assertTrue(repository.priceHistory(portfolio.cards.single()).first().isEmpty())
        }
    }

    @Test fun catalogReferencesUseTheSameRegularAndReversePriceFields() = runBlocking {
        val dao = database.cardVaultDao()
        dao.insertCatalogCards(listOf(
            product(41L, "Holo test card").copy(finish = "Holo"),
            product(42L, "Reverse test card").copy(finish = "Reverse Holo"),
            product(43L, "Unpriced reverse test card").copy(finish = "Reverse Holo"),
        ))
        dao.insertRawPrices(listOf(
            price(41L, 1_000L, 532L, 914L),
            price(42L, 1_000L, 532L, 914L),
            price(43L, 1_000L, 532L, 0L),
        ))

        val references = dao.catalogReferencesForSet(source, "Cardmarket test set")
            .associateBy { it.externalProductId }
        assertEquals(532L, references.getValue(41L).marketValueMinor)
        assertEquals(914L, references.getValue(42L).marketValueMinor)
        assertNull(references.getValue(43L).marketValueMinor)
        assertNull(references.getValue(43L).priceUpdatedAtEpochMillis)
    }

    private fun product(id: Long, name: String) = CatalogCardEntity(
        id = "cm-pokemon-$id", tcg = "POKEMON", setId = "price-test-expansion",
        setName = "Cardmarket test set", name = name, collectorNumber = "",
        language = "UNKNOWN", finish = "UNKNOWN", variant = "Cardmarket-Produkt",
        createdAtEpochMillis = 1_000L, externalSource = source, externalProductId = id,
    )

    private fun price(id: Long, timestamp: Long, regular: Long, reverse: Long?) = RawPriceSnapshotEntity(
        cardId = "cm-pokemon-$id", source = source, currency = "EUR",
        lowValueMinor = null, averageValueMinor = null, trendValueMinor = regular,
        marketValueMinor = regular, holoTrendValueMinor = reverse,
        fetchedAtEpochMillis = timestamp, effectiveAtEpochMillis = timestamp,
    )

    private fun specialInput() = ManualCardInput(
        name = "Bauz", setName = "Optimale Ordnung", collectorNumber = "090/088",
        language = CardLanguage.GERMAN, finish = "Holo", variant = "Holo", quantity = 1,
        condition = "Nicht geprüft", notes = "", acquisitionPriceEur = null,
        acquisitionDateEpochDay = null, externalProductId = 877507L,
        cardmarketExpansionId = "6443", sourceSetId = "me03", sourceCardId = "me03-090",
    )
}
