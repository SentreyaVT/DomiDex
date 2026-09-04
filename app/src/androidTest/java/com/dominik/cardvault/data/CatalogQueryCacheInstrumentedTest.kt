package com.dominik.cardvault.data

import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.CatalogSearchProjection
import com.dominik.cardvault.data.local.RawPriceSnapshotEntity
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room notifications against isolated fixtures; never opens the installed user's database. */
@RunWith(AndroidJUnit4::class)
class CatalogQueryCacheInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CardVaultDatabase::class.java,
        ).build()
        repository = RoomCollectionRepository(database)
    }

    @After fun closeDatabase() = database.close()

    @Test fun catalogInvalidationRefreshesEmptyAndPopulatedSearches() = runBlocking {
        val dao = database.cardVaultDao()
        // No sync-status collection: these reads can refresh only through table invalidation.
        assertTrue(repository.searchCatalog("Pikachu", 20).isEmpty())
        afterInvalidation("catalog_cards") { dao.insertCatalogCard(marketCard()) }

        val first = repository.searchCatalog("Pikachu", 20)
        assertEquals(42L, first.single().productId)
        assertSame(first, repository.searchCatalog("Pikachu", 20))

        afterInvalidation("catalog_cards") {
            dao.upsertCatalogCards(listOf(marketCard().copy(name = "Raichu", rarity = "RARE")))
        }
        assertTrue(repository.searchCatalog("Pikachu", 20).isEmpty())
        val renamed = repository.searchCatalog("Raichu", 20).single()
        assertEquals(42L, renamed.productId)
        assertEquals("Raichu", renamed.name)
        assertEquals("RARE", renamed.rarity)

        afterInvalidation("catalog_cards") {
            dao.insertCatalogCard(marketCard().copy(id = "cm-pokemon-43", externalProductId = 43L, name = "Raichu", setId = "6209"))
        }
        assertEquals(1, repository.searchCatalog("Raichu", 1).size)
        assertEquals(setOf(42L, 43L), repository.searchCatalog("Raichu", 20).map { it.productId }.toSet())
    }

    @Test fun priceInvalidationRefreshesBothTrendFieldsWithoutCatalogRevisionChanges() = runBlocking {
        val dao = database.cardVaultDao()
        repository.searchCatalog("Pikachu", 20)
        afterInvalidation("catalog_cards") { dao.insertCatalogCard(marketCard()) }
        val unpriced = repository.searchCatalog("Pikachu", 20).single()
        assertNull(unpriced.trendPriceEur)
        assertNull(unpriced.holoTrendPriceEur)

        afterInvalidation("raw_price_snapshots") {
            dao.insertRawPrices(listOf(priceSnapshot(trend = 345, holoTrend = 512)))
        }
        val original = repository.searchCatalog("Pikachu", 20)
        assertEquals(3.45, original.single().trendPriceEur!!, .001)
        assertEquals(5.12, original.single().holoTrendPriceEur!!, .001)
        assertSame(original, repository.searchCatalog("Pikachu", 20))

        afterInvalidation("raw_price_snapshots") {
            dao.insertRawPricesReplacing(listOf(priceSnapshot(trend = 789, holoTrend = 987)))
        }
        val replaced = repository.searchCatalog("Pikachu", 20).single()
        assertEquals(42L, replaced.productId)
        assertEquals(7.89, replaced.trendPriceEur!!, .001)
        assertEquals(9.87, replaced.holoTrendPriceEur!!, .001)

        afterInvalidation("raw_price_snapshots") {
            dao.insertRawPrices(listOf(priceSnapshot(trend = 923, holoTrend = 0).copy(effectiveAtEpochMillis = 2000)))
        }
        val latest = repository.searchCatalog("Pikachu", 20).single()
        assertEquals(9.23, latest.trendPriceEur!!, .001)
        assertNull(latest.holoTrendPriceEur)
    }

    @Test fun limitingBeforePriceLookupPreservesLegacyRowsAndOrdering() = runBlocking {
        val dao = database.cardVaultDao()
        val names = listOf("Pikachu", "pikachu", "Pikachu EX", "Team Pikachu", "Pikachu [promo]", "Raichu", "Pikachu")
        dao.upsertCatalogCards(names.mapIndexed { index, name ->
            marketCard().copy(id = "cm-pokemon-${42 + index}", externalProductId = 42L + index, name = name)
        } + listOf(
            marketCard().copy(id = "other-source", externalProductId = 100L, externalSource = "TCGDEX_DE"),
            marketCard().copy(id = "missing-product", externalProductId = null),
        ))
        dao.insertRawPrices(listOf(
            priceSnapshot(trend = 345, holoTrend = 512),
            priceSnapshot(trend = 789, holoTrend = 987).copy(effectiveAtEpochMillis = 2000),
            priceSnapshot(trend = 99999, holoTrend = 88888).copy(source = "UNRELATED_SOURCE", effectiveAtEpochMillis = 3000),
            priceSnapshot(trend = 456, holoTrend = 0).copy(cardId = "cm-pokemon-43"),
        ))
        for (query in listOf("Pikachu", "PIKACHU", "Pika", "chu", "Pika%EX", "missing", "")) {
            for (limit in listOf(0, 1, 3, 20, 500, -1)) {
                assertEquals(
                    "query=$query limit=$limit",
                    legacySearch(query, limit),
                    dao.searchCatalog(CardmarketSyncContract.SOURCE, query, limit),
                )
            }
        }
    }

    private suspend fun legacySearch(query: String, limit: Int): List<CatalogSearchProjection> = withContext(Dispatchers.IO) {
        // Freeze the previous SQL so this checks all fields and order against the former path.
        val sql = """
            SELECT c.externalProductId, c.name, c.setId, c.setName,
                   c.collectorNumber, c.language, c.finish, c.variant, c.imageUrl,
                   p.trendValueMinor AS marketValueMinor,
                   NULLIF(p.holoTrendValueMinor, 0) AS holoMarketValueMinor,
                   p.currency, c.rarity
            FROM catalog_cards c
            LEFT JOIN raw_price_snapshots p ON p.id = (
                SELECT p2.id FROM raw_price_snapshots p2
                WHERE p2.cardId = c.id AND p2.source = 'CARDMARKET_PUBLIC_DOWNLOAD'
                ORDER BY p2.effectiveAtEpochMillis DESC, p2.id DESC LIMIT 1
            )
            WHERE c.externalSource = ? AND c.externalProductId IS NOT NULL
              AND c.name LIKE '%' || ? || '%' COLLATE NOCASE
            ORDER BY CASE WHEN c.name = ? COLLATE NOCASE THEN 0
                          WHEN c.name LIKE ? || '%' COLLATE NOCASE THEN 1 ELSE 2 END,
                     c.name COLLATE NOCASE
            LIMIT ?
        """.trimIndent()
        database.query(SimpleSQLiteQuery(sql, arrayOf<Any>(CardmarketSyncContract.SOURCE, query, query, query, limit))).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(CatalogSearchProjection(
                    externalProductId = cursor.getLong(0), name = cursor.getString(1),
                    setId = cursor.getString(2), setName = cursor.getString(3), collectorNumber = cursor.getString(4),
                    language = cursor.getString(5), finish = cursor.getString(6), variant = cursor.getString(7),
                    imageUrl = if (cursor.isNull(8)) null else cursor.getString(8),
                    marketValueMinor = if (cursor.isNull(9)) null else cursor.getLong(9),
                    holoMarketValueMinor = if (cursor.isNull(10)) null else cursor.getLong(10),
                    currency = if (cursor.isNull(11)) null else cursor.getString(11),
                    rarity = if (cursor.isNull(12)) null else cursor.getString(12),
                ))
            }
        }
    }

    private suspend fun afterInvalidation(table: String, write: suspend () -> Unit) {
        val invalidated = CompletableDeferred<Unit>()
        val observer = object : InvalidationTracker.Observer(table) {
            override fun onInvalidated(tables: Set<String>) {
                if (table in tables) invalidated.complete(Unit)
            }
        }
        // The repository observer is already registered by the initial search. Room notifies
        // this later observer after it; no sleeps or forced tracker refresh hide lost callbacks.
        withContext(Dispatchers.IO) { database.invalidationTracker.addObserver(observer) }
        try {
            write()
            withTimeout(5000) { invalidated.await() }
        } finally {
            withContext(Dispatchers.IO) { database.invalidationTracker.removeObserver(observer) }
        }
    }

    // Same source/product and distinct Normal/Holo prices as CardVaultDatabaseTest's fixture.
    private fun marketCard() = CatalogCardEntity(
        id = "cm-pokemon-42", tcg = "POKEMON", setId = "1585", setName = "Cardmarket-Erweiterung 1585",
        name = "Pikachu", collectorNumber = "", language = "UNKNOWN", finish = "UNKNOWN",
        variant = "Cardmarket-Produkt", createdAtEpochMillis = 1000,
        externalSource = CardmarketSyncContract.SOURCE, externalProductId = 42L,
    )

    private fun priceSnapshot(trend: Long, holoTrend: Long) = RawPriceSnapshotEntity(
        cardId = "cm-pokemon-42", source = CardmarketSyncContract.SOURCE, currency = "EUR",
        lowValueMinor = 100, averageValueMinor = 200, trendValueMinor = trend, marketValueMinor = trend,
        holoTrendValueMinor = holoTrend, fetchedAtEpochMillis = 1000, effectiveAtEpochMillis = 1000,
    )
}
