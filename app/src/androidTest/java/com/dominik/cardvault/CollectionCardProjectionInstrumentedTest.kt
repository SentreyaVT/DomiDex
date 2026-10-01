package com.dominik.cardvault

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.CollectionEntryEntity
import com.dominik.cardvault.data.local.PrivateBinderEntity
import com.dominik.cardvault.data.local.PrivateBinderPlacementEntity
import com.dominik.cardvault.data.local.RawPriceSnapshotEntity
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CardPriceMetric
import com.dominik.cardvault.model.CardPriceSelection
import com.dominik.cardvault.model.CollectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Value-parity control for the real Room mapper, not a timing SLA or an expected failure. */
@RunWith(AndroidJUnit4::class)
class CollectionCardProjectionInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val owner = MutableStateFlow(OWNER)
    private var syncRequests = 0
    private val dao get() = database.cardVaultDao()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java,
        ).build()
        repository = RoomCollectionRepository(database, owner, onSyncNeeded = { syncRequests++ },
            verifyGuestClaimOwner = { owner.value })
    }

    @After fun tearDown() = database.close()

    @Test fun completeCardKeepsMetadataAndSavedAlternativeWhenTrueTrendArrives() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(15_000L) {
            val entry = seed()
            val choice = selection()
            repository.setPriceSelection(entry.id, choice)
            assertEquals(1, syncRequests)
            val saved = requireNotNull(dao.priceSelection(OWNER, ENTRY_SYNC_ID))
            assertEquals(1_000L, saved.sourceAt)
            assertEquals(2_000L, saved.selectedAt)

            val expected = expectedCard(entry.id, choice)
            assertProjection(expected, 15.96)
            val selected = repository.portfolio.first().cards.single()
            assertNull(selected.trendValueEur)
            assertEquals(choice, selected.activePriceSelection)
            assertEquals(5.32, requireNotNull(selected.effectiveValueEur), 0.0)
            assertTrue(selected.usesPriceSelection)
            assertEquals("Gewählter Vergleichswert · Cardmarket-Durchschnitt", selected.valuationLabel)
            assertEquals(listOf(0.0), repository.portfolio.first().history.map { it.value })

            // Deliberately insert later then earlier: source time, not insert order, governs history.
            dao.insertRawPrices(listOf(price(3_000L, 700L), price(2_500L, 600L)))
            val withTrend = expected.copy(currentValueEur = 7.0, changePercent = 16.666666666666668,
                priceUpdatedAtEpochMillis = 3_000L)
            assertProjection(withTrend, 21.0)
            val priced = repository.portfolio.first().cards.single()
            assertEquals(7.0, requireNotNull(priced.trendValueEur), 0.0)
            assertNull(priced.activePriceSelection)
            assertEquals(7.0, requireNotNull(priced.effectiveValueEur), 0.0)
            assertFalse(priced.usesPriceSelection)
            assertEquals("Cardmarket-Trendpreis", priced.valuationLabel)
            assertEquals(choice, priced.priceSelection)
            assertEquals(listOf(0.0, 18.0, 21.0), repository.portfolio.first().history.map { it.value })
            assertEquals("Reading the new trend must not rewrite the chosen source snapshot",
                saved, dao.priceSelection(OWNER, ENTRY_SYNC_ID))
            assertEquals(1, syncRequests)
        }
    }

    @Test fun wrongSourceHidesSavedAlternativeWithoutLosingItAcrossOwnerSwitches() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(15_000L) {
            val entry = seed()
            val choice = selection()
            repository.setPriceSelection(entry.id, choice)
            val saved = requireNotNull(dao.priceSelection(OWNER, ENTRY_SYNC_ID))
            assertProjection(expectedCard(entry.id, choice), 15.96)

            // Retain the real saved sidecar; only the parent source ceases to be Cardmarket.
            dao.updateCollectionEntry(entry.copy(externalProductSource = "OTHER_PRICE_SOURCE"))
            val unpriced = expectedCard(entry.id, null)
            assertProjection(unpriced, 0.0)
            val card = repository.portfolio.first().cards.single()
            assertNull(card.priceSelection)
            assertNull(card.activePriceSelection)
            assertNull(card.trendValueEur)
            assertNull(card.effectiveValueEur)
            assertFalse(card.usesPriceSelection)
            assertEquals("Preis nicht verfügbar", card.valuationLabel)
            assertEquals(saved, dao.priceSelection(OWNER, ENTRY_SYNC_ID))

            owner.value = "projection-other-owner"
            val other = repository.portfolio.first()
            assertTrue(other.cards.isEmpty())
            assertTrue(other.history.isEmpty())
            assertTrue(repository.privateBinderContents(PRIVATE_BINDER).first().isEmpty())
            assertTrue(repository.binders.first().isEmpty())
            assertTrue(repository.privateBinders.first().isEmpty())
            assertTrue(dao.priceSelections(owner.value).isEmpty())

            owner.value = OWNER
            assertProjection(unpriced, 0.0)
            dao.updateCollectionEntry(entry)
            assertProjection(expectedCard(entry.id, choice), 15.96)
            assertEquals(saved, dao.priceSelection(OWNER, ENTRY_SYNC_ID))
            assertEquals(1, syncRequests)
        }
    }

    private suspend fun assertProjection(expected: CollectionCard, total: Double) {
        val portfolio = repository.portfolio.first()
        assertEquals("Independent oracle includes all 27 constructor fields", expected, portfolio.cards.single())
        assertEquals(expected, repository.privateBinderContents(PRIVATE_BINDER).first().single().card)
        assertEquals(total, portfolio.totalValue, .000001)
        // These summary flows use currentCardsFor, rather than portfolio's historical mapper input.
        assertEquals(total, repository.binders.first().single().marketValueEur, .000001)
        assertEquals(total, repository.privateBinders.first().single().marketValueEur, .000001)
    }

    /** Deliberately independent of DAO projections and product mapping/copy helpers. */
    private fun expectedCard(entryId: Long, choice: CardPriceSelection?) = CollectionCard(
        entryId = entryId,
        id = "projection-visual-card",
        name = "Mew · Projektion",
        set = "Projection Set",
        number = "090/088",
        language = CardLanguage.ENGLISH,
        finish = "Holo",
        variant = "Holo",
        condition = "Excellent",
        quantity = 3,
        currentValueEur = 0.0,
        changePercent = 0.0,
        priceUpdatedAtEpochMillis = null,
        notes = "Notiz λ · Sammlung",
        acquisitionPriceEur = 12.34,
        acquisitionDateEpochDay = 20_001L,
        accent = 0xFFCE77B7,
        binderId = "projection-set-binder",
        externalProductId = 42L,
        imageUrl = "https://example.invalid/projection-card.png",
        rarity = "SPECIAL_ILLUSTRATION_RARE",
        effectOverride = "anniversary30",
        marketSearchName = null,
        marketExpansionId = null,
        sourceSetId = "projection-set",
        sourceCardId = "projection-set-090",
        priceSelection = choice,
    )

    private suspend fun seed(): CollectionEntryEntity = database.withTransaction {
        dao.insertCatalogCard(CatalogCardEntity(
            id = "projection-visual-card", tcg = "POKEMON", setId = "projection-set", setName = "Projection Set",
            name = "Mew · Projektion", collectorNumber = "090/088", language = "ENGLISH",
            finish = "Normal", variant = "Standard", createdAtEpochMillis = 500L,
            externalSource = "TCGDEX_DE", imageUrl = "https://example.invalid/projection-card.png",
            rarity = "SPECIAL_ILLUSTRATION_RARE", effectOverride = "catalog-effect-not-the-entry-override",
        ))
        dao.insertCatalogCard(CatalogCardEntity(
            id = "cm-pokemon-42", tcg = "POKEMON", setId = "source-expansion", setName = "Market product set",
            name = "Separate market product", collectorNumber = "", language = "UNKNOWN", finish = "UNKNOWN",
            variant = "Cardmarket-Produkt", createdAtEpochMillis = 500L,
            externalSource = CardmarketSyncContract.SOURCE, externalProductId = 42L,
        ))
        dao.insertRawPrices(listOf(price(1_000L, null)))
        val entry = CollectionEntryEntity(
            cardId = "projection-visual-card", quantity = 3, condition = "Excellent",
            acquisitionPriceMinor = 1_234L, acquisitionCurrency = "EUR", acquisitionDateEpochDay = 20_001L,
            notes = "Notiz λ · Sammlung", frontPhotoUri = null, backPhotoUri = null,
            createdAtEpochMillis = 500L, updatedAtEpochMillis = 500L,
            externalProductSource = CardmarketSyncContract.SOURCE, externalProductId = 42L,
            binderId = "projection-set-binder", ownerUid = OWNER, syncId = ENTRY_SYNC_ID,
            finishOverride = "Holo", variantOverride = "Holo", effectOverride = "anniversary30",
            sourceSetId = "projection-set", sourceCardId = "projection-set-090",
        ).let { it.copy(id = dao.insertCollectionEntry(it)) }
        dao.insertPrivateBinder(PrivateBinderEntity(
            id = PRIVATE_BINDER, ownerUid = OWNER, name = "Private projection fixture",
            createdAtEpochMillis = 500L, updatedAtEpochMillis = 500L,
        ))
        dao.insertPrivateBinderPlacement(PrivateBinderPlacementEntity(
            id = "projection-placement", ownerUid = OWNER, binderId = PRIVATE_BINDER,
            collectionEntryId = entry.id, slotPosition = 4L, holoEffectId = "placement-only-effect",
            holoIntensity = .75f, createdAtEpochMillis = 500L, updatedAtEpochMillis = 500L,
        ))
        entry
    }

    private fun selection() = CardPriceSelection(
        "projection-set", "projection-set-090", "090/088", "Holo", "Holo", CardLanguage.ENGLISH,
        42L, CardPriceMetric.NORMAL_AVG_SELL, 532L, "EUR", 1_000L, 2_000L,
    )

    private fun price(at: Long, trend: Long?) = RawPriceSnapshotEntity(
        cardId = "cm-pokemon-42", source = CardmarketSyncContract.SOURCE, currency = "EUR",
        lowValueMinor = 450L, averageValueMinor = 532L, trendValueMinor = trend, marketValueMinor = trend,
        holoTrendValueMinor = 9_999L, fetchedAtEpochMillis = at, effectiveAtEpochMillis = at,
    )

    companion object {
        private const val OWNER = "projection-owner"
        private const val ENTRY_SYNC_ID = "projection-entry"
        private const val PRIVATE_BINDER = "projection-private-binder"
    }
}
