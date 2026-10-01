package com.dominik.cardvault

import androidx.room.Room
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.backup.UserBackupStore
import com.dominik.cardvault.data.local.*
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import com.dominik.cardvault.data.sync.deferredPriceSelectionParentPending
import com.dominik.cardvault.data.sync.privateSyncContinuation
import com.dominik.cardvault.data.sync.PrivateSyncContinuation
import com.dominik.cardvault.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PriceSelectionRepositoryInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val owner = MutableStateFlow("price-owner")
    private val source = CardmarketSyncContract.SOURCE

    @Before fun setUp() {
        database = newDatabase()
        repository = RoomCollectionRepository(database, owner, verifyGuestClaimOwner = { owner.value })
    }
    @After fun tearDown() = database.close()

    @Test fun candidatesStayExplicitAndSelectionFeedsEveryTotalWithoutWritingFakeTrend() = runBlocking {
        seed()
        repository.addManualCard(input())
        val original = repository.portfolio.first().cards.single()
        assertNull(original.effectiveValueEur)
        val product = repository.catalogProducts(listOf(42L)).single()
        assertEquals(5, product.priceAlternativesFor("Holo").size)
        assertEquals(2, product.priceAlternativesFor("Reverse Holo").size)
        repository.setPriceSelection(original.entryId, selection())
        val binderId = repository.createPrivateBinder("Auswahl", null, "DOMIDEX")
        repository.assignToPrivateBinder(binderId, original.entryId, 0)

        val portfolio = repository.portfolio.first()
        val card = portfolio.cards.single()
        assertEquals(10.64, portfolio.totalValue, .0001)
        assertEquals(2, portfolio.alternativePricedCards)
        assertEquals(0, portfolio.trendPricedCards)
        assertEquals(10.64, repository.binders.first().single().marketValueEur, .0001)
        assertEquals(10.64, repository.privateBinders.first().single().marketValueEur, .0001)
        assertEquals(2, repository.privateBinders.first().single().alternativePricedCards)
        assertEquals(selection(), card.priceSelection)
        assertEquals(0.0, card.currentValueEur, .0)
        assertNull(card.priceUpdatedAtEpochMillis)
        assertTrue(repository.priceHistory(card).first().isEmpty())
        assertTrue(portfolio.history.all { it.value == 0.0 })
        val row = database.cardVaultDao().observeCollection(owner.value).first().single()
        assertNull(row.marketValueMinor)
        assertNull(row.priceUpdatedAtEpochMillis)
        assertEquals(1, database.cardVaultDao().pendingPrivateBinderSyncOutbox(owner.value, 100)
            .count { it.recordType == "PRICE_SELECTION" })
    }

    @Test fun laterTrueTrendWinsWithoutDeletingTheSnapshotAndClearIsPersistent() = runBlocking {
        seed()
        repository.addManualCard(input().copy(priceSelection = selection()))
        val entry = repository.portfolio.first().cards.single()
        database.cardVaultDao().insertRawPrices(listOf(price(3_000L).copy(trendValueMinor = 700L, marketValueMinor = 700L)))
        val priced = repository.portfolio.first().cards.single()
        assertEquals(7.0, priced.effectiveValueEur!!, .0)
        assertFalse(priced.usesPriceSelection)
        assertEquals(selection(), priced.priceSelection)
        assertTrue(repository.catalogProducts(listOf(42L)).single().priceAlternativesFor("Holo").isEmpty())
        assertTrue(runCatching { repository.setPriceSelection(entry.entryId, selection()) }.isFailure)
        repository.clearPriceSelection(entry.entryId)
        val syncId = database.cardVaultDao().getCollectionEntry(entry.entryId, owner.value)!!.syncId
        val cleared = database.cardVaultDao().priceSelection(owner.value, syncId)!!
        assertTrue(cleared.cleared)
        assertNull(cleared.amountMinor)
        assertNull(cleared.sourceCardId)
        assertNull(cleared.selectedAt)
        database.cardVaultDao().insertRawPrices(listOf(price(4_000L)))
        assertNull(RoomCollectionRepository(database, owner).portfolio.first().cards.single().effectiveValueEur)
    }

    @Test fun invalidIdentityOrChangedSourceRollsBackAddAndNeverIncrementsQuantity() = runBlocking {
        seed()
        assertTrue(runCatching {
            repository.addManualCard(input().copy(priceSelection = selection().copy(sourceSetId = "wrong")))
        }.isFailure)
        assertTrue(repository.portfolio.first().cards.isEmpty())
        assertTrue(database.cardVaultDao().pendingSyncOutbox(owner.value).isEmpty())
        assertTrue(database.cardVaultDao().priceSelections(owner.value).isEmpty())

        repository.addManualCard(input().copy(priceSelection = selection()))
        listOf(
            selection().copy(productId = 43L), selection().copy(sourceCardId = "me03-091"),
            selection().copy(collectorNumber = "090/100"), selection().copy(variant = "Stamped"),
            selection().copy(language = CardLanguage.ENGLISH), selection().copy(amountMinor = 999L),
            selection().copy(sourceAt = 999L),
        ).forEach { wrong ->
            assertTrue(runCatching { repository.addManualCard(input().copy(priceSelection = wrong)) }.isFailure)
            assertEquals(2, repository.portfolio.first().totalCards)
            assertEquals(selection(), repository.portfolio.first().cards.single().priceSelection)
        }
    }

    @Test fun reverseNeverBorrowsNormalValuesAndOwnerSwitchNeverExposesTheChoice() = runBlocking {
        seed()
        val reverseInput = input().copy(finish = "Reverse Holo", variant = "Reverse Holo")
        repository.addManualCard(reverseInput)
        val card = repository.portfolio.first().cards.single()
        val product = repository.catalogProducts(listOf(42L)).single()
        val reverse = product.priceAlternativesFor(card.finish).first().toSelection(card, 2_000L)
        assertTrue(reverse.metric.reverse)
        repository.setPriceSelection(card.entryId, reverse)
        assertTrue(runCatching { repository.setPriceSelection(card.entryId, reverse.copy(metric = CardPriceMetric.NORMAL_LOW)) }.isFailure)
        assertEquals(8.0, repository.portfolio.first().cards.single().effectiveValueEur!!, .0)
        owner.value = "other-owner"
        assertTrue(repository.portfolio.first().cards.isEmpty())
        assertTrue(runCatching { repository.setPriceSelection(card.entryId, reverse) }.isFailure)
        owner.value = "price-owner"
        assertEquals(reverse, repository.portfolio.first().cards.single().priceSelection)
    }

    @Test fun backupRestoresSnapshotWithoutMarketCacheAndAcceptsV26Backup() = runBlocking(Dispatchers.IO) {
        seed()
        repository.addManualCard(input().copy(priceSelection = selection()))
        val snapshot = UserBackupStore(database, owner.value).exportJson()
        val target = newDatabase()
        try {
            val store = UserBackupStore(target, "restored-owner")
            assertEquals(1, store.restore(snapshot).addedCards)
            val restored = RoomCollectionRepository(target, MutableStateFlow("restored-owner"))
            assertEquals(selection(), restored.portfolio.first().cards.single().priceSelection)
            assertEquals(10.64, restored.portfolio.first().totalValue, .0001)
            assertTrue(store.restore(snapshot).alreadyImported)
            val old = JSONObject(snapshot).put("databaseVersion", 12)
            old.getJSONObject("tables").remove("collection_price_selections")
            assertNotNull(store.validate(old.toString()))
            val bad = JSONObject(snapshot)
            bad.getJSONObject("tables").getJSONArray("collection_price_selections").getJSONObject(0)
                .put("entrySyncId", "foreign-entry")
            assertTrue(runCatching { store.validate(bad.toString()) }.isFailure)
        } finally { target.close() }
    }

    @Test fun explicitGuestClaimMovesSelectionAndQueuesItsOwnSidecar() = runBlocking {
        owner.value = GUEST_OWNER_UID
        seed()
        repository.addManualCard(input().copy(priceSelection = selection()))
        val entryId = repository.portfolio.first().cards.single().entryId
        owner.value = "claimed-owner"
        assertEquals(1, repository.claimGuestCollectionForCurrentUser())
        assertTrue(database.cardVaultDao().priceSelections(GUEST_OWNER_UID).isEmpty())
        val stored = database.cardVaultDao().priceSelections(owner.value).single()
        assertEquals(database.cardVaultDao().getCollectionEntry(entryId, owner.value)!!.syncId, stored.entrySyncId)
        assertEquals(selection(), repository.portfolio.first().cards.single().priceSelection)
        assertEquals(1, database.cardVaultDao().pendingPrivateBinderSyncOutbox(owner.value, 100)
            .count { it.recordType == "PRICE_SELECTION" })
    }

    @Test fun reopeningDatabaseKeepsTheChosenSnapshotAndItsPendingRevision() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "price-selection-restart-${UUID.randomUUID()}.db"
        var persistent = Room.databaseBuilder(context, CardVaultDatabase::class.java, name).build()
        try {
            seed(persistent)
            val first = RoomCollectionRepository(persistent, owner)
            first.addManualCard(input().copy(priceSelection = selection()))
            persistent.close()
            persistent = Room.databaseBuilder(context, CardVaultDatabase::class.java, name).build()
            val restarted = RoomCollectionRepository(persistent, owner)
            val card = restarted.portfolio.first().cards.single()
            assertEquals(selection(), card.priceSelection)
            assertEquals(5.32, card.effectiveValueEur!!, .0001)
            val mutation = persistent.cardVaultDao().pendingPrivateBinderSyncOutbox(owner.value, 100)
                .single { it.recordType == "PRICE_SELECTION" }
            assertEquals(0L, mutation.expectedRemoteRevision)
            restarted.clearPriceSelection(card.entryId)
            persistent.close()
            persistent = Room.databaseBuilder(context, CardVaultDatabase::class.java, name).build()
            assertNull(RoomCollectionRepository(persistent, owner).portfolio.first().cards.single().effectiveValueEur)
        } finally {
            persistent.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun parentAcknowledgedDuringPullContinuesSidecarWithoutAnArtificialRetry() = runBlocking {
        seed()
        repository.addManualCard(input().copy(priceSelection = selection()))
        val dao = database.cardVaultDao()
        val sidecar = dao.pendingPrivateBinderSyncOutbox(owner.value, 100).single { it.recordType == "PRICE_SELECTION" }
        val deferred = setOf(sidecar.entryKey)
        val parent = dao.syncOutbox("${owner.value}:${sidecar.recordId}")!!
        assertTrue(deferredPriceSelectionParentPending(dao, owner.value, deferred))
        dao.markOutboxConflict(parent.entryKey, parent.mutationId, "fixture-conflict")
        assertEquals(PrivateSyncContinuation.RETRY, privateSyncContinuation(
            deferredPriceSelectionParentPending(dao, owner.value, deferred), false, true))
        // Simulate the authoritative parent ACK's final atomic outbox removal, without networking.
        dao.deleteSyncOutboxIfCurrent(parent.entryKey, parent.mutationId)
        assertEquals(PrivateSyncContinuation.APPEND, privateSyncContinuation(
            deferredPriceSelectionParentPending(dao, owner.value, deferred), false, true))
        assertEquals(sidecar, dao.privateBinderSyncOutbox(sidecar.entryKey))
        assertFalse(deferredPriceSelectionParentPending(dao, "other-owner", deferred))
        assertEquals(selection(), repository.portfolio.first().cards.single().priceSelection)
    }

    private fun newDatabase() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java,
    ).build()

    private suspend fun seed(target: CardVaultDatabase = database) {
        target.cardVaultDao().insertCatalogCard(CatalogCardEntity(
            id = "cm-pokemon-42", tcg = "POKEMON", setId = "6443", setName = "Source product",
            name = "Rowlet", collectorNumber = "", language = "UNKNOWN", finish = "UNKNOWN",
            variant = "Cardmarket-Produkt", createdAtEpochMillis = 1_000L,
            externalSource = source, externalProductId = 42L,
        ))
        target.cardVaultDao().insertRawPrices(listOf(price(1_000L)))
    }

    private fun price(at: Long) = RawPriceSnapshotEntity(
        cardId = "cm-pokemon-42", source = source, currency = "EUR", lowValueMinor = 450L,
        averageValueMinor = 532L, trendValueMinor = null, marketValueMinor = null,
        average1DayValueMinor = 500L, average7DayValueMinor = 510L, average30DayValueMinor = 520L,
        holoAverageValueMinor = 800L, holoLowValueMinor = 700L, holoTrendValueMinor = null,
        fetchedAtEpochMillis = at, effectiveAtEpochMillis = at,
    )

    private fun input() = ManualCardInput(
        "Bauz", "Optimale Ordnung", "090/088", CardLanguage.GERMAN, "Holo", "Holo", 2,
        "Near Mint", "", null, null, externalProductId = 42L, cardmarketExpansionId = "6443",
        sourceSetId = "me03", sourceCardId = "me03-090",
    )

    private fun selection() = CardPriceSelection("me03", "me03-090", "090/088", "Holo", "Holo",
        CardLanguage.GERMAN, 42L, CardPriceMetric.NORMAL_AVG_SELL, 532L, "EUR", 1_000L, 2_000L)
}
