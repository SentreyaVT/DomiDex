package com.dominik.cardvault.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogSyncStateEntity
import com.dominik.cardvault.model.CardPriceMetric
import com.dominik.cardvault.model.priceAlternativesFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import okio.Buffer

/** Real importer persistence and DAO reads, not a provider download or WorkManager test. */
@RunWith(AndroidJUnit4::class)
class CardmarketImportRoomInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val source = CardmarketSyncContract.SOURCE
    private val sourceTime = "2026-09-22T02:48:57+0200"
    private val sourceAt = Instant.parse("2026-09-22T00:48:57Z").toEpochMilli()
    private val fetchedAt = sourceAt + 86_400_000L

    @Before fun setUp() {
        // One isolated connection also keeps the TEMP rollback probe on the writer.
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CardVaultDatabase::class.java,
        ).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
    }

    @After fun tearDown() = database.close()

    @Test fun nullTrendPreservesEveryAlternativeAndOriginalSourceTime() = runBlocking(Dispatchers.IO) {
        persistCardmarketImport(database, prepared(listOf(product(42)), listOf(price(42))), fetchedAt)

        val raw = rows("raw_price_snapshots").single()
        assertEquals("cm-pokemon-42", raw["cardId"])
        assertEquals(source, raw["source"])
        assertEquals("EUR", raw["currency"])
        assertNull(raw["trendValueMinor"])
        assertNull(raw["marketValueMinor"])
        assertNull(raw["holoTrendValueMinor"])
        assertEquals("532", raw["averageValueMinor"])
        assertEquals("450", raw["lowValueMinor"])
        assertEquals("500", raw["average1DayValueMinor"])
        assertEquals("510", raw["average7DayValueMinor"])
        assertEquals("520", raw["average30DayValueMinor"])
        assertEquals("800", raw["holoAverageValueMinor"])
        assertEquals("700", raw["holoLowValueMinor"])
        assertEquals(sourceAt.toString(), raw["effectiveAtEpochMillis"])
        assertEquals(fetchedAt.toString(), raw["fetchedAtEpochMillis"])

        val catalog = rows("catalog_cards").single()
        assertEquals(source, catalog["externalSource"])
        assertEquals("42", catalog["externalProductId"])
        assertEquals("6601", catalog["setId"])
        assertEquals("", catalog["collectorNumber"]) // No collector/printing identity invented by import.

        val result = RoomCollectionRepository(database).catalogProducts(listOf(42L)).single()
        assertNull(result.trendPriceEur)
        assertNull(result.holoTrendPriceEur)
        val normal = result.priceAlternativesFor("Holo")
        val reverse = result.priceAlternativesFor("Reverse Holo")
        assertEquals(mapOf(
            CardPriceMetric.NORMAL_AVG_SELL to 532L,
            CardPriceMetric.NORMAL_LOW to 450L,
            CardPriceMetric.NORMAL_AVG_1D to 500L,
            CardPriceMetric.NORMAL_AVG_7D to 510L,
            CardPriceMetric.NORMAL_AVG_30D to 520L,
        ), normal.associate { it.metric to it.amountMinor })
        assertEquals(mapOf(
            CardPriceMetric.REVERSE_HOLO_AVG_SELL to 800L,
            CardPriceMetric.REVERSE_HOLO_LOW to 700L,
        ), reverse.associate { it.metric to it.amountMinor })
        assertTrue((normal + reverse).all { it.productId == 42L && it.currency == "EUR" && it.sourceAt == sourceAt })
        assertEquals(
            CatalogSyncStateEntity(source, "SUCCESS", fetchedAt, fetchedAt, sourceTime, 1, 1, null),
            database.cardVaultDao().syncState(source),
        )
    }

    @Test fun sameSourceTimeReplacesFieldsWithoutDuplicatingPriceHistory() = runBlocking(Dispatchers.IO) {
        persistCardmarketImport(database, prepared(
            listOf(product(42), product(43)), listOf(price(42), price(43).copy(trendMinor = 900)),
        ), fetchedAt)
        val untouchedProduct = rows("catalog_cards").single { it["id"] == "cm-pokemon-43" }
        val untouchedPrice = rows("raw_price_snapshots").single { it["cardId"] == "cm-pokemon-43" }
        val nextFetch = fetchedAt + 5_000
        val updated = price(42).copy(
            averageMinor = 620, lowMinor = 510, average1Minor = null, average7Minor = 555,
            average30Minor = 600, holoAverageMinor = 920, holoLowMinor = 830,
        )

        persistCardmarketImport(database, prepared(
            listOf(product(42).copy(name = "Updated exact product")), listOf(updated),
        ), nextFetch)

        val prices = rows("raw_price_snapshots")
        assertEquals(2, prices.size)
        val replaced = prices.single { it["cardId"] == "cm-pokemon-42" }
        assertEquals(sourceAt.toString(), replaced["effectiveAtEpochMillis"])
        assertEquals(nextFetch.toString(), replaced["fetchedAtEpochMillis"])
        assertNull(replaced["average1DayValueMinor"])
        assertNull(replaced["trendValueMinor"])
        assertNull(replaced["marketValueMinor"])
        assertNull(replaced["holoTrendValueMinor"])
        assertEquals(untouchedPrice, prices.single { it["cardId"] == "cm-pokemon-43" })
        assertEquals(untouchedProduct, rows("catalog_cards").single { it["id"] == "cm-pokemon-43" })

        val result = RoomCollectionRepository(database).catalogProducts(listOf(42L)).single()
        assertEquals("Updated exact product", result.name)
        assertEquals(mapOf(
            CardPriceMetric.NORMAL_AVG_SELL to 620L,
            CardPriceMetric.NORMAL_LOW to 510L,
            CardPriceMetric.NORMAL_AVG_7D to 555L,
            CardPriceMetric.NORMAL_AVG_30D to 600L,
        ), result.priceAlternativesFor("Holo").associate { it.metric to it.amountMinor })
        assertEquals(mapOf(
            CardPriceMetric.REVERSE_HOLO_AVG_SELL to 920L,
            CardPriceMetric.REVERSE_HOLO_LOW to 830L,
        ), result.priceAlternativesFor("Reverse Holo").associate { it.metric to it.amountMinor })
        assertTrue(result.priceAlternatives.all { it.sourceAt == sourceAt })
        assertEquals(
            CatalogSyncStateEntity(source, "SUCCESS", nextFetch, nextFetch, sourceTime, 1, 1, null),
            database.cardVaultDao().syncState(source),
        )
    }

    @Test fun lateSqliteFailureRollsBackAllChunksAndSuccessState() = runBlocking(Dispatchers.IO) {
        persistCardmarketImport(database, prepared(
            listOf(product(1), product(2_001)), listOf(price(1).copy(trendMinor = 99), price(2_001)),
        ), fetchedAt)
        val before = snapshot()
        val nextSourceTime = "2026-09-23T02:48:57+0200"
        val nextSourceAt = sourceAt + 86_400_000L
        val nextFetch = fetchedAt + 86_400_000L
        val incoming = prepared(
            (1L..1_001L).map { product(it).copy(name = "incoming-$it") },
            (1L..1_001L).map { price(it).copy(averageMinor = 700 + it) },
            nextSourceTime,
        )
        val sql = database.openHelper.writableDatabase
        // AFTER INSERT proves even the SUCCESS row has been written. The distinct
        // error is only raised when both 1,000-row boundaries have really been crossed.
        sql.execSQL("""
            CREATE TEMP TRIGGER cardmarket_import_rollback_probe
            AFTER INSERT ON main.catalog_sync_state
            WHEN NEW.source = '$source' AND NEW.status = 'SUCCESS'
                 AND NEW.lastAttemptAtEpochMillis = $nextFetch
            BEGIN
                SELECT CASE WHEN
                    (SELECT COUNT(*) FROM main.catalog_cards WHERE externalSource = '$source') = 1002
                    AND (SELECT name FROM main.catalog_cards WHERE id = 'cm-pokemon-1') = 'incoming-1'
                    AND (SELECT COUNT(*) FROM main.raw_price_snapshots
                         WHERE source = '$source' AND effectiveAtEpochMillis = $nextSourceAt) = 1001
                    AND (SELECT averageValueMinor FROM main.raw_price_snapshots
                         WHERE cardId = 'cm-pokemon-1001' AND source = '$source'
                               AND effectiveAtEpochMillis = $nextSourceAt) = 1701
                    AND (SELECT status FROM main.catalog_sync_state WHERE source = '$source') = 'SUCCESS'
                    AND NEW.catalogProductCount = 1001 AND NEW.priceRecordCount = 1001
                    THEN RAISE(ABORT, 'CARDMARKET_ATOMIC_AFTER_ALL_CHUNKS')
                    ELSE RAISE(ABORT, 'CARDMARKET_ATOMIC_TOO_EARLY') END;
            END
        """.trimIndent())
        try {
            val failure = runCatching { persistCardmarketImport(database, incoming, nextFetch) }.exceptionOrNull()
            assertNotNull("The isolated SQLite trigger must abort the real transaction", failure)
            val causes = generateSequence(requireNotNull(failure)) { it.cause }.toList()
            assertTrue(causes.any { it is SQLiteException })
            assertTrue("Failure must prove completed chunks and inserted SUCCESS: $failure",
                causes.any { it.message.orEmpty().contains("CARDMARKET_ATOMIC_AFTER_ALL_CHUNKS") })
            assertEquals("Every product, raw price and sync-state column must roll back", before, snapshot())
        } finally {
            sql.execSQL("DROP TRIGGER IF EXISTS temp.cardmarket_import_rollback_probe")
        }

        // Identical data succeeds without the test-only trigger; no production failhook exists.
        persistCardmarketImport(database, incoming, nextFetch)
        assertEquals(1_002, rows("catalog_cards").size)
        assertEquals(1_003, rows("raw_price_snapshots").size)
        assertEquals(
            CatalogSyncStateEntity(source, "SUCCESS", nextFetch, nextFetch, nextSourceTime, 1_001, 1_001, null),
            database.cardVaultDao().syncState(source),
        )
    }

    private fun product(id: Long) = CardmarketProduct(id, "Product $id", 1, 6_601, null)

    @Test fun contradictoryRowsCannotReplaceTheLastGoodRoomSnapshotInEitherOrder() = runBlocking(Dispatchers.IO) {
        persistCardmarketImport(database, prepared(
            listOf(product(42), product(43)),
            listOf(price(42).copy(trendMinor = 1000, holoTrendMinor = 2000), price(43)),
        ), fetchedAt)
        val before = snapshot()
        val first = duplicateGuideRow(trend = "2.25", reverse = "3.75")
        val conflicts = listOf(
            duplicateGuideRow(trend = "4.50", reverse = "3.75"),
            duplicateGuideRow(trend = "2.25", reverse = "7.50"),
            duplicateGuideRow(trend = "2.25", reverse = "3.75", average = "null"),
        )
        conflicts.forEach { other ->
            listOf(listOf(first, other), listOf(other, first)).forEach { rows ->
                val guide = CardmarketParsers.prices(Buffer().writeUtf8(
                    """{"version":1,"createdAt":"$sourceTime","priceGuides":[${rows.joinToString(",")}]}""",
                ))
                assertEquals("The actual parser must deliver both rows", 2, guide.records.size)
                assertNotEquals(guide.records[0], guide.records[1])
                val incomingProducts = ParsedDownload(1, sourceTime, listOf(
                    product(42).copy(name = "Must not overwrite the original"), product(44),
                ))
                val failure = runCatching {
                    // These are the same preflight and transaction called by CardmarketImporter.
                    // Deliberately do not manufacture its separate RUNNING/ERROR catch state.
                    val incoming = prepareCardmarketImport(incomingProducts, guide)
                    persistCardmarketImport(database, incoming, fetchedAt + 5000)
                }.exceptionOrNull()
                assertEquals("Rejected input cannot change products, prices or SUCCESS metadata", before, snapshot())
                assertTrue("A contradictory guide must fail in preflight", failure is IllegalArgumentException)
            }
        }
    }

    private fun duplicateGuideRow(trend: String, reverse: String, average: String = "5.32") =
        """{"idProduct":42,"idCategory":1,"avg":$average,"low":4.50,"trend":$trend,
            "avg1":5.00,"avg7":5.10,"avg30":5.20,"avg-holo":8.00,"low-holo":7.00,
            "trend-holo":$reverse}"""

    private fun price(id: Long) = CardmarketPrice(
        productId = id, categoryId = 1, averageMinor = 532, lowMinor = 450, trendMinor = null,
        average1Minor = 500, average7Minor = 510, average30Minor = 520,
        holoAverageMinor = 800, holoLowMinor = 700, holoTrendMinor = null,
    )

    private fun prepared(
        products: List<CardmarketProduct>, prices: List<CardmarketPrice>, createdAt: String = sourceTime,
    ) = prepareCardmarketImport(
        ParsedDownload(1, "2026-01-01T00:00:00Z", products), // The guide's date must win.
        ParsedDownload(1, createdAt, prices),
    )

    private fun snapshot() = listOf("catalog_cards", "raw_price_snapshots", "catalog_sync_state")
        .associateWith(::rows)

    private fun rows(table: String): List<Map<String, String?>> {
        require(table in setOf("catalog_cards", "raw_price_snapshots", "catalog_sync_state"))
        val key = if (table == "catalog_sync_state") "source" else "id"
        return database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY $key").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.columnNames.mapIndexed { index, name ->
                    name to if (cursor.isNull(index)) null else cursor.getString(index)
                }.toMap())
            }
        }
    }
}
