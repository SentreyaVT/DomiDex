package com.dominik.cardvault

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.CollectionEntryEntity
import com.dominik.cardvault.data.local.OwnedPriceHistoryProjection
import com.dominik.cardvault.data.local.RawPriceSnapshotEntity
import com.dominik.cardvault.data.local.SYNC_CLEAN
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Genuine ongoing DAO subscriptions; no .first() recreation, SDK, worker, or timing SLA. */
@RunWith(AndroidJUnit4::class)
class OwnedHistoryFlowInvalidationInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val dao get() = database.cardVaultDao()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = database.close()

    @Test fun activeOwnedHistoryTracksPriceChangesTombstonesAndOwnerIsolation() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(60_000L) {
            // Reverse lexical/time order makes card-major output observably different
            // from the public chronological contract. No cross-product timestamp ties:
            // this test deliberately does not invent an ordering for unspecified ties.
            val visual = catalog(VISUAL)
            val z = catalog(PRODUCT_Z).copy(externalSource = SOURCE, externalProductId = 42L)
            val a = catalog(PRODUCT_A).copy(externalSource = SOURCE, externalProductId = 43L)
            val b = catalog(PRODUCT_B).copy(externalSource = SOURCE, externalProductId = 44L)
            dao.insertCatalogCards(listOf(visual, z, a, b))
            val zEntry = insert(entry("z-entry", VISUAL, OWNER).copy(
                externalProductSource = SOURCE, externalProductId = 42L,
            ))
            val aEntry = insert(entry("a-entry", PRODUCT_A, OWNER).copy(
                externalProductSource = SOURCE, externalProductId = 43L,
            ))
            val bEntry = insert(entry("b-entry", PRODUCT_B, OTHER).copy(
                externalProductSource = SOURCE, externalProductId = 44L,
            ))
            dao.insertRawPrices(listOf(
                price(PRODUCT_Z, 3_000L, 300L, 350L),
                price(PRODUCT_A, 1_000L, 100L, 150L),
                price(PRODUCT_B, 2_000L, 800L, 850L),
            ))

            withFeeds { mine, other ->
                mine.awaitExactly(listOf(p(PRODUCT_A, 1_000L, 100L, 150L), p(PRODUCT_Z, 3_000L, 300L, 350L)))
                other.awaitExactly(listOf(p(PRODUCT_B, 2_000L, 800L, 850L)))

                // Only the raw-price table changes. Earliest and latest dates are
                // inserted in neither chronological nor product order. The wrong-
                // source row and foreign-owner dates must never enter my result.
                database.withTransaction {
                    dao.insertRawPrices(listOf(
                        price(PRODUCT_A, 4_000L, null, 0L),
                        price(PRODUCT_Z, 750L, 99_999L, 88_888L).copy(source = OTHER_SOURCE),
                        price(PRODUCT_B, 2_500L, 900L, -1L),
                        price(PRODUCT_Z, 500L, 50L, 55L),
                    ))
                }
                mine.awaitExactly(listOf(
                    p(PRODUCT_Z, 500L, 50L, 55L), p(PRODUCT_A, 1_000L, 100L, 150L),
                    p(PRODUCT_Z, 3_000L, 300L, 350L), p(PRODUCT_A, 4_000L, null, 0L),
                ))
                other.awaitExactly(listOf(p(PRODUCT_B, 2_000L, 800L, 850L), p(PRODUCT_B, 2_500L, 900L, -1L)))

                // Same-source/date corrections replace the existing observations;
                // nullable/zero/negative fields are preserved, not pre-valued away.
                database.withTransaction {
                    dao.insertRawPricesReplacing(listOf(
                        price(PRODUCT_Z, 3_000L, null, -7L),
                        price(PRODUCT_B, 2_000L, -1L, null),
                    ))
                }
                mine.awaitExactly(listOf(
                    p(PRODUCT_Z, 500L, 50L, 55L), p(PRODUCT_A, 1_000L, 100L, 150L),
                    p(PRODUCT_Z, 3_000L, null, -7L), p(PRODUCT_A, 4_000L, null, 0L),
                ))
                other.awaitExactly(listOf(p(PRODUCT_B, 2_000L, -1L, null), p(PRODUCT_B, 2_500L, 900L, -1L)))

                // ONLY collection_entries changes: a raw-price-only observer would
                // now time out. No price mutation masks lost join invalidation.
                val tombstone = aEntry.copy(deletedAtEpochMillis = 9_000L, updatedAtEpochMillis = 9_000L)
                dao.updateCollectionEntry(tombstone)
                mine.awaitExactly(listOf(p(PRODUCT_Z, 500L, 50L, 55L), p(PRODUCT_Z, 3_000L, null, -7L)))

                // Another entry-only fixture mutation exercises both already active
                // owner predicates, without changing auth or scheduling anything.
                // This is SQL owner isolation, not a claim about permitted remote
                // ownership transfer or account-session switching.
                val reassigned = zEntry.copy(ownerUid = OTHER, updatedAtEpochMillis = 10_000L)
                dao.updateCollectionEntry(reassigned)
                mine.awaitExactly(emptyList())
                other.awaitExactly(listOf(
                    p(PRODUCT_Z, 500L, 50L, 55L), p(PRODUCT_B, 2_000L, -1L, null),
                    p(PRODUCT_B, 2_500L, 900L, -1L), p(PRODUCT_Z, 3_000L, null, -7L),
                ))

                assertEquals(tombstone, dao.getCollectionEntry(aEntry.id, OWNER))
                assertEquals(reassigned, dao.getCollectionEntry(zEntry.id, OTHER))
                assertNull(dao.getCollectionEntry(zEntry.id, OWNER))
                assertEquals(bEntry, dao.getCollectionEntry(bEntry.id, OTHER))
                // Filters must not physically prune excluded historical observations.
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM raw_price_snapshots").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(7L, it.getLong(0))
                }
            }
        }
    }

    private suspend fun withFeeds(block: suspend (Feed, Feed) -> Unit) = coroutineScope {
        val mine = Channel<List<OwnedPriceHistoryProjection>>(32)
        val other = Channel<List<OwnedPriceHistoryProjection>>(32)
        val collectors = listOf(
            launch(start = CoroutineStart.UNDISPATCHED) {
                dao.observeOwnedRawPriceHistory(OWNER, SOURCE).collect { mine.send(it) }
            },
            launch(start = CoroutineStart.UNDISPATCHED) {
                dao.observeOwnedRawPriceHistory(OTHER, SOURCE).collect { other.send(it) }
            },
        )
        try { block(Feed(mine), Feed(other)) }
        finally {
            withContext(NonCancellable) {
                collectors.forEach { it.cancel() }
                try { withTimeout(5_000L) { collectors.joinAll() } }
                finally { mine.close(); other.close() }
            }
        }
    }

    private class Feed(private val channel: Channel<List<OwnedPriceHistoryProjection>>) {
        suspend fun awaitExactly(expected: List<OwnedPriceHistoryProjection>) = withTimeout(10_000L) {
            // Room may publish already queued old states. Every required new state
            // differs from all its predecessors; an old equal response cannot prove
            // the next mutation. Never demand one emission per SQLite transaction.
            while (true) {
                val actual = channel.receive()
                assertTrue("Every public history emission must stay chronological",
                    actual.zipWithNext().all { (left, right) ->
                        left.effectiveAtEpochMillis <= right.effectiveAtEpochMillis
                    })
                if (actual == expected) return@withTimeout
            }
        }
    }

    private fun catalog(id: String) = CatalogCardEntity(
        id, "POKEMON", "history-fixture", "Synthetic history", "Synthetic $id", "001/001",
        "GERMAN", "Normal", "Standard", 1L,
    )
    private fun entry(syncId: String, cardId: String, owner: String) = CollectionEntryEntity(
        cardId = cardId, quantity = 1, condition = "Near Mint", acquisitionPriceMinor = null,
        acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "synthetic history only",
        frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
        ownerUid = owner, syncId = syncId, syncState = SYNC_CLEAN, remoteRevision = 1L,
    )
    private suspend fun insert(value: CollectionEntryEntity) = value.copy(id = dao.insertCollectionEntry(value))
    private fun price(cardId: String, at: Long, trend: Long?, reverse: Long?) = RawPriceSnapshotEntity(
        cardId = cardId, source = SOURCE, currency = "EUR", lowValueMinor = null, averageValueMinor = null,
        trendValueMinor = trend, marketValueMinor = trend, holoTrendValueMinor = reverse,
        fetchedAtEpochMillis = at, effectiveAtEpochMillis = at,
    )
    private fun p(cardId: String, at: Long, trend: Long?, reverse: Long?) =
        OwnedPriceHistoryProjection(cardId, at, trend, reverse)

    companion object {
        private const val OWNER = "history-flow-owner"
        private const val OTHER = "history-flow-other"
        private const val SOURCE = CardmarketSyncContract.SOURCE
        private const val OTHER_SOURCE = "OTHER_HISTORY_SOURCE"
        private const val VISUAL = "history-visual-z"
        private const val PRODUCT_Z = "history-z-product"
        private const val PRODUCT_A = "history-a-product"
        private const val PRODUCT_B = "history-b-product"
    }
}
