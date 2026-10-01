package com.dominik.cardvault

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.BinderEntity
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.CollectionEntryEntity
import com.dominik.cardvault.data.local.RawPriceSnapshotEntity
import com.dominik.cardvault.data.local.SYNC_CLEAN
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import com.dominik.cardvault.model.BinderSummary
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CardPriceMetric
import com.dominik.cardvault.model.CardPriceSelection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Live real-Room repository subscriptions, not a new .first() subscription after every mutation. */
@RunWith(AndroidJUnit4::class)
class BinderMetadataFlowInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val dao get() = database.cardVaultDao()
    private val owner = MutableStateFlow(OWNER)
    private val metadataQueries = CopyOnWriteArrayList<String>()

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ ->
                val normalized = sql.replace(Regex("\\s+"), " ").trim()
                if (normalized.startsWith("SELECT e.binderId AS id,", ignoreCase = true)) metadataQueries.add(normalized)
            }, Executor { it.run() }).build()
        repository = RoomCollectionRepository(database, owner)
    }

    @After fun close() = database.close()

    @Test fun activeMetadataFlowPreservesCardinalityNamesOrderingAndOwnerIsolation() = runBlocking(Dispatchers.IO) {
        withTimeout(60_000) {
            val first = catalog("metadata-card-a", "Alpha")
            val second = catalog("metadata-card-z", "Zulu")
            val third = catalog("metadata-card-b", "beta")
            val hidden = catalog("metadata-card-hidden", "ZZZ-hidden")
            dao.insertCatalogCards(listOf(first, second, third, hidden))
            dao.insertBinder(BinderEntity("metadata-empty", "unused-set", "No owned cards", null, 1, 99_999))
            var aFirst = insert(entry("a-first", first.id, "binder-a", 2))
            var aDuplicate = insert(entry("a-duplicate", first.id, "binder-a", 3))
            var aSecond = insert(entry("a-second", second.id, "binder-a", 1))
            var b = insert(entry("b", third.id, "binder-b", 4))
            val deleted = insert(entry("deleted", hidden.id, "binder-a", 999)
                .copy(updatedAtEpochMillis = 99_999, deletedAtEpochMillis = 10))
            val foreign = insert(entry("foreign", hidden.id, "binder-a", 40)
                .copy(ownerUid = OTHER, updatedAtEpochMillis = 99_999))

            withBinderFeed { feed ->
                // Same cardId in two entries counts once, but all quantities count.
                // The final name is MAX(c.setName), not the persistent BinderEntity name.
                feed.await(listOf(summary("binder-b", "beta", 1, 4), summary("binder-a", "Zulu", 2, 6)))

                dao.upsertCatalogCards(listOf(second.copy(setName = "Aardvark")))
                // MAX(Alpha, Aardvark) is Alpha; equal recency sorts names with NOCASE.
                feed.await(listOf(summary("binder-a", "Alpha", 2, 6), summary("binder-b", "beta", 1, 4)))

                aFirst = aFirst.copy(quantity = 7)
                dao.updateCollectionEntry(aFirst)
                feed.await(listOf(summary("binder-a", "Alpha", 2, 11), summary("binder-b", "beta", 1, 4)))

                b = b.copy(updatedAtEpochMillis = 200)
                dao.updateCollectionEntry(b)
                feed.await(listOf(summary("binder-b", "beta", 1, 4), summary("binder-a", "Alpha", 2, 11)))

                aDuplicate = aDuplicate.copy(binderId = "binder-b", updatedAtEpochMillis = 250)
                dao.updateCollectionEntry(aDuplicate)
                feed.await(listOf(summary("binder-b", "beta", 2, 7), summary("binder-a", "Alpha", 2, 8)))

                aSecond = aSecond.copy(deletedAtEpochMillis = 300, updatedAtEpochMillis = 300)
                dao.updateCollectionEntry(aSecond)
                val remaining = listOf(summary("binder-b", "beta", 2, 7), summary("binder-a", "Alpha", 1, 7))
                feed.await(remaining)

                owner.value = OTHER
                feed.await(listOf(summary("binder-a", "ZZZ-hidden", 1, 40)))
                owner.value = OWNER
                feed.await(remaining)

                aFirst = aFirst.copy(deletedAtEpochMillis = 400, updatedAtEpochMillis = 400)
                dao.updateCollectionEntry(aFirst)
                feed.await(listOf(summary("binder-b", "beta", 2, 7)))
            }
            assertEquals(deleted, dao.getCollectionEntry(deleted.id, OWNER))
            assertEquals(foreign, dao.getCollectionEntry(foreign.id, OTHER))
            assertMetadataQueryUsesOnlyConsumedFields()
        }
    }

    @Test fun activeBinderPricesRefreshAcrossTrendAlternativeClearAndOwnerChanges() = runBlocking(Dispatchers.IO) {
        withTimeout(60_000) {
            val visual = catalog("visual-normal", "Normal set")
            val reverseVisual = catalog("visual-reverse", "Reverse set").copy(finish = "Reverse Holo", variant = "Reverse Holo")
            // Distinct visual and linked market rows exercise the real unique product join.
            val product = catalog("market-42", "Market source").copy(externalSource = SOURCE, externalProductId = 42)
            val reverseProduct = catalog("market-43", "Market source").copy(externalSource = SOURCE, externalProductId = 43)
            dao.insertCatalogCards(listOf(visual, reverseVisual, product, reverseProduct))
            val selected = insert(entry("selected", visual.id, "normal-binder", 2).copy(
                externalProductSource = SOURCE, externalProductId = 42,
                sourceSetId = "normal-set", sourceCardId = "normal-set-001",
            ))
            val reverse = insert(entry("reverse", reverseVisual.id, "reverse-binder", 1).copy(
                externalProductSource = SOURCE, externalProductId = 43, updatedAtEpochMillis = 90,
                sourceSetId = "reverse-set", sourceCardId = "reverse-set-001",
            ))
            val foreign = insert(entry("foreign-price", visual.id, "foreign-binder", 4).copy(
                ownerUid = OTHER, externalProductSource = SOURCE, externalProductId = 42,
                sourceSetId = "normal-set", sourceCardId = "normal-set-001",
            ))
            dao.insertRawPrices(listOf(price(product.id, 1_000, null), price(reverseProduct.id, 1_100, 900, holoTrend = 700)))
            val choice = CardPriceSelection("normal-set", "normal-set-001", "001/001", "Normal", "Standard",
                CardLanguage.GERMAN, 42, CardPriceMetric.NORMAL_AVG_SELL, 500, "EUR", 1_000, 1_500)
            val reverseSummary = summary("reverse-binder", "Reverse set", 1, 1, value = 7.0, at = 1_100, trend = 1)
            val unpriced = summary("normal-binder", "Normal set", 1, 2)
            val alternative = summary("normal-binder", "Normal set", 1, 2, value = 10.0, at = 1_000, alternative = 2)

            withBinderFeed { feed ->
                feed.await(listOf(unpriced, reverseSummary))
                // Real repository selection API verifies product, printing and available quote.
                repository.setPriceSelection(selected.id, choice)
                feed.await(listOf(alternative, reverseSummary))

                // Only raw_price_snapshots changes: metadata SQL must not be needed to refresh value.
                dao.insertRawPrices(listOf(price(product.id, 2_000, 300)))
                feed.await(listOf(summary("normal-binder", "Normal set", 1, 2, value = 6.0, at = 2_000, trend = 2), reverseSummary))

                dao.insertRawPrices(listOf(price(product.id, 3_000, null)))
                feed.await(listOf(alternative, reverseSummary))

                // Only the actual selection sidecar/outbox changes, never the collection entry.
                repository.clearPriceSelection(selected.id)
                feed.await(listOf(unpriced, reverseSummary))
                assertEquals(true, dao.priceSelection(OWNER, selected.syncId)?.cleared)

                owner.value = OTHER
                feed.await(listOf(summary("foreign-binder", "Normal set", 1, 4)))
                owner.value = OWNER
                feed.await(listOf(unpriced, reverseSummary))

                // A fresh valid choice after return must also update this same live subscriber.
                repository.setPriceSelection(selected.id, choice.copy(sourceAt = 3_000, selectedAt = 3_500))
                feed.await(listOf(alternative.copy(lastPriceUpdatedAtEpochMillis = 3_000), reverseSummary))
            }
            assertEquals(selected, dao.getCollectionEntry(selected.id, OWNER))
            assertEquals(reverse, dao.getCollectionEntry(reverse.id, OWNER))
            assertEquals(foreign, dao.getCollectionEntry(foreign.id, OTHER))
            assertNull(dao.priceSelection(OTHER, foreign.syncId))
            assertTrue("Actual binder metadata SELECT must execute too", metadataQueries.isNotEmpty())
        }
    }

    private fun assertMetadataQueryUsesOnlyConsumedFields() {
        assertTrue("Must inspect executed Room SQL, not just a source string", metadataQueries.isNotEmpty())
        val expected = "SELECT e.binderId AS id, MAX(c.setName) AS name, NULL AS heroAsset, " +
            "COUNT(DISTINCT e.cardId) AS uniqueCards, COALESCE(SUM(e.quantity), 0) AS ownedCards"
        metadataQueries.forEach { sql ->
            assertEquals("BINDER_METADATA_UNUSED_PRICE_AGGREGATES: only five consumed fields", expected, sql.substringBefore(" FROM "))
            assertFalse("Metadata must not join or calculate prices: $sql",
                Regex("\\b(linked|raw_price_snapshots|marketValueMinor|lastPriceUpdatedAtEpochMillis)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql))
        }
    }

    private suspend fun withBinderFeed(block: suspend (BinderFeed) -> Unit) = coroutineScope {
        val channel = Channel<List<BinderSummary>>(32)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { repository.binders.collect { channel.send(it) } }
        try { block(BinderFeed(channel)) }
        finally { collector.cancelAndJoin(); channel.close() }
    }

    private class BinderFeed(private val channel: Channel<List<BinderSummary>>) {
        suspend fun await(expected: List<BinderSummary>) = withTimeout(15_000) {
            // combine may emit an intermediate old/new pair. Require the complete exact
            // eventual state; do not assume one emission per transaction or recreate flows.
            while (true) if (channel.receive() == expected) return@withTimeout
        }
    }

    private fun catalog(id: String, setName: String) = CatalogCardEntity(
        id, "POKEMON", "set-$id", setName, "Card $id", "001/001", "GERMAN", "Normal", "Standard", 1,
    )
    private fun entry(syncId: String, cardId: String, binderId: String, quantity: Int) = CollectionEntryEntity(
        cardId = cardId, quantity = quantity, condition = "Near Mint", acquisitionPriceMinor = null,
        acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "fixture-$syncId",
        frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = 1, updatedAtEpochMillis = 100,
        binderId = binderId, ownerUid = OWNER, syncId = syncId, syncState = SYNC_CLEAN, remoteRevision = 1,
    )
    private suspend fun insert(value: CollectionEntryEntity) = value.copy(id = dao.insertCollectionEntry(value))
    private fun price(cardId: String, at: Long, trend: Long?, holoTrend: Long? = null) = RawPriceSnapshotEntity(
        cardId = cardId, source = SOURCE, currency = "EUR", lowValueMinor = 400, averageValueMinor = 500,
        trendValueMinor = trend, marketValueMinor = trend, holoTrendValueMinor = holoTrend,
        fetchedAtEpochMillis = at, effectiveAtEpochMillis = at,
    )
    private fun summary(id: String, name: String, unique: Int, owned: Int, value: Double = 0.0,
        at: Long? = null, alternative: Int = 0, trend: Int = 0) =
        BinderSummary(id, name, null, unique, owned, value, at, alternative, trend)

    companion object {
        private const val OWNER = "binder-metadata-owner"
        private const val OTHER = "binder-metadata-other"
        private const val SOURCE = CardmarketSyncContract.SOURCE
    }
}
