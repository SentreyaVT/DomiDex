package com.dominik.cardvault

import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in LOCAL ROOM benchmark, NOT a Firebase/network/UI/real-device benchmark.
 * No Activity, Firebase client, WorkManager enqueue, product DB singleton or scanner images.
 * Each database has a fresh test-only name and is removed in finally; reports stay in cacheDir.
 *
 * Instrumentation args:
 *   release27RoomScale=true                 (otherwise opt-in tests SKIP)
 *   release27RoomAnnualHistory=2-vs-365      (separate annual method; size1000/repeats3 only)
 *   release27RoomSizes=1000,5000,20000       (nonempty subset of these exact sizes)
 *   release27RoomRepeats=3                  (3..10, warm read samples / fresh migrations)
 *   release27RoomLabel=baseline             (safe report filename component)
 *
 * Timings have NO pass/fail SLA. First-read means first subscription AFTER seed/hash, not cold
 * disk or cold process. Warm samples resubscribe to real DAO/repository flows in the same DB.
 * QueryCallback counts SQL delivered by Room (including observer bookkeeping), not disk I/O.
 * Memory is whole-process before/after PSS/heap snapshots, NOT peak memory or a leak verdict.
 * The 180s coroutine timeout is a hung-test guard, not a product performance requirement.
 */
@RunWith(AndroidJUnit4::class)
class Release27RoomScaleInstrumentedTest {
    @get:Rule val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), CardVaultDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun localRoomReadFlowsAtSyntheticScale() = runBlocking<Unit> {
        val config = config()
        val report = report(config, "localRoomReadFlows")
        try {
            for (size in config.sizes) {
                val name = freshName("read")
                val counter = SqlCounter()
                val db = Room.databaseBuilder(context, CardVaultDatabase::class.java, name)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> counter.record(sql) }, Executor { it.run() })
                    .build()
                try {
                    val sql = db.openHelper.writableDatabase
                    measured(report, "seed", size, -1, name, counter) { seed(sql, size) }
                    assertSeed(sql, size)
                    val before = hashes(sql)
                    report.getJSONArray("integrity").put(JSONObject().put("size", size).put("before", hashJson(before)))
                    val owner = MutableStateFlow(OWNER)
                    val repository = RoomCollectionRepository(db, owner)
                    val dao = db.cardVaultDao()
                    val expectedIds = (0 until size).filterNot(::deleted).map { it + 1L }.toSet()
                    val expectedQuantity = expectedIds.sumOf { quantity((it - 1L).toInt()) }
                    val expectedMinor = expectedIds.sumOf { valueMinor((it - 1L).toInt()) * quantity((it - 1L).toInt()) }

                    // sample=0 is separately labelled; samples 1..repeats are the warm repetitions.
                    for (sample in 0..config.repeats) {
                        val rows = measured(report, "collection.first", size, sample, name, counter) {
                            dao.observeCollection(OWNER).first()
                        }
                        assertEquals(expectedIds, rows.map { it.entryId }.toSet())
                        assertEquals(expectedIds.size, rows.size)
                        assertEquals(expectedMinor, rows.sumOf { (it.marketValueMinor ?: 0L) * it.quantity })

                        val sets = measured(report, "setBinderSummaries.first", size, sample, name, counter) {
                            repository.binders.first()
                        }
                        assertEquals(size / SET_SIZE, sets.size)
                        assertEquals(expectedQuantity, sets.sumOf { it.ownedCards })
                        assertEquals(expectedMinor / 100.0, sets.sumOf { it.marketValueEur }, .00001)

                        val binders = measured(report, "privateBinderSummaries.first", size, sample, name, counter) {
                            repository.privateBinders.first()
                        }
                        assertEquals(listOf(BINDER), binders.map { it.id })
                        assertEquals(expectedIds.size, binders.single().uniqueCards)
                        assertEquals(expectedQuantity, binders.single().ownedCards)
                        assertEquals(expectedMinor / 100.0, binders.single().marketValueEur, .00001)

                        val portfolio = measured(report, "portfolio.first", size, sample, name, counter) {
                            repository.portfolio.first()
                        }
                        assertEquals(expectedIds, portfolio.cards.map { it.entryId }.toSet())
                        assertEquals(expectedQuantity, portfolio.totalCards)
                        assertEquals(expectedMinor / 100.0, portfolio.totalValue, .00001)
                        assertEquals(2, portfolio.history.size)

                        val cards = measured(report, "privateBinderContents.first", size, sample, name, counter) {
                            repository.privateBinderContents(BINDER).first()
                        }
                        assertEquals(portfolio.cards.associateBy { it.entryId }, cards.associate { it.card.entryId to it.card })
                        assertEquals(expectedIds.size, cards.size)
                        assertEquals(expectedIds.map { it - 1L }.toSet(), cards.map { it.position }.toSet())

                        val pending = measured(report, "pendingCollectionOutbox.limit200", size, sample, name, counter) {
                            dao.pendingSyncOutbox(OWNER, 200)
                        }
                        assertEquals((0 until size).count { state(it) == "PENDING" }.coerceAtMost(200), pending.size)
                        assertTrue(pending.all { it.ownerUid == OWNER && it.state == "PENDING" })
                        val privatePending = measured(report, "pendingPrivateOutbox.limit200", size, sample, name, counter) {
                            dao.pendingPrivateBinderSyncOutbox(OWNER, 200)
                        }
                        assertEquals(200, privatePending.size)
                        assertTrue(privatePending.all { it.ownerUid == OWNER })
                    }
                    // Owner switching is local StateFlow only, never authentication or network access.
                    owner.value = OTHER_OWNER
                    assertEquals(OTHER_ROWS, repository.portfolio.first().cards.size)
                    assertEquals(listOf(BINDER), repository.privateBinders.first().map { it.id })
                    owner.value = "fixture-owner-with-no-data"
                    assertTrue(repository.portfolio.first().cards.isEmpty())
                    assertTrue(repository.privateBinders.first().isEmpty())
                    owner.value = OWNER
                    assertEquals(expectedIds.size, repository.portfolio.first().cards.size)
                    assertEquals("Reads must preserve every existing field", before, hashes(sql))
                    assertNoForeignKeyErrors(sql)
                    report.getJSONArray("integrity").put(JSONObject().put("size", size).put("afterReadHashMatch", true))
                } finally {
                    db.close()
                    context.deleteDatabase(name)
                }
                save(report, config)
            }
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally { save(report, config) }
    }

    @Test fun localRoomAnnualHistoryQueryBreakdown() = runBlocking<Unit> {
        val config = config()
        assumeTrue("Annual history query probe not requested", InstrumentationRegistry.getArguments()
            .getString("release27RoomHistoryProbe") == "annual365")
        require(config.sizes == listOf(1_000) && config.repeats == 3)
        val days = 365
        val report = report(config, "localRoomAnnualHistoryQueryBreakdown")
            .put("fixtureVersion", 2)
            .put("fixture", "Unchanged annual seed: 1003 products, 365 daily dates, 950 live owner-A products")
            .put("historyDays", JSONArray(listOf(days)))
            .put("diagnosticIterations", 2)
            .put("readSampling", "Two diagnostic iterations per operation, not a three-warm-sample median; first DAO after seed/hash is not cold disk")
            .put("queryCounterScope", "Room QueryCallback counts only, not disk IO; raw cursor may use the same callback adapter")
            .put("probeScope", "Captured production SQL and bindings unchanged; DAO emission, raw cursor materialization, groupBy and actual history sweep separated; assertions outside timings")
            .put("deadlineScope", "180s cooperative enclosing guard; synchronous SQLite/hash/seed cannot be forcibly interrupted by coroutine cancellation")
            .put("memoryScope", "Whole-process snapshots while baseline projection is retained; no GC, no peak/retention claim")
            .put("cleanup", JSONArray())
            .put("rawCursorPhases", JSONArray())
        val name = freshName("history-probe-365")
        val file = context.getDatabasePath(name).canonicalFile
        check(file.name == name && file.parentFile == context.getDatabasePath("history-probe-parent").canonicalFile.parentFile)
        val files = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
        check(files.none(File::exists)) { "Probe requires an entirely fresh named test database" }
        val counter = SqlCounter()
        val captured = java.util.concurrent.atomic.AtomicReference<HistoryProbeSql?>()
        val captureFailure = java.util.concurrent.atomic.AtomicReference<String?>()
        val capturedCalls = AtomicLong()
        val whitespace = Regex("\\s+")
        val prefix = "SELECT p.cardId, p.effectiveAtEpochMillis, p.trendValueMinor, p.holoTrendValueMinor FROM raw_price_snapshots p WHERE p.source = ?"
        val db = Room.databaseBuilder(context, CardVaultDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback(RoomDatabase.QueryCallback { statement, bindings ->
                counter.record(statement)
                val normalized = statement.replace(whitespace, " ").trim()
                if (normalized.startsWith(prefix)) {
                    capturedCalls.incrementAndGet()
                    if (bindings != listOf(SOURCE, OWNER)) {
                        captureFailure.compareAndSet(null, "History binding order/source/owner differed")
                    } else {
                        val candidate = HistoryProbeSql(statement, listOf(SOURCE, OWNER))
                        synchronized(captured) {
                            val previous = captured.get()
                            if (previous == null) captured.set(candidate)
                            else if (previous != candidate) captureFailure.compareAndSet(null, "History SQL or bindings changed")
                        }
                    }
                }
            }, Executor { it.run() })
            .build()
        try {
            try {
                withTimeout(180_000L) {
                    val sql = db.openHelper.writableDatabase
                    annualMeasured(report, "seed", days, -1, name, counter) { seedAnnualHistory(sql, days) }
                    assertSeed(sql, 1_000)
                    assertEquals(366_095L, scalar(sql, "SELECT COUNT(*) FROM raw_price_snapshots"))
                    assertEquals(365L, scalar(sql, "SELECT COUNT(DISTINCT effectiveAtEpochMillis) FROM raw_price_snapshots"))
                    val before = hashes(sql)
                    check(before.size == 15)
                    report.getJSONArray("integrity").put(JSONObject()
                        .put("size", 1_000).put("historyDays", days).put("ownedHistoryRows", 346_750)
                        .put("before", hashJson(before)))
                    val dao = db.cardVaultDao()
                    val rows = dao.observeCollection(OWNER).first() // Setup, not a history sample.
                    val live = (0 until 1_000).filterNot(::deleted)
                    assertEquals(live.map { it + 1L }.toSet(), rows.map { it.entryId }.toSet())
                    assertEquals(live.size, rows.size)
                    rows.forEach { row ->
                        val index = (row.entryId - 1L).toInt()
                        assertEquals("fixture-card-$index", row.priceCardId)
                        assertEquals(quantity(index), row.quantity)
                        assertEquals(annualValueMinor(index, days - 1), row.marketValueMinor)
                        assertEquals(AT + days * ANNUAL_DAY_MILLIS, row.priceUpdatedAtEpochMillis)
                        assertEquals(index % 7 == 0, row.finish.contains("reverse", true))
                    }
                    var baseline: List<com.dominik.cardvault.data.local.OwnedPriceHistoryProjection>? = null
                    repeat(2) { sample ->
                        val loaded = annualMeasured(report, "ownedHistory.dao.first", days, sample, name, counter) {
                            dao.observeOwnedRawPriceHistory(OWNER, SOURCE).first()
                        }
                        check(captureFailure.get() == null) { captureFailure.get().orEmpty() }
                        check(captured.get() != null && capturedCalls.get() >= sample + 1L)
                        assertHistoryProbeRows(loaded)
                        if (baseline == null) baseline = loaded else assertEquals(baseline, loaded)
                    }
                    val expected = checkNotNull(baseline)
                    val query = checkNotNull(captured.get())
                    report.put("capturedHistorySql", query.sql)
                        .put("capturedHistoryBindings", JSONArray(query.bindings))
                        .put("sqliteVersion", sql.query("SELECT sqlite_version()").use { cursor ->
                            check(cursor.moveToFirst()); cursor.getString(0)
                        })
                    val explain = JSONArray()
                    sql.query(androidx.sqlite.db.SimpleSQLiteQuery(
                        "EXPLAIN QUERY PLAN " + query.sql, query.bindings.toTypedArray(),
                    )).use { cursor ->
                        check(cursor.columnCount == 4)
                        while (cursor.moveToNext()) {
                            check(explain.length() < 64)
                            explain.put(JSONObject().put("id", cursor.getLong(0)).put("parent", cursor.getLong(1))
                                .put("aux", cursor.getLong(2)).put("detail", cursor.getString(3)))
                        }
                    }
                    check(explain.length() > 0)
                    report.put("explainQueryPlan", explain)
                    repeat(2) { sample ->
                        val raw = annualMeasured(report, "ownedHistory.rawCursor", days, sample, name, counter) {
                            readHistoryProbeCursor(sql, query)
                        }
                        assertEquals(listOf("cardId", "effectiveAtEpochMillis", "trendValueMinor", "holoTrendValueMinor"), raw.columns)
                        assertHistoryProbeRows(raw.rows)
                        assertEquals("Unchanged captured SQL must materialize the exact DAO projection/order", expected, raw.rows)
                        report.getJSONArray("rawCursorPhases").put(JSONObject().put("sample", sample)
                            .put("createCursorMs", raw.createCursorMs).put("firstMoveMs", raw.firstMoveMs)
                            .put("materializeAfterFirstMoveMs", raw.materializeAfterFirstMoveMs)
                            .put("rows", raw.rows.size))
                    }
                    repeat(2) { sample ->
                        val grouped = annualMeasured(report, "ownedHistory.groupBy", days, sample, name, counter) {
                            expected.groupBy(com.dominik.cardvault.data.local.OwnedPriceHistoryProjection::cardId)
                        }
                        assertEquals(live.map { "fixture-card-$it" }.toSet(), grouped.keys)
                        assertEquals(expected.size, grouped.values.sumOf { it.size })
                        grouped.forEach { (cardId, prices) ->
                            assertEquals(days, prices.size)
                            val index = cardId.removePrefix("fixture-card-").toInt()
                            prices.forEachIndexed { point, price ->
                                assertEquals(cardId, price.cardId)
                                assertEquals(AT + (point + 1) * ANNUAL_DAY_MILLIS, price.effectiveAtEpochMillis)
                                assertEquals(100L + index % 9_000 + point, price.trendValueMinor)
                                assertEquals(150L + index % 9_000 + point, price.holoTrendValueMinor)
                            }
                        }
                    }
                    repeat(2) { sample ->
                        val history = annualMeasured(report, "ownedHistory.actualSweep", days, sample, name, counter) {
                            com.dominik.cardvault.data.buildChronologicalPortfolioHistory(rows, expected)
                        }
                        assertEquals(days, history.size)
                        history.forEachIndexed { point, value ->
                            val minor = live.sumOf { annualValueMinor(it, point) * quantity(it) }
                            assertEquals(minor / 100.0, value.value, .00001)
                            val label = java.time.Instant.ofEpochMilli(AT + (point + 1) * ANNUAL_DAY_MILLIS)
                                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                                .format(java.time.format.DateTimeFormatter.ofPattern("MMM"))
                            assertEquals(label, value.label)
                        }
                    }
                    check(captureFailure.get() == null) { captureFailure.get().orEmpty() }
                    assertEquals(query, captured.get())
                    report.put("capturedHistoryCallbackCount", capturedCalls.get())
                    assertEquals("Probe reads must preserve every existing field", before, hashes(sql))
                    assertNoForeignKeyErrors(sql)
                    report.getJSONArray("integrity").put(JSONObject()
                        .put("size", 1_000).put("historyDays", days).put("afterReadHashMatch", true))
                    assertEquals(9, report.getJSONArray("samples").length())
                }
            } finally {
                db.close()
                check(!db.isOpen)
                if (files.any(File::exists)) check(context.deleteDatabase(name))
                check(files.none(File::exists)) { "Probe's exact SQLite fixture remains" }
                report.getJSONArray("cleanup").put(JSONObject()
                    .put("historyDays", days).put("databaseName", name).put("closed", true).put("filesAbsent", true))
            }
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally { save(report, config) }
    }

    private data class HistoryProbeSql(val sql: String, val bindings: List<String>)
    private data class HistoryProbeCursorResult(
        val rows: List<com.dominik.cardvault.data.local.OwnedPriceHistoryProjection>,
        val columns: List<String>, val createCursorMs: Double, val firstMoveMs: Double,
        val materializeAfterFirstMoveMs: Double,
    )

    private fun readHistoryProbeCursor(sql: SupportSQLiteDatabase, query: HistoryProbeSql): HistoryProbeCursorResult {
        val start = System.nanoTime()
        val cursor = sql.query(androidx.sqlite.db.SimpleSQLiteQuery(query.sql, query.bindings.toTypedArray()))
        val created = System.nanoTime()
        cursor.use {
            var present = it.moveToFirst()
            val firstMoved = System.nanoTime()
            val columns = it.columnNames.toList()
            check(columns == listOf("cardId", "effectiveAtEpochMillis", "trendValueMinor", "holoTrendValueMinor"))
            val rows = ArrayList<com.dominik.cardvault.data.local.OwnedPriceHistoryProjection>(346_750)
            while (present) {
                check(!it.isNull(0) && !it.isNull(1))
                rows += com.dominik.cardvault.data.local.OwnedPriceHistoryProjection(
                    it.getString(0), it.getLong(1), if (it.isNull(2)) null else it.getLong(2),
                    if (it.isNull(3)) null else it.getLong(3),
                )
                present = it.moveToNext()
            }
            val completed = System.nanoTime()
            return HistoryProbeCursorResult(rows, columns, (created - start) / 1_000_000.0,
                (firstMoved - created) / 1_000_000.0, (completed - firstMoved) / 1_000_000.0)
        }
    }

    private fun assertHistoryProbeRows(prices: List<com.dominik.cardvault.data.local.OwnedPriceHistoryProjection>) {
        check(prices.size == 346_750)
        val seen = IntArray(1_000)
        var lastTimestamp = Long.MIN_VALUE
        for (price in prices) {
            check(price.cardId.startsWith("fixture-card-"))
            val index = price.cardId.removePrefix("fixture-card-").toInt()
            check(index in 0 until 1_000 && !deleted(index))
            val point = seen[index]++
            check(point in 0 until 365)
            check(price.effectiveAtEpochMillis >= lastTimestamp)
            lastTimestamp = price.effectiveAtEpochMillis
            check(price.effectiveAtEpochMillis == AT + (point + 1) * ANNUAL_DAY_MILLIS)
            check(price.trendValueMinor == 100L + index % 9_000 + point)
            check(price.holoTrendValueMinor == 150L + index % 9_000 + point)
        }
        seen.forEachIndexed { index, count -> check(count == if (deleted(index)) 0 else 365) }
    }

    // DRAFT: optional additive members in the same class. Requires existing HistoryProbe* helpers.
    // Test hypothesis only: no DAO/product/schema modification and no alternate driver/index.
    @Test fun localRoomAnnualHistoryOrderingCounterfactual() = runBlocking<Unit> {
        val config = config()
        assumeTrue("Ordering counterfactual not requested", InstrumentationRegistry.getArguments()
            .getString("release27RoomHistoryCounterfactual") == "product-order")
        require(config.sizes == listOf(1_000) && config.repeats == 3)
        val days = 365
        val report = report(config, "localRoomAnnualHistoryOrderingCounterfactual")
            .put("fixtureVersion", 2).put("historyDays", JSONArray(listOf(days)))
            .put("fixture", "Unchanged annual365 seed; 1003 products, 950 live owner-A products")
            .put("diagnosticIterations", 2)
            .put("readSampling", "Two original/candidate paired diagnostic iterations; NOT three warm medians; DAO capture and parity validation outside measured pairs")
            .put("counterfactualScope", "Only captured terminal ORDER BY replaced; existing indexes/driver unchanged; candidate materialization plus stable timestamp sort measured together")
            .put("tieScope", "Source fixed; UNIQUE(cardId,source,time) prohibits same-product/time ties; cross-product/time ties need not have identical original SQL order")
            .put("memoryScope", "Whole-process snapshots with DAO baseline/canonical oracle retained; no peak/retention claim")
            .put("deadlineScope", "180s cooperative enclosing guard, not synchronous SQL interrupt or product SLA")
            .put("cleanup", JSONArray()).put("pairProofs", JSONArray()).put("queryPlans", JSONArray())
        val name = freshName("history-order-365")
        val file = context.getDatabasePath(name).canonicalFile
        check(file.name == name && file.parentFile == context.getDatabasePath("history-order-parent").canonicalFile.parentFile)
        val files = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
        check(files.none(File::exists))
        val counter = SqlCounter()
        val captureEnabled = java.util.concurrent.atomic.AtomicBoolean(true)
        val captured = java.util.concurrent.atomic.AtomicReference<HistoryProbeSql?>()
        val captureFailure = java.util.concurrent.atomic.AtomicReference<String?>()
        val whitespace = Regex("\\s+")
        val prefix = "SELECT p.cardId, p.effectiveAtEpochMillis, p.trendValueMinor, p.holoTrendValueMinor FROM raw_price_snapshots p WHERE p.source = ?"
        val db = Room.databaseBuilder(context, CardVaultDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback(RoomDatabase.QueryCallback { statement, bindings ->
                counter.record(statement)
                if (captureEnabled.get() && statement.replace(whitespace, " ").trim().startsWith(prefix)) {
                    if (bindings != listOf(SOURCE, OWNER)) {
                        captureFailure.compareAndSet(null, "Unexpected original history bindings")
                    } else {
                        val candidate = HistoryProbeSql(statement, listOf(SOURCE, OWNER))
                        synchronized(captured) {
                            val previous = captured.get()
                            if (previous == null) captured.set(candidate)
                            else if (previous != candidate) captureFailure.compareAndSet(null, "Original capture changed")
                        }
                    }
                }
            }, Executor { it.run() }).build()
        try {
            try {
                withTimeout(180_000L) {
                    val sql = db.openHelper.writableDatabase
                    annualMeasured(report, "seed", days, -1, name, counter) { seedAnnualHistory(sql, days) }
                    assertSeed(sql, 1_000)
                    assertEquals(366_095L, scalar(sql, "SELECT COUNT(*) FROM raw_price_snapshots"))
                    val before = hashes(sql)
                    check(before.size == 15)
                    report.getJSONArray("integrity").put(JSONObject().put("size", 1_000)
                        .put("historyDays", days).put("ownedHistoryRows", 346_750).put("before", hashJson(before)))

                    // Prove actual schema uniqueness, not merely fixture coincidence.
                    val indexName = "index_raw_price_snapshots_cardId_source_effectiveAtEpochMillis"
                    var foundIndex = false
                    sql.query("PRAGMA index_list(`raw_price_snapshots`)").use { c -> while (c.moveToNext()) {
                        if (c.getString(c.getColumnIndexOrThrow("name")) == indexName) {
                            check(!foundIndex)
                            check(c.getInt(c.getColumnIndexOrThrow("unique")) == 1)
                            check(c.getInt(c.getColumnIndexOrThrow("partial")) == 0)
                            foundIndex = true
                        }
                    } }
                    check(foundIndex)
                    val indexedColumns = sql.query("PRAGMA index_info(`$indexName`)").use { c ->
                        buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
                    }
                    assertEquals(listOf("cardId", "source", "effectiveAtEpochMillis"), indexedColumns)
                    report.put("verifiedUniqueIndex", indexName).put("verifiedUniqueColumns", JSONArray(indexedColumns))

                    val dao = db.cardVaultDao()
                    val rows = dao.observeCollection(OWNER).first()
                    val live = (0 until 1_000).filterNot(::deleted)
                    assertEquals(live.map { it + 1L }.toSet(), rows.map { it.entryId }.toSet())
                    assertEquals(live.size, rows.size)
                    rows.forEach { row ->
                        val index = (row.entryId - 1L).toInt()
                        assertEquals("fixture-card-$index", row.priceCardId)
                        assertEquals(quantity(index), row.quantity)
                        assertEquals(index % 7 == 0, row.finish.contains("reverse", true))
                    }
                    val baseline = dao.observeOwnedRawPriceHistory(OWNER, SOURCE).first()
                    check(captureFailure.get() == null) { captureFailure.get().orEmpty() }
                    val original = checkNotNull(captured.get())
                    captureEnabled.set(false) // No further DAO calls; only explicit original/candidate raw queries.
                    assertHistoryProbeRows(baseline)
                    val suffix = "ORDER BY p.effectiveAtEpochMillis"
                    val offset = original.sql.indexOf(suffix)
                    check(offset >= 0 && offset == original.sql.lastIndexOf(suffix))
                    check(original.sql.substring(offset + suffix.length).all(Char::isWhitespace))
                    val replacement = "ORDER BY p.cardId, p.effectiveAtEpochMillis"
                    val candidate = HistoryProbeSql(original.sql.replaceRange(offset, offset + suffix.length, replacement), original.bindings)
                    check(candidate.sql.substring(0, offset) == original.sql.substring(0, offset))
                    check(candidate.sql.substring(offset + replacement.length) == original.sql.substring(offset + suffix.length))
                    check(candidate.bindings == original.bindings && candidate.sql != original.sql)
                    report.put("capturedHistorySql", original.sql).put("candidateHistorySql", candidate.sql)
                        .put("capturedHistoryBindings", JSONArray(original.bindings))
                        .put("sqliteVersion", sql.query("SELECT sqlite_version()").use { c -> check(c.moveToFirst()); c.getString(0) })
                    for ((label, query) in listOf("original" to original, "candidate" to candidate)) {
                        val plan = JSONArray()
                        sql.query(androidx.sqlite.db.SimpleSQLiteQuery("EXPLAIN QUERY PLAN " + query.sql,
                            query.bindings.toTypedArray())).use { c ->
                            check(c.columnCount == 4)
                            while (c.moveToNext()) {
                                check(plan.length() < 64)
                                plan.put(JSONObject().put("id", c.getLong(0)).put("parent", c.getLong(1))
                                    .put("aux", c.getLong(2)).put("detail", c.getString(3)))
                            }
                        }
                        check(plan.length() > 0)
                        report.getJSONArray("queryPlans").put(JSONObject().put("variant", label).put("rows", plan))
                    }
                    val canonicalComparator = compareBy<com.dominik.cardvault.data.local.OwnedPriceHistoryProjection>(
                        { it.effectiveAtEpochMillis }, { it.cardId },
                    )
                    val canonicalBaseline = baseline.sortedWith(canonicalComparator)
                    val originalHistory = com.dominik.cardvault.data.buildChronologicalPortfolioHistory(rows, baseline)
                    assertEquals(days, originalHistory.size)
                    originalHistory.forEachIndexed { point, value ->
                        assertEquals(live.sumOf { annualValueMinor(it, point) * quantity(it) } / 100.0, value.value, .00001)
                        val label = java.time.Instant.ofEpochMilli(AT + (point + 1) * ANNUAL_DAY_MILLIS)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                            .format(java.time.format.DateTimeFormatter.ofPattern("MMM"))
                        assertEquals(label, value.label)
                    }
                    repeat(2) { sample ->
                        // Same materializer for both SQL forms; only candidate needs in-memory sorting.
                        val originalRead = annualMeasured(report, "historyOrder.original.rawCursor", days, sample, name, counter) {
                            readHistoryProbeCursor(sql, original)
                        }
                        assertHistoryProbeRows(originalRead.rows)
                        assertEquals(baseline, originalRead.rows)
                        val candidateRead = annualMeasured(report, "historyOrder.candidate.rawCursorAndStableSort", days, sample, name, counter) {
                            val raw = readHistoryProbeCursor(sql, candidate)
                            val sortStarted = System.nanoTime()
                            val chronological = raw.rows.sortedBy { it.effectiveAtEpochMillis }
                            HistoryOrderCandidate(raw, chronological, (System.nanoTime() - sortStarted) / 1_000_000.0)
                        }
                        assertEquals(originalRead.columns, candidateRead.raw.columns)
                        // Require the exact requested product/time order before the stable Kotlin sort.
                        for (index in 1 until candidateRead.raw.rows.size) {
                            val left = candidateRead.raw.rows[index - 1]
                            val right = candidateRead.raw.rows[index]
                            check(left.cardId < right.cardId || (left.cardId == right.cardId &&
                                left.effectiveAtEpochMillis < right.effectiveAtEpochMillis))
                        }
                        assertHistoryProbeRows(candidateRead.chronological)
                        assertEquals("All four fields and every unique product/date must agree",
                            canonicalBaseline, candidateRead.chronological.sortedWith(canonicalComparator))
                        assertEquals(originalHistory, com.dominik.cardvault.data.buildChronologicalPortfolioHistory(rows, candidateRead.chronological))
                        report.getJSONArray("pairProofs").put(JSONObject().put("sample", sample)
                            .put("rows", candidateRead.chronological.size).put("fullNormalizedProjectionEqual", true)
                            .put("fullOriginalSequenceEqual", baseline == candidateRead.chronological)
                            .put("actualSweepEqual", true).put("candidateStableSortMs", candidateRead.stableSortMs)
                            .put("originalFirstMoveMs", originalRead.firstMoveMs)
                            .put("originalMaterializeAfterFirstMoveMs", originalRead.materializeAfterFirstMoveMs)
                            .put("candidateFirstMoveMs", candidateRead.raw.firstMoveMs)
                            .put("candidateMaterializeAfterFirstMoveMs", candidateRead.raw.materializeAfterFirstMoveMs))
                    }
                    assertEquals(before, hashes(sql))
                    assertNoForeignKeyErrors(sql)
                    report.getJSONArray("integrity").put(JSONObject().put("size", 1_000)
                        .put("historyDays", days).put("afterReadHashMatch", true))
                    assertEquals(5, report.getJSONArray("samples").length())
                }
            } finally {
                db.close()
                check(!db.isOpen)
                if (files.any(File::exists)) check(context.deleteDatabase(name))
                check(files.none(File::exists))
                report.getJSONArray("cleanup").put(JSONObject().put("historyDays", days)
                    .put("databaseName", name).put("closed", true).put("filesAbsent", true))
            }
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally { save(report, config) }
    }

    private data class HistoryOrderCandidate(
        val raw: HistoryProbeCursorResult,
        val chronological: List<com.dominik.cardvault.data.local.OwnedPriceHistoryProjection>,
        val stableSortMs: Double,
    )

    @Test fun localRoomPostFixHistoryPhasesAndFirstPositiveCounterfactual() = runBlocking<Unit> {
        val config = config()
        assumeTrue("Post-fix history diagnostic not requested", InstrumentationRegistry.getArguments()
            .getString("release27RoomHistoryPostFix") == "phase-first-positive")
        require(config.sizes == listOf(1_000) && config.repeats == 3)
        val report = report(config, "localRoomPostFixHistoryPhases")
            .put("fixtureVersion", 2).put("historyDays", JSONArray(listOf(365)))
            .put("fixture", "Unmodified annual365 seed: 1003 products; 950 active owner-A products; 346750 eligible observations")
            .put("phaseScope", "Separate sequential timings are not additive wall-clock attribution: real product-major DAO; same stable Default sort; groupBy; actual production sweep; raw counterfactual bases")
            .put("concurrencyScope", "Two genuine independent .first subscriptions start behind one barrier; not UI, sustained invalidation, hardware or latency SLA")
            .put("candidateScope", "Test-only raw SQL, no product DAO/repository integration or invalidation proof")
            .put("memoryScope", "Whole-process snapshots; full baseline retained only inside completed sequential helper; concurrent subscriptions retain compact expected portfolio only; no peak, no cold-disk, no forced GC or heap increase")
            .put("readSampling", "Sample 0 is the first measured repetition after unmeasured baseline reads, NOT first DAO subscription or cold disk; 1..3 are subsequent warm repetitions")
            .put("measurementOrder", "Seed, all four sequential five-phase repetitions, then four concurrent subscription repetitions; no full-history fixture lists retained across these scopes")
            .put("cleanup", JSONArray()).put("concurrentReadings", JSONArray())
            .put("transferCounts", JSONArray())
        val name = freshName("postfix-history-365")
        val files = checkedPostFixFiles(name)
        val counter = SqlCounter()
        val captured = java.util.concurrent.atomic.AtomicReference<HistoryProbeSql?>()
        val captureError = java.util.concurrent.atomic.AtomicReference<String?>()
        val db = Room.databaseBuilder(context, CardVaultDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback(RoomDatabase.QueryCallback { statement, bindings ->
                counter.record(statement)
                val normalized = statement.replace(Regex("\\s+"), " ").trim()
                if (normalized.startsWith("SELECT p.cardId, p.effectiveAtEpochMillis, p.trendValueMinor, p.holoTrendValueMinor FROM raw_price_snapshots p WHERE p.source = ?") &&
                    bindings == listOf(SOURCE, OWNER)) {
                    val next = HistoryProbeSql(normalized, listOf(SOURCE, OWNER))
                    synchronized(captured) {
                        val old = captured.get()
                        if (old == null) captured.set(next)
                        else if (old != next) captureError.compareAndSet(null, "Actual history SQL changed during proof")
                    }
                }
            }, Executor { it.run() }).build()
        try {
            try {
                val sql = db.openHelper.writableDatabase
                annualMeasured(report, "seed", 365, -1, name, counter) { seedAnnualHistory(sql, 365) }
                assertSeed(sql, 1_000)
                assertEquals(366_095L, scalar(sql, "SELECT COUNT(*) FROM raw_price_snapshots"))
                val before = hashes(sql)
                assertEquals(15, before.size)
                report.getJSONArray("integrity").put(JSONObject().put("historyDays", 365).put("before", hashJson(before)))
                val dao = db.cardVaultDao()
                val repository = RoomCollectionRepository(db, MutableStateFlow(OWNER))
                // Full-history lists stay in this completed helper, never in the
                // parent coroutine that later collects both independent live flows.
                suspend fun measuredPhases(): com.dominik.cardvault.model.PortfolioSummary {
                    val rows = dao.observeCollection(OWNER).first()
                    val baseline = dao.observeOwnedRawPriceHistory(OWNER, SOURCE).first()
                    assertHistoryProbeRows(baseline)
                    val capturedQuery = checkNotNull(captured.get()) { "No real production history query captured" }
                    check(captureError.get() == null) { captureError.get().orEmpty() }
                    val candidate = postFixFirstPositiveQuery(capturedQuery, OWNER)
                    report.put("capturedHistorySql", capturedQuery.sql)
                        .put("capturedHistoryBindings", JSONArray(capturedQuery.bindings))
                        .put("candidateSql", candidate.sql).put("candidateBindings", JSONArray(candidate.bindings))
                        .put("historyPlan", postFixExplain(sql, capturedQuery))
                        .put("candidatePlan", postFixExplain(sql, candidate))
                    val expectedBases = postFixBasesOracle(rows.map { it.priceCardId }, baseline)
                    assertEquals(950, expectedBases.size)
                    for ((id, bases) in expectedBases) {
                        val index = id.removePrefix("fixture-card-").toInt()
                        assertEquals(PostFixBases(100L + index % 9_000, 150L + index % 9_000), bases)
                    }
                    val baselinePortfolio = repository.portfolio.first()
                    postFixAssertAnnualPortfolio(baselinePortfolio)
                    for (sample in 0..config.repeats) {
                        val byProduct = annualMeasured(report, "postFix.realDao.productOrder", 365, sample, name, counter) {
                            dao.observeOwnedRawPriceHistoryByProduct(OWNER, SOURCE).first()
                        }
                        assertEquals(346_750, byProduct.size)
                        assertTrue(byProduct.zipWithNext().all { (a, b) ->
                            a.cardId < b.cardId || (a.cardId == b.cardId && a.effectiveAtEpochMillis < b.effectiveAtEpochMillis)
                        })
                        val chronological = annualMeasured(report, "postFix.sameStableSort.Default", 365, sample, name, counter) {
                            withContext(Dispatchers.Default) { byProduct.sortedBy { it.effectiveAtEpochMillis } }
                        }
                        assertEquals(baseline, chronological)
                        assertHistoryProbeRows(chronological)
                        val grouped = annualMeasured(report, "postFix.groupBy.Default", 365, sample, name, counter) {
                            withContext(Dispatchers.Default) { chronological.groupBy { it.cardId } }
                        }
                        assertEquals(expectedBases.keys, grouped.keys)
                        assertEquals(346_750, grouped.values.sumOf { it.size })
                        grouped.values.forEach { assertEquals(365, it.size) }
                        val history = annualMeasured(report, "postFix.productionSweep.Default", 365, sample, name, counter) {
                            withContext(Dispatchers.Default) {
                                com.dominik.cardvault.data.buildChronologicalPortfolioHistory(rows, chronological)
                            }
                        }
                        assertEquals(baselinePortfolio.history, history)
                        val candidateBases = annualMeasured(report, "postFix.counterfactual.firstPositiveCursor", 365, sample, name, counter) {
                            readPostFixBases(sql, candidate)
                        }
                        assertEquals(expectedBases, candidateBases)
                        // This is exact full-record reconstruction while retaining unchanged fields,
                        // not a test of a hypothetical new production repository or Room mapper.
                        postFixAssertReconstructedCards(rows, baselinePortfolio.cards, candidateBases)
                        report.getJSONArray("transferCounts").put(JSONObject().put("sample", sample)
                            .put("productionHistoryRows", byProduct.size).put("candidateBaseRows", candidateBases.size))
                    }
                    return baselinePortfolio
                }
                val baselinePortfolio = measuredPhases()
                // No full baseline, byProduct, chronological or grouped object is
                // returned or captured here. Garbage collection remains unforced.
                for (sample in 0..config.repeats) {

                    val pair = annualMeasured(report, "postFix.concurrentPortfolioAndPrivate.first", 365, sample, name, counter) {
                        coroutineScope {
                            val release = CompletableDeferred<Unit>()
                            val portfolioReady = CompletableDeferred<Unit>()
                            val privateReady = CompletableDeferred<Unit>()
                            val portfolio = async(Dispatchers.Default) {
                                portfolioReady.complete(Unit); release.await()
                                val start = System.nanoTime()
                                val value = repository.portfolio.first()
                                PostFixTimed(value, (System.nanoTime() - start) / 1_000_000.0)
                            }
                            val privateCards = async(Dispatchers.Default) {
                                privateReady.complete(Unit); release.await()
                                val start = System.nanoTime()
                                val value = repository.privateBinderContents(BINDER).first()
                                PostFixTimed(value, (System.nanoTime() - start) / 1_000_000.0)
                            }
                            portfolioReady.await(); privateReady.await(); release.complete(Unit)
                            portfolio.await() to privateCards.await()
                        }
                    }
                    assertEquals(baselinePortfolio, pair.first.value)
                    assertEquals(950, pair.second.value.size)
                    assertEquals(baselinePortfolio.cards.associateBy { it.entryId },
                        pair.second.value.associate { it.card.entryId to it.card })
                    pair.second.value.forEach { placed ->
                        val index = (placed.card.entryId - 1).toInt()
                        assertEquals("placement-$index", placed.placementId)
                        assertEquals(BINDER, placed.binderId)
                        assertEquals(index.toLong(), placed.position)
                        assertEquals(if (index % 2 == 0) "rainbow" else null, placed.holoEffectId)
                        assertEquals(if (index % 2 == 0) .35f else 0f, placed.holoIntensity, 0f)
                    }
                    report.getJSONArray("concurrentReadings").put(JSONObject().put("sample", sample)
                        .put("portfolioMs", pair.first.elapsedMs).put("privateContentsMs", pair.second.elapsedMs))
                }
                check(captureError.get() == null) { captureError.get().orEmpty() }
                assertEquals(before, hashes(sql))
                assertNoForeignKeyErrors(sql)
                assertEquals(25, report.getJSONArray("samples").length())
                assertEquals(4, report.getJSONArray("concurrentReadings").length())
                assertEquals(4, report.getJSONArray("transferCounts").length())
                report.getJSONArray("integrity").put(JSONObject().put("afterReadHashMatch", true))
            } finally {
                db.close(); check(!db.isOpen)
                if (files.any(File::exists)) check(context.deleteDatabase(name))
                check(files.none(File::exists))
                report.getJSONArray("cleanup").put(JSONObject().put("databaseName", name)
                    .put("closed", true).put("filesAbsent", true))
            }
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally { save(report, config) }
    }

    @Test fun localRoomGuardedPortfolioAggregate() = runBlocking<Unit> {
        val config = config()
        assumeTrue("Guarded portfolio proof not requested", InstrumentationRegistry.getArguments()
            .getString("release27RoomPortfolioAggregate") == "guarded-delta")
        require(config.sizes == listOf(1_000) && config.repeats == 3)
        val report = report(config, "localRoomGuardedPortfolioAggregate")
            .put("fixtureVersion", 2).put("historyDays", JSONArray(listOf(365)))
            .put("fixture", "Unmodified annual365: 1003 products, 950 active owner-A products, 346750 eligible observations")
            .put("measurementOrder", "Seed/hash; four actual public-portfolio subscriptions; then four paired legacy DAO+sweep and aggregate DAO+adapter diagnostics")
            .put("readSampling", "Public sample0 is first subscription after seed/hash, not cold disk/process. Samples1..3 are warm; later diagnostic samples are already warm")
            .put("comparisonScope", "Same annual fixture as existing annual2-vs365 test; diagnostic order is explicitly different. Existing unchanged annual method remains the exact baseline/postfix benchmark identity")
            .put("memoryScope", "Whole-process snapshots, no peak/forced GC. Legacy raw lists are scoped to a completed helper before aggregate reads")
            .put("queryCounterScope", "Room total callbacks include WITH queries; existing SELECT-only subcounter excludes WITH. Dedicated real legacy/aggregate/base counters are also recorded")
            .put("remaining", "No API26 execution or multi-CursorWindow concurrent guard stress proved here")
            .put("cleanup", JSONArray()).put("transport", JSONArray())
        val name = freshName("guarded-annual-365")
        val files = checkedPostFixFiles(name)
        val counter = SqlCounter()
        val legacyCalls = AtomicLong()
        val aggregateCalls = AtomicLong()
        val baseCalls = AtomicLong()
        val legacySql = java.util.concurrent.atomic.AtomicReference<HistoryProbeSql?>()
        val aggregateSql = java.util.concurrent.atomic.AtomicReference<HistoryProbeSql?>()
        val captureError = java.util.concurrent.atomic.AtomicReference<String?>()
        val db = Room.databaseBuilder(context, CardVaultDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback(RoomDatabase.QueryCallback { statement, bindings ->
                counter.record(statement)
                val normalized = statement.replace(Regex("\\s+"), " ").trim()
                fun capture(target: java.util.concurrent.atomic.AtomicReference<HistoryProbeSql?>,
                    expected: List<String>) {
                    if (bindings != expected) {
                        captureError.compareAndSet(null, "Unexpected generated query bindings")
                    } else synchronized(target) {
                        // Preserve actual statement text and actual typed binding values.
                        val next = HistoryProbeSql(statement, bindings.map { it as String })
                        val old = target.get()
                        if (old == null) target.set(next)
                        else if (old != next) captureError.compareAndSet(null, "Generated SQL changed during proof")
                    }
                }
                if (normalized.startsWith("SELECT p.cardId, p.effectiveAtEpochMillis, p.trendValueMinor, p.holoTrendValueMinor FROM raw_price_snapshots p WHERE p.source = ?")) {
                    legacyCalls.incrementAndGet()
                    if (bindings.lastOrNull() == OWNER) capture(legacySql, listOf(SOURCE, OWNER))
                }
                if (normalized.startsWith("WITH owner_rows AS (")) {
                    aggregateCalls.incrementAndGet()
                    if (bindings.firstOrNull() == OWNER) capture(aggregateSql, listOf(OWNER, SOURCE, SOURCE, SOURCE))
                }
                if (normalized.startsWith("WITH owned(cardId) AS (") &&
                    normalized.contains("AS firstNormalTrendValueMinor")) baseCalls.incrementAndGet()
            }, Executor { it.run() }).build()
        try {
            try {
                val sql = db.openHelper.writableDatabase
                annualMeasured(report, "seed", 365, -1, name, counter) { seedAnnualHistory(sql, 365) }
                assertSeed(sql, 1_000)
                assertEquals(366_095L, scalar(sql, "SELECT COUNT(*) FROM raw_price_snapshots"))
                assertEquals(365L, scalar(sql, "SELECT COUNT(DISTINCT effectiveAtEpochMillis) FROM raw_price_snapshots"))
                assertEquals(AT + ANNUAL_DAY_MILLIS, scalar(sql, "SELECT MIN(effectiveAtEpochMillis) FROM raw_price_snapshots"))
                assertEquals(AT + 365 * ANNUAL_DAY_MILLIS, scalar(sql, "SELECT MAX(effectiveAtEpochMillis) FROM raw_price_snapshots"))
                val before = hashes(sql); assertEquals(15, before.size)
                report.getJSONArray("integrity").put(JSONObject().put("before", hashJson(before)))
                sql.query("SELECT sqlite_version()").use { c ->
                    assertTrue(c.moveToFirst()); report.put("sqliteVersion", c.getString(0))
                }
                val dao = db.cardVaultDao()
                var syncRequests = 0
                val repository = RoomCollectionRepository(db, MutableStateFlow(OWNER), onSyncNeeded = { syncRequests++ })
                val expected = guardedAnnualExpected(OWNER)
                // No unmeasured DAO/repository read precedes these real public subscriptions.
                for (sample in 0..config.repeats) {
                    val legacyBefore = legacyCalls.get()
                    val aggregateBefore = aggregateCalls.get()
                    val basesBefore = baseCalls.get()
                    val actual = annualMeasured(report, "guarded.actualPortfolio.first", 365, sample, name, counter) {
                        repository.portfolio.first()
                    }
                    val oldReads = legacyCalls.get() - legacyBefore
                    val newReads = aggregateCalls.get() - aggregateBefore
                    val baseReads = baseCalls.get() - basesBefore
                    assertEquals("Every field, uniquely ordered fixture card and original chart point", expected, actual)
                    postFixAssertAnnualPortfolio(actual)
                    assertEquals(0L, oldReads)
                    assertTrue("Actual public path must execute the real aggregate query", newReads > 0L)
                    assertTrue("Actual public card changes use genuine first-positive reads", baseReads > 0L)
                    report.getJSONArray("transport").put(JSONObject().put("sample", sample)
                        .put("operation", "actualPortfolio").put("legacyQueryCalls", oldReads)
                        .put("aggregateQueryCalls", newReads).put("baseQueryCalls", baseReads)
                        .put("completeModelsEqual", true))
                }
                val rows = dao.observeCollection(OWNER).first()
                // Return only the small chart and transfer count. The full raw list is not
                // captured by the parent coroutine or retained during aggregate/public reads.
                suspend fun legacyChart(): Pair<List<com.dominik.cardvault.model.PortfolioPoint>, Int> {
                    val history = dao.observeOwnedRawPriceHistory(OWNER, SOURCE).first()
                    val chart = withContext(Dispatchers.Default) {
                        com.dominik.cardvault.data.buildChronologicalPortfolioHistory(rows, history)
                    }
                    return chart to history.size
                }
                suspend fun aggregateChart(): Pair<List<com.dominik.cardvault.model.PortfolioPoint>, List<com.dominik.cardvault.data.local.OwnedPortfolioDeltaProjection>> {
                    val deltas = dao.observeOwnedPortfolioDeltas(OWNER, SOURCE).first()
                    val chart = withContext(Dispatchers.Default) {
                        checkNotNull(com.dominik.cardvault.data.buildGuardedPortfolioHistory(deltas))
                    }
                    return chart to deltas
                }
                for (sample in 0..config.repeats) {
                    val legacyBefore = legacyCalls.get()
                    val old = annualMeasured(report, "guarded.legacyDaoAndSweep", 365, sample, name, counter) { legacyChart() }
                    assertEquals(346_750, old.second)
                    assertEquals(expected.history, old.first)
                    assertTrue("Positive control sees the real legacy transport", legacyCalls.get() > legacyBefore)
                    val compact = annualMeasured(report, "guarded.aggregateDaoAndMap", 365, sample, name, counter) { aggregateChart() }
                    assertEquals(366, compact.second.size)
                    assertEquals(com.dominik.cardvault.data.local.OwnedPortfolioDeltaProjection(0, 1, null, 0L), compact.second.first())
                    compact.second.drop(1).forEachIndexed { index, row ->
                        assertEquals(1, row.rowKind); assertEquals(1, row.isSafe)
                        assertEquals(AT + (index + 1) * ANNUAL_DAY_MILLIS, row.effectiveAtEpochMillis)
                    }
                    assertEquals(expected.history, compact.first)
                    assertEquals(old.first, compact.first)
                    report.getJSONArray("transport").put(JSONObject().put("sample", sample)
                        .put("operation", "chartDiagnostics").put("legacyRows", old.second)
                        .put("aggregateRowsIncludingGuard", compact.second.size).put("exactChartEqual", true))
                }
                assertEquals(950, dao.observeOwnedFirstPositivePrices(OWNER, SOURCE).first().size)
                val privateCards = repository.privateBinderContents(BINDER).first()
                assertEquals(expected.cards.associateBy { it.entryId }, privateCards.associate { it.card.entryId to it.card })
                assertEquals(950, privateCards.size)
                privateCards.forEach { placed ->
                    val index = (placed.card.entryId - 1L).toInt()
                    assertEquals("placement-$index", placed.placementId)
                    assertEquals(BINDER, placed.binderId)
                    assertEquals(index.toLong(), placed.position)
                    assertEquals(if (index % 2 == 0) "rainbow" else null, placed.holoEffectId)
                    assertEquals(if (index % 2 == 0) .35f else 0f, placed.holoIntensity, 0f)
                }
                for (owner in listOf(OTHER_OWNER, "fixture-owner-with-no-data")) {
                    val isolated = RoomCollectionRepository(db, MutableStateFlow(owner), onSyncNeeded = { syncRequests++ })
                    assertEquals(guardedAnnualExpected(owner), isolated.portfolio.first())
                }
                check(captureError.get() == null) { captureError.get().orEmpty() }
                val oldQuery = checkNotNull(legacySql.get()) { "Missing actual legacy query" }
                val newQuery = checkNotNull(aggregateSql.get()) { "Missing actual aggregate query" }
                assertEquals(listOf(SOURCE, OWNER), oldQuery.bindings)
                assertEquals(listOf(OWNER, SOURCE, SOURCE, SOURCE), newQuery.bindings)
                assertEquals(4, newQuery.sql.count { it == '?' })
                report.put("actualLegacySql", oldQuery.sql).put("actualLegacyBindings", JSONArray(oldQuery.bindings))
                    .put("actualAggregateSql", newQuery.sql).put("actualAggregateBindings", JSONArray(newQuery.bindings))
                    .put("legacyPlan", postFixExplain(sql, oldQuery)).put("aggregatePlan", postFixExplain(sql, newQuery))
                    .put("ownedHistoryRows", 346_750).put("aggregateRowsIncludingGuard", 366).put("firstPositiveRows", 950)
                assertEquals(0, syncRequests)
                assertEquals(before, hashes(sql)); assertNoForeignKeyErrors(sql)
                assertEquals(13, report.getJSONArray("samples").length())
                assertEquals(8, report.getJSONArray("transport").length())
                report.getJSONArray("integrity").put(JSONObject().put("afterReadHashMatch", true))
            } finally {
                db.close(); check(!db.isOpen)
                if (files.any(File::exists)) check(context.deleteDatabase(name))
                check(files.none(File::exists))
                report.getJSONArray("cleanup").put(JSONObject().put("databaseName", name)
                    .put("closed", true).put("filesAbsent", true))
            }
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally { save(report, config) }
    }

    /** Independent full constructor from the unchanged synthetic annual fixture, not DAO models. */
    private fun guardedAnnualExpected(owner: String): com.dominik.cardvault.model.PortfolioSummary {
        val indices = when (owner) {
            OWNER -> (0 until 1_000).filterNot(::deleted)
            OTHER_OWNER -> (1_000 until 1_000 + OTHER_ROWS).toList()
            else -> emptyList()
        }
        val cards = indices.asReversed().map { index ->
            val set = index / SET_SIZE
            val reverse = index % 7 == 0
            val first = annualValueMinor(index, 0)
            val last = annualValueMinor(index, 364)
            com.dominik.cardvault.model.CollectionCard(
                entryId = index + 1L, id = "fixture-card-$index", name = "Synthetic card $index",
                set = "Synthetic set $set", number = "" + (index % SET_SIZE + 1) + "/" + SET_SIZE,
                language = com.dominik.cardvault.model.CardLanguage.GERMAN,
                finish = if (reverse) "Reverse Holo" else "Normal",
                variant = if (reverse) "Reverse Holo" else "Standard", condition = "Near Mint",
                quantity = quantity(index), currentValueEur = last / 100.0,
                changePercent = (last - first) * 100.0 / first,
                priceUpdatedAtEpochMillis = AT + 365 * ANNUAL_DAY_MILLIS,
                notes = "Synthetic $index; \u00e4|\u03bb", acquisitionPriceEur = (50L + index) / 100.0,
                acquisitionDateEpochDay = 20_000L + index, accent = 0xFF6C78A8,
                binderId = "set-$set", externalProductId = 90_000_000L + index,
                imageUrl = null, rarity = "UNKNOWN", effectOverride = null,
                marketSearchName = null, marketExpansionId = null,
                sourceSetId = "fixture-$set", sourceCardId = "fixture-$set-" + (index % SET_SIZE + 1),
                priceSelection = null,
            )
        }
        val history = if (indices.isEmpty()) emptyList() else (0 until 365).map { point ->
            val value = indices.sumOf { annualValueMinor(it, point) * quantity(it) }
            val label = java.time.Instant.ofEpochMilli(AT + (point + 1) * ANNUAL_DAY_MILLIS)
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("MMM"))
            com.dominik.cardvault.model.PortfolioPoint(label, value / 100.0)
        }
        return com.dominik.cardvault.model.PortfolioSummary(cards, history)
    }

    @Test fun localRoomGuardedPortfolioCountBoundary() = runBlocking<Unit> {
        val config = config()
        assumeTrue("Guarded count proof not requested", InstrumentationRegistry.getArguments()
            .getString("release27RoomPortfolioAggregate") == "guarded-delta-count")
        require(config.sizes == listOf(20_000) && config.repeats == 3)
        val report = report(config, "localRoomGuardedPortfolioCountBoundary")
            .put("scope", "Fresh existing20000 synthetic fixture; restore only its owner-A tombstones, then add/tombstone one zero-quantity entry. No annual timing claim")
            .put("cleanup", JSONArray()).put("states", JSONArray())
        val name = freshName("guarded-count-boundary")
        val files = checkedPostFixFiles(name)
        val db = Room.databaseBuilder(context, CardVaultDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            try {
                val sql = db.openHelper.writableDatabase
                seed(sql, 20_000); assertSeed(sql, 20_000)
                val dao = db.cardVaultDao()
                var syncRequests = 0
                val repository = RoomCollectionRepository(db, MutableStateFlow(OWNER), onSyncNeeded = { syncRequests++ })
                // Keep all original records; the existing1000 owner-A tombstones are restored
                // only in this fresh UUID fixture. Foreign owner's3 rows never consume the bound.
                sql.execSQL("UPDATE collection_entries SET deletedAtEpochMillis=NULL WHERE ownerUid=?",
                    arrayOf<Any>(OWNER))
                suspend fun state(label: String, count: Int, safe: Boolean) {
                    val before = hashes(sql); assertEquals(15, before.size)
                    val rows = dao.observeCollection(OWNER).first()
                    assertEquals(count, rows.size)
                    val old = dao.observeOwnedRawPriceHistory(OWNER, SOURCE).first()
                    assertEquals(40_000, old.size)
                    val expectedHistory = com.dominik.cardvault.data.buildChronologicalPortfolioHistory(rows, old)
                    val delta = dao.observeOwnedPortfolioDeltas(OWNER, SOURCE).first()
                    assertEquals(com.dominik.cardvault.data.local.OwnedPortfolioDeltaProjection(0, if (safe) 1 else 0, null, 0L), delta.first())
                    assertEquals(if (safe) 3 else 1, delta.size)
                    val compact = com.dominik.cardvault.data.buildGuardedPortfolioHistory(delta)
                    if (safe) assertEquals(expectedHistory, compact) else assertNull(compact)
                    val actual = repository.portfolio.first()
                    assertEquals(count, actual.cards.size)
                    assertEquals(rows.map { it.entryId }.toSet(), actual.cards.map { it.entryId }.toSet())
                    assertEquals(expectedHistory, actual.history)
                    assertEquals(0, syncRequests)
                    assertEquals(before, hashes(sql)); assertNoForeignKeyErrors(sql)
                    report.getJSONArray("states").put(JSONObject().put("label", label)
                        .put("activeJoinedOwnerRows", count).put("isSafe", safe)
                        .put("projectionRows", delta.size).put("legacyRows", old.size)
                        .put("historyEqual", true).put("before", hashJson(before)).put("afterReadHashMatch", true))
                }
                state("inclusive-20000", 20_000, true)
                val template = checkNotNull(dao.getCollectionEntry(2L, OWNER))
                val extra = template.copy(id = 0L, quantity = 0, syncId = "guarded-count-extra")
                val extraId = dao.insertCollectionEntry(extra)
                assertTrue(extraId > 20_003L)
                state("exclusive-20001", 20_001, false)
                dao.updateCollectionEntry(extra.copy(id = extraId, deletedAtEpochMillis = AT + 999L))
                state("tombstone-extra-restores-safe", 20_000, true)
                assertEquals(3, report.getJSONArray("states").length())
            } finally {
                db.close(); check(!db.isOpen)
                if (files.any(File::exists)) check(context.deleteDatabase(name))
                check(files.none(File::exists))
                report.getJSONArray("cleanup").put(JSONObject().put("databaseName", name)
                    .put("closed", true).put("filesAbsent", true))
            }
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally { save(report, config) }
    }

    @Test fun localRoomFirstPositiveCounterfactualEdgeParity() = runBlocking<Unit> {
        val config = config()
        assumeTrue("First-positive edge proof not requested", InstrumentationRegistry.getArguments()
            .getString("release27RoomHistoryPostFix") == "phase-first-positive")
        require(config.sizes == listOf(1_000) && config.repeats == 3)
        val report = report(config, "localRoomFirstPositiveEdgeParity")
            .put("fixtureVersion", 2).put("historyDays", JSONArray(listOf(2)))
            .put("scope", "Fresh annual2 synthetic fixture, explicit local mutations, read parity after each; no product changes and no production/user database")
            .put("cleanup", JSONArray()).put("states", JSONArray())
        val name = freshName("first-positive-edges")
        val files = checkedPostFixFiles(name)
        val captured = java.util.concurrent.atomic.AtomicReference<HistoryProbeSql?>()
        val db = Room.databaseBuilder(context, CardVaultDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback(RoomDatabase.QueryCallback { statement, bindings ->
                val normalized = statement.replace(Regex("\\s+"), " ").trim()
                if (normalized.startsWith("SELECT p.cardId, p.effectiveAtEpochMillis, p.trendValueMinor, p.holoTrendValueMinor FROM raw_price_snapshots p WHERE p.source = ?") &&
                    bindings == listOf(SOURCE, OWNER)) {
                    captured.compareAndSet(null, HistoryProbeSql(normalized, listOf(SOURCE, OWNER)))
                }
            }, Executor { it.run() }).build()
        try {
            try {
                val sql = db.openHelper.writableDatabase
                seedAnnualHistory(sql, 2); assertSeed(sql, 1_000)
                val dao = db.cardVaultDao()
                dao.observeOwnedRawPriceHistory(OWNER, SOURCE).first()
                val originalQuery = checkNotNull(captured.get())
                suspend fun state(label: String, expectedOne: PostFixBases?, onePresent: Boolean = true) {
                    val before = hashes(sql); assertEquals(15, before.size)
                    val perOwner = JSONArray()
                    for (owner in listOf(OWNER, OTHER_OWNER, "fixture-owner-with-no-data")) {
                        val rows = dao.observeCollection(owner).first()
                        val history = dao.observeOwnedRawPriceHistory(owner, SOURCE).first()
                        val candidate = readPostFixBases(sql, postFixFirstPositiveQuery(originalQuery, owner))
                        assertEquals(postFixBasesOracle(rows.map { it.priceCardId }, history), candidate)
                        val legacyChart = com.dominik.cardvault.data.buildChronologicalPortfolioHistory(rows, history)
                        val deltas = dao.observeOwnedPortfolioDeltas(owner, SOURCE).first()
                        val safe = !(label == "explicit-quarantined-identity" && owner == OWNER)
                        assertEquals(com.dominik.cardvault.data.local.OwnedPortfolioDeltaProjection(0, if (safe) 1 else 0, null, 0L), deltas.first())
                        val compactChart = com.dominik.cardvault.data.buildGuardedPortfolioHistory(deltas)
                        if (safe) assertEquals(legacyChart, compactChart) else {
                            assertEquals(1, deltas.size); assertNull(compactChart)
                        }
                        val repository = RoomCollectionRepository(db, MutableStateFlow(owner))
                        val portfolio = repository.portfolio.first()
                        assertEquals("Every owner and original edge state keeps every chart point", legacyChart, portfolio.history)
                        postFixAssertReconstructedCards(rows, portfolio.cards, candidate)
                        val privateCards = repository.privateBinderContents(BINDER).first()
                        // This fixture retains one placement for every active entry of either owner.
                        // An intentionally owner-reassigned entry can have no matching old-owner placement.
                        val placements = dao.observePrivateBinderPlacements(BINDER, owner).first()
                        val byId = portfolio.cards.associateBy { it.entryId }
                        val expected = placements.mapNotNull { p -> byId[p.collectionEntryId]?.let { c ->
                            com.dominik.cardvault.model.PrivateBinderCard(p.id, p.binderId, p.slotPosition, c, p.holoEffectId, p.holoIntensity)
                        } }
                        assertEquals(expected, privateCards)
                        if (owner == OWNER) {
                            assertEquals(onePresent, candidate.containsKey("fixture-card-1"))
                            if (onePresent) assertEquals(expectedOne, candidate["fixture-card-1"])
                            if (label == "owned-product-without-history") {
                                assertTrue(candidate.containsKey("fixture-card-5"))
                                assertEquals(PostFixBases(null, null), candidate["fixture-card-5"])
                            }
                            if (label == "explicit-quarantined-identity") {
                                assertFalse(candidate.containsKey("fixture-card-6"))
                                assertEquals("", rows.single { it.entryId == 7L }.priceCardId)
                            }
                        }
                        perOwner.put(JSONObject().put("owner", owner).put("historyRows", history.size)
                            .put("bases", candidate.size).put("fullCardsEqual", true).put("privatePlacementsEqual", true)
                            .put("aggregateSafe", safe).put("aggregateRows", deltas.size).put("historyEqual", true))
                    }
                    assertEquals(before, hashes(sql)); assertNoForeignKeyErrors(sql)
                    report.getJSONArray("states").put(JSONObject().put("label", label)
                        .put("before", hashJson(before)).put("afterReadHashMatch", true).put("owners", perOwner))
                }
                suspend fun correct(at: Long, normal: Long?, reverse: Long?) {
                    // Genuine unique-index REPLACE, not merely UPDATE: row ID can change.
                    dao.insertRawPricesReplacing(listOf(com.dominik.cardvault.data.local.RawPriceSnapshotEntity(
                        cardId = "fixture-card-1", source = SOURCE, currency = "EUR", lowValueMinor = null,
                        averageValueMinor = null, trendValueMinor = normal, marketValueMinor = normal,
                        holoTrendValueMinor = reverse, fetchedAtEpochMillis = at, effectiveAtEpochMillis = at,
                    )))
                }
                suspend fun insert(at: Long, normal: Long?, reverse: Long?, source: String = SOURCE) {
                    dao.insertRawPrices(listOf(com.dominik.cardvault.data.local.RawPriceSnapshotEntity(
                        cardId = "fixture-card-1", source = source, currency = "EUR", lowValueMinor = null,
                        averageValueMinor = null, trendValueMinor = normal, marketValueMinor = normal,
                        holoTrendValueMinor = reverse, fetchedAtEpochMillis = at, effectiveAtEpochMillis = at,
                    )))
                }
                val first = AT + ANNUAL_DAY_MILLIS
                val second = AT + 2 * ANNUAL_DAY_MILLIS
                val third = AT + 3 * ANNUAL_DAY_MILLIS
                val earlier = AT + ANNUAL_DAY_MILLIS / 2
                state("baseline", PostFixBases(101L, 151L))
                correct(first, null, null); correct(second, 0L, -7L)
                state("all-null-zero-negative", PostFixBases(null, null))
                insert(third, -11L, 77L)
                state("reverse-first-positive-on-later-date", PostFixBases(null, 77L))
                insert(earlier, 23L, null)
                state("out-of-order-earlier-normal", PostFixBases(23L, 77L))
                correct(earlier, 0L, 31L)
                state("same-date-correction-removes-normal-base", PostFixBases(null, 31L))
                correct(third, 47L, 0L)
                state("independent-normal-reverse-dates", PostFixBases(47L, 31L))
                insert(AT + 1L, 999_999L, 888_888L, "IGNORED_HISTORY_SOURCE")
                state("wrong-source-cannot-supply-base", PostFixBases(47L, 31L))
                // Entry 3 still has its different visual card; link now points at product 1.
                sql.execSQL("UPDATE collection_entries SET externalProductId=?,finishOverride=? WHERE id=?",
                    arrayOf<Any>(90_000_001L, "Reverse Holo", 3L))
                state("duplicate-linked-product-distinct-finish", PostFixBases(47L, 31L))
                // Removing the original product-1 owner does not remove the linked sibling's base.
                sql.execSQL("UPDATE collection_entries SET deletedAtEpochMillis=? WHERE id=?", arrayOf<Any>(AT + 99L, 2L))
                state("tombstone-original-with-linked-sibling", PostFixBases(47L, 31L))
                sql.execSQL("UPDATE collection_entries SET deletedAtEpochMillis=? WHERE id=?", arrayOf<Any>(AT + 100L, 3L))
                state("tombstone-last-owner-reference", null, onePresent = false)
                sql.execSQL("UPDATE collection_entries SET ownerUid=? WHERE id=?", arrayOf<Any>(OTHER_OWNER, 4L))
                state("entry-only-owner-reassignment", null, onePresent = false)
                // Delete only rows in this fresh test-owned UUID fixture, never product data.
                sql.execSQL("DELETE FROM raw_price_snapshots WHERE cardId=?", arrayOf<Any>("fixture-card-5"))
                state("owned-product-without-history", null, onePresent = false)
                sql.execSQL("UPDATE collection_entries SET externalProductId=?,sourceCardId=? WHERE id=?",
                    arrayOf<Any>(670816L, "swsh11-201", 7L))
                state("explicit-quarantined-identity", null, onePresent = false)
                // Every price of the now unowned product remains retained; no pruning.
                assertEquals(5L, scalar(sql, "SELECT COUNT(*) FROM raw_price_snapshots WHERE cardId='fixture-card-1'"))
                assertEquals(13, report.getJSONArray("states").length())
            } finally {
                db.close(); check(!db.isOpen)
                if (files.any(File::exists)) check(context.deleteDatabase(name))
                check(files.none(File::exists))
                report.getJSONArray("cleanup").put(JSONObject().put("databaseName", name)
                    .put("closed", true).put("filesAbsent", true))
            }
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally { save(report, config) }
    }

    private data class PostFixBases(val normal: Long?, val reverse: Long?)
    private data class PostFixTimed<T>(val value: T, val elapsedMs: Double)

    private fun checkedPostFixFiles(name: String): List<File> {
        val file = context.getDatabasePath(name).canonicalFile
        check(file.name == name && file.parentFile == context.getDatabasePath("postfix-proof-parent").canonicalFile.parentFile)
        return listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
            .also { check(it.none(File::exists)) { "Only an entirely fresh UUID fixture is permitted" } }
    }

    /** Preserve the ACTUAL generated ownership/source/quarantine subquery, fail on shape drift. */
    private fun postFixFirstPositiveQuery(actual: HistoryProbeSql, owner: String): HistoryProbeSql {
        val normalized = actual.sql.replace(Regex("\\s+"), " ").trim()
        val prefix = "SELECT p.cardId, p.effectiveAtEpochMillis, p.trendValueMinor, p.holoTrendValueMinor FROM raw_price_snapshots p WHERE p.source = ? AND p.cardId IN ("
        val suffix = ") ORDER BY p.cardId, p.effectiveAtEpochMillis"
        check(actual.bindings == listOf(SOURCE, OWNER))
        check(normalized.startsWith(prefix) && normalized.endsWith(suffix))
        check(normalized.count { it == '?' } == 2)
        val membership = normalized.removePrefix(prefix).removeSuffix(suffix).trim()
        check(membership.startsWith("SELECT COALESCE(linked.id, c.id) FROM collection_entries e "))
        check(membership.count { it == '?' } == 1)
        val candidate = """
            WITH candidates(cardId) AS ($membership),
            owned(cardId) AS (SELECT DISTINCT cardId FROM candidates)
            SELECT o.cardId,
              (SELECT p.trendValueMinor FROM raw_price_snapshots p
               WHERE p.cardId=o.cardId AND p.source=? AND p.trendValueMinor>0
               ORDER BY p.effectiveAtEpochMillis ASC LIMIT 1) AS firstNormal,
              (SELECT p.holoTrendValueMinor FROM raw_price_snapshots p
               WHERE p.cardId=o.cardId AND p.source=? AND p.holoTrendValueMinor>0
               ORDER BY p.effectiveAtEpochMillis ASC LIMIT 1) AS firstReverse
            FROM owned o ORDER BY o.cardId
        """.trimIndent()
        return HistoryProbeSql(candidate, listOf(owner, SOURCE, SOURCE))
    }

    private fun readPostFixBases(sql: SupportSQLiteDatabase, query: HistoryProbeSql): Map<String, PostFixBases> {
        val result = linkedMapOf<String, PostFixBases>()
        sql.query(androidx.sqlite.db.SimpleSQLiteQuery(query.sql, query.bindings.toTypedArray())).use { cursor ->
            assertEquals(listOf("cardId", "firstNormal", "firstReverse"), cursor.columnNames.toList())
            while (cursor.moveToNext()) {
                assertFalse(cursor.isNull(0))
                val id = cursor.getString(0)
                val value = PostFixBases(if (cursor.isNull(1)) null else cursor.getLong(1),
                    if (cursor.isNull(2)) null else cursor.getLong(2))
                assertTrue(value.normal == null || value.normal > 0L)
                assertTrue(value.reverse == null || value.reverse > 0L)
                check(result.put(id, value) == null) { "Duplicate candidate product" }
            }
        }
        return result
    }

    private fun postFixBasesOracle(ids: List<String>, prices: List<com.dominik.cardvault.data.local.OwnedPriceHistoryProjection>): Map<String, PostFixBases> {
        assertTrue(prices.zipWithNext().all { (a, b) -> a.effectiveAtEpochMillis <= b.effectiveAtEpochMillis })
        val grouped = prices.groupBy { it.cardId }
        return ids.filter { it.isNotEmpty() }.distinct().associateWith { id ->
            val history = grouped[id].orEmpty()
            PostFixBases(history.firstOrNull { (it.trendValueMinor ?: 0L) > 0L }?.trendValueMinor,
                history.firstOrNull { (it.holoTrendValueMinor ?: 0L) > 0L }?.holoTrendValueMinor)
        }
    }

    private fun postFixAssertReconstructedCards(
        rows: List<com.dominik.cardvault.data.local.CollectionProjection>,
        cards: List<com.dominik.cardvault.model.CollectionCard>, bases: Map<String, PostFixBases>,
    ) {
        val byId = rows.associateBy { it.entryId }
        assertEquals(rows.size, cards.size)
        assertEquals(byId.keys, cards.map { it.entryId }.toSet())
        val candidate = cards.map { card ->
            val row = byId.getValue(card.entryId)
            val base = bases[row.priceCardId]
            val first = if (row.finish.contains("reverse", true)) base?.reverse else base?.normal
            val latest = row.marketValueMinor?.takeIf { it > 0L }
            val change = if (first != null && latest != null) (latest - first) * 100.0 / first else 0.0
            card.copy(changePercent = change)
        }
        assertEquals("Full records retain all non-derived fields and exact first-positive change", cards, candidate)
    }

    private fun postFixAssertAnnualPortfolio(portfolio: com.dominik.cardvault.model.PortfolioSummary) {
        val live = (0 until 1_000).filterNot(::deleted)
        assertEquals(live.map { it + 1L }.toSet(), portfolio.cards.map { it.entryId }.toSet())
        assertEquals(365, portfolio.history.size)
        portfolio.history.forEachIndexed { point, value ->
            assertEquals(live.sumOf { annualValueMinor(it, point) * quantity(it) } / 100.0, value.value, .00001)
            val label = java.time.Instant.ofEpochMilli(AT + (point + 1) * ANNUAL_DAY_MILLIS)
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("MMM"))
            assertEquals(label, value.label)
        }
        portfolio.cards.forEach { card ->
            val index = (card.entryId - 1).toInt()
            val first = annualValueMinor(index, 0)
            val last = annualValueMinor(index, 364)
            assertEquals(last / 100.0, card.currentValueEur, .00001)
            assertEquals((last - first) * 100.0 / first, card.changePercent, .00001)
            assertEquals(AT + 365 * ANNUAL_DAY_MILLIS, card.priceUpdatedAtEpochMillis)
        }
    }

    private fun postFixExplain(sql: SupportSQLiteDatabase, query: HistoryProbeSql): JSONArray {
        val result = JSONArray()
        sql.query(androidx.sqlite.db.SimpleSQLiteQuery("EXPLAIN QUERY PLAN " + query.sql, query.bindings.toTypedArray())).use { cursor ->
            assertEquals(4, cursor.columnCount)
            while (cursor.moveToNext()) {
                check(result.length() < 64)
                result.put(JSONObject().put("id", cursor.getLong(0)).put("parent", cursor.getLong(1))
                    .put("aux", cursor.getLong(2)).put("detail", cursor.getString(3)))
            }
        }
        check(result.length() > 0)
        return result
    }

    @Test fun localRoomAnnualHistoryAtBoundedSyntheticScale() = runBlocking<Unit> {
        val config = config() // Existing emulator-only guard, safe label and opt-in.
        assumeTrue("Annual history proof not requested", InstrumentationRegistry.getArguments()
            .getString("release27RoomAnnualHistory") == "2-vs-365")
        require(config.sizes == listOf(1_000) && config.repeats == 3)
        val size = 1_000
        val report = report(config, "localRoomAnnualHistory")
            .put("fixtureVersion", 2)
            .put("fixture", "1000 owner-A products plus 3 owner-B sentinels; 50 A tombstones; separate 2/365 daily-date files")
            .put("historyDays", JSONArray(listOf(2, 365)))
            .put("cleanup", JSONArray())
            .put("annualScope", "Retained synthetic SQLite history only; not 365 daily imports, peak memory, UI or whole-catalog capacity")
        try {
            for (days in listOf(2, 365)) {
                val name = freshName("annual-$days")
                val file = context.getDatabasePath(name).canonicalFile
                check(file.name == name && file.parentFile == context.getDatabasePath("annual-proof-parent").canonicalFile.parentFile)
                val files = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
                check(files.none(File::exists)) { "Annual proof requires an entirely fresh named test database" }
                val counter = SqlCounter()
                val db = Room.databaseBuilder(context, CardVaultDatabase::class.java, name)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> counter.record(sql) }, Executor { it.run() })
                    .build()
                try {
                    val sql = db.openHelper.writableDatabase
                    annualMeasured(report, "seed", days, -1, name, counter) { seedAnnualHistory(sql, days) }
                    assertSeed(sql, size)
                    assertEquals((size + OTHER_ROWS) * days.toLong(), scalar(sql, "SELECT COUNT(*) FROM raw_price_snapshots"))
                    assertEquals(days.toLong(), scalar(sql, "SELECT COUNT(DISTINCT effectiveAtEpochMillis) FROM raw_price_snapshots"))
                    assertEquals(AT + ANNUAL_DAY_MILLIS, scalar(sql, "SELECT MIN(effectiveAtEpochMillis) FROM raw_price_snapshots"))
                    assertEquals(AT + days * ANNUAL_DAY_MILLIS, scalar(sql, "SELECT MAX(effectiveAtEpochMillis) FROM raw_price_snapshots"))
                    val ownedRows = scalar(sql, """
                        SELECT COUNT(*) FROM raw_price_snapshots p
                        WHERE p.source = '$SOURCE' AND p.cardId IN (
                            SELECT cardId FROM collection_entries
                            WHERE ownerUid = '$OWNER' AND deletedAtEpochMillis IS NULL
                        )
                    """)
                    assertEquals(950L * days, ownedRows)
                    val before = hashes(sql)
                    report.getJSONArray("integrity").put(JSONObject()
                        .put("size", size).put("historyDays", days).put("ownedHistoryRows", ownedRows)
                        .put("before", hashJson(before)))
                    val owner = MutableStateFlow(OWNER)
                    val repository = RoomCollectionRepository(db, owner)
                    val dao = db.cardVaultDao()
                    val live = (0 until size).filterNot(::deleted)
                    val expectedIds = live.map { it + 1L }.toSet()
                    val expectedQuantity = live.sumOf(::quantity)
                    val expectedMinor = live.sumOf { annualValueMinor(it, days - 1) * quantity(it) }
                    for (sample in 0..config.repeats) {
                        val rows = annualMeasured(report, "collection.first", days, sample, name, counter) {
                            dao.observeCollection(OWNER).first()
                        }
                        assertEquals(expectedIds, rows.map { it.entryId }.toSet())
                        assertEquals(expectedIds.size, rows.size)
                        assertEquals(expectedMinor, rows.sumOf { (it.marketValueMinor ?: 0L) * it.quantity })
                        rows.forEach { row ->
                            val index = (row.entryId - 1L).toInt()
                            assertEquals(annualValueMinor(index, days - 1), row.marketValueMinor)
                            assertEquals(AT + days * ANNUAL_DAY_MILLIS, row.priceUpdatedAtEpochMillis)
                        }
                        val sets = annualMeasured(report, "setBinderSummaries.first", days, sample, name, counter) {
                            repository.binders.first()
                        }
                        assertEquals(size / SET_SIZE, sets.size)
                        assertEquals(expectedQuantity, sets.sumOf { it.ownedCards })
                        assertEquals(expectedMinor / 100.0, sets.sumOf { it.marketValueEur }, .00001)
                        val binders = annualMeasured(report, "privateBinderSummaries.first", days, sample, name, counter) {
                            repository.privateBinders.first()
                        }
                        assertEquals(listOf(BINDER), binders.map { it.id })
                        assertEquals(expectedIds.size, binders.single().uniqueCards)
                        assertEquals(expectedQuantity, binders.single().ownedCards)
                        assertEquals(expectedMinor / 100.0, binders.single().marketValueEur, .00001)
                        val portfolio = annualMeasured(report, "portfolio.first", days, sample, name, counter) {
                            repository.portfolio.first()
                        }
                        assertEquals(expectedIds, portfolio.cards.map { it.entryId }.toSet())
                        assertEquals(expectedIds.size, portfolio.cards.size)
                        assertEquals(expectedQuantity, portfolio.totalCards)
                        assertEquals(expectedMinor / 100.0, portfolio.totalValue, .00001)
                        assertEquals(days, portfolio.history.size)
                        portfolio.history.forEachIndexed { point, value ->
                            val minor = live.sumOf { annualValueMinor(it, point) * quantity(it) }
                            assertEquals("History value at $point/$days", minor / 100.0, value.value, .00001)
                            val label = java.time.Instant.ofEpochMilli(AT + (point + 1) * ANNUAL_DAY_MILLIS)
                                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                                .format(java.time.format.DateTimeFormatter.ofPattern("MMM"))
                            assertEquals("History label at $point/$days", label, value.label)
                        }
                        portfolio.cards.forEach { card ->
                            val index = (card.entryId - 1L).toInt()
                            val first = annualValueMinor(index, 0)
                            val latest = annualValueMinor(index, days - 1)
                            assertEquals(latest / 100.0, card.currentValueEur, .00001)
                            assertEquals((latest - first) * 100.0 / first, card.changePercent, .00001)
                            assertEquals(AT + days * ANNUAL_DAY_MILLIS, card.priceUpdatedAtEpochMillis)
                        }
                        val cards = annualMeasured(report, "privateBinderContents.first", days, sample, name, counter) {
                            repository.privateBinderContents(BINDER).first()
                        }
                        assertEquals(expectedIds.size, cards.size)
                        assertEquals(portfolio.cards.associateBy { it.entryId }, cards.associate { it.card.entryId to it.card })
                        assertEquals(expectedIds.map { it - 1L }.toSet(), cards.map { it.position }.toSet())
                        val pending = annualMeasured(report, "pendingCollectionOutbox.limit200", days, sample, name, counter) {
                            dao.pendingSyncOutbox(OWNER, 200)
                        }
                        assertEquals((0 until size).count { state(it) == "PENDING" }.coerceAtMost(200), pending.size)
                        assertTrue(pending.all { it.ownerUid == OWNER && it.state == "PENDING" })
                        val privatePending = annualMeasured(report, "pendingPrivateOutbox.limit200", days, sample, name, counter) {
                            dao.pendingPrivateBinderSyncOutbox(OWNER, 200)
                        }
                        assertEquals(200, privatePending.size)
                        assertTrue(privatePending.all { it.ownerUid == OWNER })
                    }
                    owner.value = OTHER_OWNER
                    val other = repository.portfolio.first()
                    assertEquals((size until size + OTHER_ROWS).map { it + 1L }.toSet(), other.cards.map { it.entryId }.toSet())
                    assertEquals(OTHER_ROWS, other.cards.size)
                    assertEquals(days, other.history.size)
                    other.history.forEachIndexed { point, value ->
                        val minor = (size until size + OTHER_ROWS).sumOf { annualValueMinor(it, point) * quantity(it) }
                        assertEquals(minor / 100.0, value.value, .00001)
                    }
                    owner.value = "fixture-owner-with-no-data"
                    val empty = repository.portfolio.first()
                    assertTrue(empty.cards.isEmpty() && empty.history.isEmpty())
                    assertTrue(repository.privateBinders.first().isEmpty())
                    assertTrue(repository.privateBinderContents(BINDER).first().isEmpty())
                    owner.value = OWNER
                    assertEquals(expectedIds, repository.portfolio.first().cards.map { it.entryId }.toSet())
                    assertEquals("Every existing field must survive all reads", before, hashes(sql))
                    assertNoForeignKeyErrors(sql)
                    report.getJSONArray("integrity").put(JSONObject()
                        .put("size", size).put("historyDays", days).put("afterReadHashMatch", true))
                } finally {
                    db.close()
                    check(!db.isOpen)
                    if (files.any(File::exists)) check(context.deleteDatabase(name))
                    check(files.none(File::exists)) { "Annual proof's exact SQLite fixture remains" }
                    report.getJSONArray("cleanup").put(JSONObject()
                        .put("historyDays", days).put("databaseName", name)
                        .put("closed", true).put("filesAbsent", true))
                }
                save(report, config)
            }
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally { save(report, config) }
    }

    private suspend fun <T> annualMeasured(report: JSONObject, operation: String, days: Int, sample: Int,
        name: String, counter: SqlCounter, block: suspend () -> T): T {
        val result = measured(report, operation, 1_000, sample, name, counter, block)
        val samples = report.getJSONArray("samples")
        samples.getJSONObject(samples.length() - 1).put("historyDays", days)
        return result
    }

    /** Existing complete relational fixture; only this fresh test database gains more dates. */
    private fun seedAnnualHistory(db: SupportSQLiteDatabase, days: Int) {
        require(days in setOf(2, 365))
        seed(db, 1_000) // Existing two-point fixture remains unchanged for all existing callers.
        db.beginTransaction()
        try {
            db.execSQL("""
                UPDATE raw_price_snapshots SET
                    effectiveAtEpochMillis = ? + ((effectiveAtEpochMillis - ?) / 1000) * ?,
                    fetchedAtEpochMillis = ? + ((fetchedAtEpochMillis - ?) / 1000) * ?
            """, arrayOf<Any>(AT, AT, ANNUAL_DAY_MILLIS, AT, AT, ANNUAL_DAY_MILLIS))
            FixtureWriter(db).use { out ->
                for (point in 2 until days) for (index in 0 until 1_000 + OTHER_ROWS) {
                    val trend = 100L + index % 9_000 + point
                    val at = AT + (point + 1) * ANNUAL_DAY_MILLIS
                    val id = (1_000 + OTHER_ROWS) * 2L + (point - 2L) * (1_000 + OTHER_ROWS) + index + 1L
                    out.insert("raw_price_snapshots", "id,cardId,source,currency,lowValueMinor,averageValueMinor,trendValueMinor,marketValueMinor,average1DayValueMinor,average7DayValueMinor,average30DayValueMinor,holoAverageValueMinor,holoLowValueMinor,holoTrendValueMinor,fetchedAtEpochMillis,effectiveAtEpochMillis",
                        id, "fixture-card-$index", SOURCE, "EUR", trend - 20, trend - 10, trend, trend,
                        trend - 1, trend - 2, trend - 3, trend + 45, trend + 40, trend + 50, at, at)
                }
            }
            db.execSQL("UPDATE catalog_sync_state SET priceRecordCount = ? WHERE source = ?",
                arrayOf<Any>((1_000 + OTHER_ROWS) * days, SOURCE))
            db.execSQL("UPDATE app_metadata SET value = ? WHERE `key` = ?", arrayOf<Any>(
                "version=2;size=1000;historyDays=$days", "synthetic-benchmark-fixture"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // Independent value oracle. Never invokes repository/DAO pricing helpers.
    private fun annualValueMinor(index: Int, point: Int): Long =
        100L + index % 9_000 + point + if (index % 7 == 0) 50L else 0L

    private val ANNUAL_DAY_MILLIS: Long get() = 86_400_000L

    @Test fun localRoomMigration12To13PreservesEverySeededField() = runBlocking<Unit> {
        val config = config()
        val report = report(config, "localRoomMigration12To13")
        try {
            for (size in config.sizes) for (sample in 1..config.repeats) {
                val name = freshName("migration")
                try {
                    val before = migrationHelper.createDatabase(name, 12).use { old ->
                        seed(old, size)
                        assertSeed(old, size)
                        hashes(old)
                    }
                    val migrationStatements = AtomicLong()
                    val countedMigration = object : Migration(12, 13) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            // This counter covers the actual migration body only, not helper validation.
                            CardVaultDatabase.MIGRATION_12_13.migrate(object : SupportSQLiteDatabase by db {
                                override fun execSQL(sql: String) {
                                    migrationStatements.incrementAndGet()
                                    db.execSQL(sql)
                                }
                                override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                                    migrationStatements.incrementAndGet()
                                    db.execSQL(sql, bindArgs)
                                }
                            })
                        }
                    }
                    val migrated = measured(report, "migrationAndSchemaValidation", size, sample, name, null) {
                        migrationHelper.runMigrationsAndValidate(name, 13, true, countedMigration)
                    }
                    migrated.use { upgraded ->
                        assertEquals(13, upgraded.version)
                        val after = hashes(upgraded, before.keys.toList())
                        assertEquals("All v12 rows, NULLs, fields, revisions and SQLite sequences must survive", before, after)
                        assertEquals(0L, scalar(upgraded, "SELECT COUNT(*) FROM collection_price_selections"))
                        assertSeed(upgraded, size)
                        assertNoForeignKeyErrors(upgraded)
                        report.getJSONArray("integrity").put(JSONObject()
                            .put("size", size).put("sample", sample).put("allOldFieldHashesMatch", true)
                            .put("newPriceSelectionRows", 0).put("migrationBodySqlStatements", migrationStatements.get())
                            .put("migrationBodySqlCounterScope", "execSQL in MIGRATION_12_13 only; helper validation excluded")
                            .put("oldTables", hashJson(before)).put("databaseBytesAfter", databaseBytes(name)))
                    }
                    // Also validate the migrated file through Room/DAOs, not merely raw SQLite.
                    val reopened = Room.databaseBuilder(context, CardVaultDatabase::class.java, name).build()
                    try {
                        val rows = reopened.cardVaultDao().observeCollection(OWNER).first()
                        assertEquals(size - (0 until size).count(::deleted), rows.size)
                        assertEquals(before, hashes(reopened.openHelper.writableDatabase, before.keys.toList()))
                    } finally { reopened.close() }
                } finally { context.deleteDatabase(name) }
                save(report, config)
            }
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally { save(report, config) }
    }

    @Test fun localRoomPortfolioAggregateCrossesRealWindowsWithAtomicCorrections() = runBlocking<Unit> {
        val config = config()
        assumeTrue("Explicit multi-window proof not requested", InstrumentationRegistry.getArguments()
            .getString("release27RoomPortfolioWindows") == "atomic-corrections")
        require(config.sizes == listOf(1_000) && config.repeats == 3)
        val report = report(config, "localRoomPortfolioAggregateWindows")
            .put("fixtureVersion", 1).put("fixture", "One product, two finishes, 100000 distinct timestamps; other-owner/deleted/source sentinels")
            .put("scope", "Fresh owned local DB only; unchanged real Room SQL/cursors; no network, product singleton or engine/window-size switch")
            .put("passCriteria", "Exact types/tuples, actual window crossings, complete atomic-state oracle, unchanged unrelated fields and verified cleanup")
            .put("deadlineScope", "Finite cursor barriers and writer joins; enclosing 180s timeout is cooperative, not a hard native-SQL deadline")
            .put("remaining", "No API26 result without an actual API26 run; writer serialization is not overlapping-commit proof")
            .put("phases", JSONArray()).put("cleanup", JSONArray())
        val name = freshName("portfolio-windows")
        val files = checkedPostFixFiles(name)
        val audit = PortfolioWindowAudit()
        val writers = mutableListOf<PortfolioWindowWriter>()
        val db = Room.databaseBuilder(context, CardVaultDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(PortfolioWindowFactory(audit))
            .setQueryCallback(RoomDatabase.QueryCallback(audit::callback), Executor { it.run() }).build()
        var failure: Throwable? = null
        try {
            withTimeout(180_000L) {
                val sql = db.openHelper.writableDatabase
                assertEquals("wal", sql.query("PRAGMA journal_mode").use { c ->
                    assertTrue(c.moveToFirst()); c.getString(0).lowercase(java.util.Locale.ROOT)
                })
                report.put("sqliteVersion", sql.query("SELECT sqlite_version()").use { c ->
                    assertTrue(c.moveToFirst()); c.getString(0)
                })
                seedPortfolioWindows(sql, name)
                assertPortfolioWindowFixture(sql, name, generationB = false, unsafe = false)
                val before = hashes(sql); assertEquals(15, before.size)
                val nonempty = setOf("catalog_cards", "collection_entries", "raw_price_snapshots", "app_metadata", "sqlite_sequence")
                before.filterKeys { it !in nonempty }.forEach { (table, value) -> assertEquals("Initially empty $table", 0L, value.rows) }
                assertEquals(nonempty, before.filterValues { it.rows > 0 }.keys)
                report.getJSONArray("integrity").put(JSONObject().put("before", hashJson(before)))
                val dao = db.cardVaultDao()
                val initial = PortfolioWindowRead("initial-safe", pause = false)
                audit.arm(initial)
                val initialRows = try { dao.observeOwnedPortfolioDeltas(OWNER, SOURCE).first() }
                    finally { audit.disarm(initial) }
                assertPortfolioWindowDeltas(initialRows, generationB = false)
                initial.assertComplete(); report.getJSONArray("phases").put(initial.json())
                assertEquals("Read-only initial proof preserves all fields", before, hashes(sql))
                assertEquals(listOf(com.dominik.cardvault.data.local.OwnedPortfolioDeltaProjection(0, 1, null, 0L)),
                    dao.observeOwnedPortfolioDeltas("window-owner-empty", SOURCE).first())
                assertEquals(listOf(com.dominik.cardvault.data.local.OwnedPortfolioDeltaProjection(0, 0, null, 0L)),
                    dao.observeOwnedPortfolioDeltas(OTHER_OWNER, SOURCE).first())
                var overlapping = 0
                for (unsafe in listOf(false, true)) {
                    // Restore A using an explicit fixture transaction, never product writes.
                    resetPortfolioWindows(sql)
                    assertPortfolioWindowFixture(sql, name, generationB = false, unsafe = false)
                    assertEquals(before, hashes(sql))
                    val probe = PortfolioWindowRead(if (unsafe) "safe-to-unsafe" else "safe-to-safe", pause = true)
                    val writer = PortfolioWindowWriter(files.first(), name, unsafe)
                    writers += writer
                    audit.arm(probe)
                    val rows = coroutineScope {
                        val read = async(Dispatchers.IO) {
                            try { dao.observeOwnedPortfolioDeltas(OWNER, SOURCE).first() }
                            catch (t: Throwable) { probe.cancellation.cancel(); throw t }
                        }
                        try {
                            check(withContext(Dispatchers.IO) { probe.ready.await(60, java.util.concurrent.TimeUnit.SECONDS) }) {
                                "No real cursor-window boundary was reached"
                            }
                            writer.start()
                            check(withContext(Dispatchers.IO) { writer.attempt.await(5, java.util.concurrent.TimeUnit.SECONDS) }) {
                                "Writer did not reach its transaction attempt"
                            }
                            // An exclusive fallback may serialize the writer. Never wait for its
                            // commit while indefinitely holding the paused read transaction.
                            withContext(Dispatchers.IO) { writer.done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
                            probe.release.countDown()
                            val result = read.await()
                            check(withContext(Dispatchers.IO) { writer.done.await(15, java.util.concurrent.TimeUnit.SECONDS) }) {
                                "Writer did not complete after the reader released its transaction"
                            }
                            writer.rethrow()
                            result
                        } finally {
                            writer.cancelled.set(true)
                            probe.release.countDown()
                            probe.cancellation.cancel()
                            read.cancel()
                            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { writer.joinBounded() }
                            audit.disarm(probe)
                        }
                    }
                    assertPortfolioWindowDeltas(rows, generationB = false)
                    probe.assertComplete()
                    assertTrue(writer.committedAt.get() > 0L)
                    val committedBeforeRefill = writer.committedAt.get() in
                        (probe.pausedAt.get() + 1) until probe.refillStartedAt.get()
                    if (committedBeforeRefill) overlapping++
                    report.getJSONArray("phases").put(probe.json().put("writer", writer.json())
                        .put("fullOriginalGenerationA", true)
                        .put("commitBeforeRefill", committedBeforeRefill)
                        .put("scheduleCoverage", if (committedBeforeRefill) "OVERLAPPING_COMMIT_PROVED"
                            else "COHERENT_READ_PROVED_OVERLAPPING_COMMIT_NOT_PROVED"))
                    assertPortfolioWindowFixture(sql, name, generationB = true, unsafe = unsafe)
                    assertPortfolioWindowUnchangedColumns(before, hashes(sql), unsafe)
                    val after = dao.observeOwnedPortfolioDeltas(OWNER, SOURCE).first()
                    if (unsafe) {
                        assertEquals(listOf(com.dominik.cardvault.data.local.OwnedPortfolioDeltaProjection(0, 0, null, 0L)), after)
                        val legacyBefore = audit.legacyCalls.get()
                        var syncRequests = 0
                        val portfolio = RoomCollectionRepository(db, MutableStateFlow(OWNER),
                            onSyncNeeded = { syncRequests++ }).portfolio.first()
                        assertTrue("Actual unsafe public path must use the unchanged raw-history fallback",
                            audit.legacyCalls.get() > legacyBefore)
                        val lastIndex = PortfolioWindowFixture.COUNT - 1L
                        val expectedCards = listOf(1, 0).map { entry ->
                            val reverse = entry == 1
                            val firstMinor = if (reverse) 2_000L else 1_000L
                            val lastMinor = if (reverse) 2_000L + 5L * lastIndex else 1_000L + 3L * lastIndex
                            com.dominik.cardvault.model.CollectionCard(
                                entryId = entry + 1L, id = PortfolioWindowFixture.CARD, name = "Synthetic 0",
                                set = "Window fixture", number = "0/3", language = com.dominik.cardvault.model.CardLanguage.GERMAN,
                                finish = if (reverse) "Reverse Holo" else "Normal", variant = "Standard", condition = "Near Mint",
                                quantity = if (reverse) 3 else 1_000_001, currentValueEur = lastMinor / 100.0,
                                changePercent = (lastMinor - firstMinor) * 100.0 / firstMinor,
                                priceUpdatedAtEpochMillis = AT + lastIndex * 60_000L, notes = "Window fixture $entry",
                                acquisitionPriceEur = null, acquisitionDateEpochDay = null, accent = 0xFF6C78A8,
                                binderId = "inbox", externalProductId = null, imageUrl = null, rarity = "UNKNOWN",
                                effectOverride = null, marketSearchName = null, marketExpansionId = null,
                                sourceSetId = null, sourceCardId = null, priceSelection = null)
                        }
                        // Distinct updatedAt values make the complete reverse-then-normal order unambiguous.
                        assertEquals(expectedCards, portfolio.cards)
                        assertEquals(1_000_004, portfolio.totalCards)
                        assertEquals(PortfolioWindowFixture.COUNT, portfolio.history.size)
                        portfolio.history.forEachIndexed { i, point ->
                            val normal = java.math.BigInteger.valueOf(1_000L + 3L * i)
                            val reverse = java.math.BigInteger.valueOf(2_000L + 5L * i)
                            val expected = normal.multiply(java.math.BigInteger.valueOf(1_000_001L))
                                .add(reverse.multiply(java.math.BigInteger.valueOf(3L)))
                            assertEquals(expected.toDouble() / 100.0, point.value, 0.0)
                            val label = java.time.Instant.ofEpochMilli(AT + i * 60_000L)
                                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                                .format(java.time.format.DateTimeFormatter.ofPattern("MMM"))
                            assertEquals(label, point.label)
                        }
                        // Current total is deliberately Euro-Double per card, unlike minor-unit history.
                        val expectedTotal = ((2_000L + 5L * lastIndex) / 100.0) * 3 +
                            ((1_000L + 3L * lastIndex) / 100.0) * 1_000_001
                        assertEquals(expectedTotal, portfolio.totalValue, 0.0)
                        assertEquals(0, syncRequests)
                        sql.execSQL("UPDATE collection_entries SET quantity=2 WHERE id=1 AND ownerUid=? AND quantity=1000001", arrayOf(OWNER))
                        assertPortfolioWindowFixture(sql, name, generationB = true, unsafe = false)
                        assertPortfolioWindowDeltas(dao.observeOwnedPortfolioDeltas(OWNER, SOURCE).first(), generationB = true)
                    } else assertPortfolioWindowDeltas(after, generationB = true)
                    assertNoForeignKeyErrors(sql)
                }
                audit.checkHealthy()
                val captured = checkNotNull(audit.actualSql.get())
                report.put("actualAggregateSql", captured)
                    .put("actualAggregateSqlSha256", MessageDigest.getInstance("SHA-256")
                        .digest(captured.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
                    .put("actualAggregateBindings", JSONArray(listOf(OWNER, SOURCE, SOURCE, SOURCE)))
                    .put("callbackCount", audit.callbackCount.get()).put("cursorCount", audit.cursorCount.get())
                    .put("overlappingCommitPhases", overlapping).put("requiredOverlappingCommitPhases", 2)
                    .put("multiWindowCoherence", "PASS")
                    .put("overlappingCommitCoverage", if (overlapping == 2) "PASS" else "INCOMPLETE")
                resetPortfolioWindows(sql)
                assertPortfolioWindowFixture(sql, name, generationB = false, unsafe = false)
                assertEquals("Exact original 15-table fixture restored", before, hashes(sql))
                report.getJSONArray("integrity").put(JSONObject().put("restoredAll15Hashes", true))
                // A serialized schedule is a useful subproof, not a full concurrency PASS.
                report.put("status", if (overlapping == 2) "PASS" else "PARTIAL_SERIALIZED_OR_UNPROVED_OVERLAP")
            }
        } catch (t: Throwable) {
            failure = t
            report.put("status", "FAIL").put("failureType", t.javaClass.name).put("failure", t.message)
            throw t
        } finally {
            audit.active.get()?.let { it.release.countDown(); it.cancellation.cancel() }
            writers.forEach { it.cancelled.set(true) }
            var cleanupError: Throwable? = null
            try {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    writers.forEach { it.joinBounded() }
                }
                writers.forEach { it.rethrow() }
            } catch (t: Throwable) { cleanupError = t }
            // Never close/delete a database underneath a still-running native writer.
            if (writers.none { it.isAlive }) {
                try {
                    db.close()
                    context.deleteDatabase(name)
                    check(files.none(File::exists)) { "Owned fixture or sidecars remain" }
                } catch (t: Throwable) {
                    if (cleanupError == null) cleanupError = t else cleanupError!!.addSuppressed(t)
                }
            } else if (cleanupError == null) cleanupError = AssertionError("Writer still alive; owned fixture retained")
            report.getJSONArray("cleanup").put(JSONObject().put("name", name)
                .put("workersTerminated", writers.none { it.isAlive }).put("filesAbsent", files.none(File::exists))
                .put("error", cleanupError?.toString() ?: JSONObject.NULL))
            report.put("writers", JSONArray().apply { writers.forEach { put(it.json()) } })
            if (cleanupError != null) {
                report.put("status", "FAIL")
                failure?.addSuppressed(cleanupError!!)
            }
            save(report, config)
            if (failure == null && cleanupError != null) throw cleanupError!!
        }
    }

    private object PortfolioWindowFixture {
        const val COUNT = 100_000
        const val CARD = "window-card"
        const val OTHER_CARD = "window-other-card"
        const val DELETED_CARD = "window-deleted-card"
        const val OTHER_SOURCE = "WINDOW_OTHER_SOURCE"
        const val CARD_COLUMNS = "id,tcg,setId,setName,name,collectorNumber,language,finish,variant,createdAtEpochMillis,externalSource,externalProductId,imageUrl,rarity,effectOverride"
        const val ENTRY_COLUMNS = "id,cardId,quantity,condition,acquisitionPriceMinor,acquisitionCurrency,acquisitionDateEpochDay,notes,frontPhotoUri,backPhotoUri,createdAtEpochMillis,updatedAtEpochMillis,externalProductSource,externalProductId,binderId,ownerUid,syncId,syncState,remoteRevision,deletedAtEpochMillis,finishOverride,variantOverride,effectOverride,sourceSetId,sourceCardId"
        const val PRICE_COLUMNS = "id,cardId,source,currency,lowValueMinor,averageValueMinor,trendValueMinor,marketValueMinor,average1DayValueMinor,average7DayValueMinor,average30DayValueMinor,holoAverageValueMinor,holoLowValueMinor,holoTrendValueMinor,fetchedAtEpochMillis,effectiveAtEpochMillis"
        fun card(i: Int): List<Any?> = listOf(listOf(CARD, OTHER_CARD, DELETED_CARD)[i], "POKEMON", "window-set", "Window fixture", "Synthetic $i", "$i/3", "GERMAN", "Normal", "Standard", AT, null, null, null, "UNKNOWN", null)
        fun entry(i: Int, unsafe: Boolean): List<Any?> = listOf(i + 1L,
            when (i) { 0, 1 -> CARD; 2 -> OTHER_CARD; else -> DELETED_CARD },
            when (i) { 0 -> if (unsafe) 1_000_001L else 2L; 1 -> 3L; else -> 1_000_001L },
            "Near Mint", null, null, null, "Window fixture $i", null, null, AT, AT + i,
            null, null, "inbox", if (i == 2) OTHER_OWNER else OWNER, "window-entry-$i", "LOCAL_ONLY", 0L,
            if (i == 3) AT else null, if (i == 1) "Reverse Holo" else null, null, null, null, null)
        fun price(i: Int, generationB: Boolean): List<Any?> {
            val owned = i < COUNT
            val card = when (i) { COUNT -> OTHER_CARD; COUNT + 1 -> DELETED_CARD; else -> CARD }
            val source = if (i == COUNT + 2) OTHER_SOURCE else SOURCE
            val normal = if (!owned) Long.MAX_VALUE else if (generationB) 1_000L + 3L * i else 100L + i
            val reverse = if (!owned) Long.MAX_VALUE else if (generationB) 2_000L + 5L * i else 200L + 2L * i
            return listOf(i + 1L, card, source, "EUR", null, null, normal, null, null, null, null,
                null, null, reverse, AT, if (owned) AT + i * 60_000L else AT - 60_000L)
        }
    }

    private fun seedPortfolioWindows(sql: SupportSQLiteDatabase, name: String) {
        sql.beginTransaction()
        try {
            FixtureWriter(sql).use { out ->
                repeat(3) { out.insert("catalog_cards", PortfolioWindowFixture.CARD_COLUMNS, *PortfolioWindowFixture.card(it).toTypedArray()) }
                repeat(4) { out.insert("collection_entries", PortfolioWindowFixture.ENTRY_COLUMNS, *PortfolioWindowFixture.entry(it, false).toTypedArray()) }
                repeat(PortfolioWindowFixture.COUNT + 3) { out.insert("raw_price_snapshots", PortfolioWindowFixture.PRICE_COLUMNS,
                    *PortfolioWindowFixture.price(it, false).toTypedArray()) }
                out.insert("app_metadata", "key,value", "portfolio-window-owned-fixture", name)
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
    }

    private fun resetPortfolioWindows(sql: SupportSQLiteDatabase) {
        sql.beginTransaction()
        try {
            sql.execSQL("UPDATE raw_price_snapshots SET trendValueMinor=100+(effectiveAtEpochMillis-?)/60000, holoTrendValueMinor=200+2*((effectiveAtEpochMillis-?)/60000) WHERE cardId=? AND source=?",
                arrayOf(AT, AT, PortfolioWindowFixture.CARD, SOURCE))
            sql.execSQL("UPDATE collection_entries SET quantity=2 WHERE id=1 AND ownerUid=?", arrayOf(OWNER))
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
    }

    private fun assertPortfolioWindowFixture(sql: SupportSQLiteDatabase, name: String, generationB: Boolean, unsafe: Boolean) {
        fun table(table: String, columns: String, count: Int, expected: (Int) -> List<Any?>) {
            val names = columns.split(',')
            val actualNames = sql.query("PRAGMA table_info($table)").use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
            assertEquals("Every fixture column is accounted for", actualNames, names.toSet())
            sql.query("SELECT $columns FROM $table ORDER BY id").use { c ->
                var row = 0
                while (c.moveToNext()) {
                    check(row < count)
                    val values = expected(row)
                    assertEquals(names.size, values.size)
                    values.forEachIndexed { column, value ->
                        val label = "$table row=$row column=${names[column]}"
                        when (value) {
                            null -> assertEquals(label, Cursor.FIELD_TYPE_NULL, c.getType(column))
                            is Number -> { assertEquals(label, Cursor.FIELD_TYPE_INTEGER, c.getType(column)); assertEquals(label, value.toLong(), c.getLong(column)) }
                            else -> { assertEquals(label, Cursor.FIELD_TYPE_STRING, c.getType(column)); assertEquals(label, value, c.getString(column)) }
                        }
                    }
                    row++
                }
                assertEquals(count, row)
            }
        }
        // Catalog IDs have lexical order CARD, DELETED_CARD, OTHER_CARD.
        table("catalog_cards", PortfolioWindowFixture.CARD_COLUMNS, 3) { PortfolioWindowFixture.card(listOf(0, 2, 1)[it]) }
        table("collection_entries", PortfolioWindowFixture.ENTRY_COLUMNS, 4) { PortfolioWindowFixture.entry(it, unsafe) }
        table("raw_price_snapshots", PortfolioWindowFixture.PRICE_COLUMNS, PortfolioWindowFixture.COUNT + 3) { PortfolioWindowFixture.price(it, generationB) }
        sql.query("SELECT key,value FROM app_metadata").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("portfolio-window-owned-fixture", c.getString(0)); assertEquals(name, c.getString(1)); assertFalse(c.moveToNext())
        }
        assertNoForeignKeyErrors(sql)
    }

    private fun assertPortfolioWindowUnchangedColumns(before: Map<String, TableHash>, after: Map<String, TableHash>, unsafe: Boolean) {
        assertEquals(before.keys, after.keys)
        before.forEach { (table, old) ->
            val next = after.getValue(table)
            assertEquals(old.rows, next.rows); assertEquals(old.columns.keys, next.columns.keys)
            val changed = when (table) {
                "raw_price_snapshots" -> setOf("trendValueMinor", "holoTrendValueMinor")
                "collection_entries" -> if (unsafe) setOf("quantity") else emptySet()
                else -> emptySet()
            }
            old.columns.forEach { (column, hash) -> if (column !in changed) assertEquals("$table.$column", hash, next.columns.getValue(column)) }
        }
    }

    private fun assertPortfolioWindowDeltas(rows: List<com.dominik.cardvault.data.local.OwnedPortfolioDeltaProjection>, generationB: Boolean) {
        assertEquals(PortfolioWindowFixture.COUNT + 1, rows.size)
        assertEquals(com.dominik.cardvault.data.local.OwnedPortfolioDeltaProjection(0, 1, null, 0L), rows.first())
        var previous = java.math.BigInteger.ZERO
        repeat(PortfolioWindowFixture.COUNT) { i ->
            val current = java.math.BigInteger.valueOf(if (generationB) 8_000L + 21L * i else 800L + 8L * i)
            val delta = current.subtract(previous)
            check(delta >= java.math.BigInteger.valueOf(Long.MIN_VALUE) && delta <= java.math.BigInteger.valueOf(Long.MAX_VALUE))
            assertEquals(com.dominik.cardvault.data.local.OwnedPortfolioDeltaProjection(1, 1, AT + i * 60_000L,
                delta.toLong()), rows[i + 1]) // BigInteger.longValueExact is API31; retain API26 compatibility.
            previous = current
        }
    }

    private class PortfolioWindowRead(val label: String, private val pause: Boolean) {
        val cancellation = android.os.CancellationSignal()
        val ready = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val claimed = java.util.concurrent.atomic.AtomicBoolean()
        val pausedAt = AtomicLong()
        val refillStartedAt = AtomicLong()
        private val boundaryClaimed = java.util.concurrent.atomic.AtomicBoolean()
        private var rows = 0
        private val windows = mutableListOf<Triple<Int, Int, Int>>()
        fun beforeMove(cursor: android.database.AbstractWindowedCursor) {
            if (!pause || cursor.position < 0 || !cursor.hasWindow()) return
            val next = cursor.position + 1
            val window = cursor.window
            if (next < cursor.count && next >= window.startPosition + window.numRows && boundaryClaimed.compareAndSet(false, true)) {
                pausedAt.set(System.nanoTime()); ready.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Finite cursor barrier expired" }
                refillStartedAt.set(System.nanoTime())
            }
        }
        fun afterMove(cursor: android.database.AbstractWindowedCursor, db: SupportSQLiteDatabase) {
            check(db.inTransaction()) { "Aggregate cursor escaped the generated Room transaction" }
            check(cursor.columnNames.toList() == listOf("rowKind", "isSafe", "effectiveAtEpochMillis", "deltaValueMinor"))
            repeat(4) { column -> check(cursor.getType(column) == if (column == 2 && rows == 0) Cursor.FIELD_TYPE_NULL else Cursor.FIELD_TYPE_INTEGER) }
            check(cursor.position == rows)
            val w = cursor.window
            if (windows.lastOrNull()?.first != w.startPosition) {
                check(windows.size < 64) { "Unexpectedly many real cursor windows" }
                windows += Triple(w.startPosition, w.numRows, cursor.position)
            }
            rows++
        }
        fun assertComplete() {
            check(claimed.get()); assertEquals(PortfolioWindowFixture.COUNT + 1, rows)
            check(windows.size >= 2 && windows.any { it.first > 0 }) { "Actual multi-window coverage was not reached" }
            if (pause) check(pausedAt.get() > 0L && refillStartedAt.get() > pausedAt.get())
        }
        fun json() = JSONObject().put("label", label).put("rows", rows).put("pausedAtNanos", pausedAt.get())
            .put("refillStartedAtNanos", refillStartedAt.get()).put("windows", JSONArray().apply {
                windows.forEach { put(JSONObject().put("start", it.first).put("rows", it.second).put("observedAtPosition", it.third)) }
            })
    }

    private class PortfolioWindowAudit {
        val active = java.util.concurrent.atomic.AtomicReference<PortfolioWindowRead?>()
        val actualSql = java.util.concurrent.atomic.AtomicReference<String?>()
        val callbackCount = AtomicLong()
        val cursorCount = AtomicLong()
        val legacyCalls = AtomicLong()
        private val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        fun arm(probe: PortfolioWindowRead) { check(active.compareAndSet(null, probe)) }
        fun disarm(probe: PortfolioWindowRead) { check(active.compareAndSet(probe, null)) }
        fun isAggregate(sql: String) = sql.replace(Regex("\\s+"), " ").trim().startsWith("WITH owner_rows AS (")
        private fun capture(sql: String, bindings: List<Any?>) {
            check(bindings.size == 4 && bindings[0] in setOf(OWNER, OTHER_OWNER, "window-owner-empty") &&
                bindings.drop(1) == listOf(SOURCE, SOURCE, SOURCE)) { "Aggregate bindings differ" }
            check(sql.count { it == '?' } == 4)
            synchronized(actualSql) { if (actualSql.get() == null) actualSql.set(sql) else check(actualSql.get() == sql) }
        }
        fun callback(sql: String, bindings: List<Any?>) {
            try {
                if (isAggregate(sql)) { capture(sql, bindings); callbackCount.incrementAndGet() }
                if (sql.replace(Regex("\\s+"), " ").trim().startsWith("SELECT p.cardId, p.effectiveAtEpochMillis, p.trendValueMinor, p.holoTrendValueMinor FROM raw_price_snapshots p WHERE p.source = ?")) {
                    check(bindings == listOf(SOURCE, OWNER)); legacyCalls.incrementAndGet()
                }
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }
        fun inspect(query: androidx.sqlite.db.SupportSQLiteQuery): PortfolioWindowRead? {
            val bindings = sortedMapOf<Int, Any?>()
            query.bindTo(object : androidx.sqlite.db.SupportSQLiteProgram {
                override fun bindNull(index: Int) { check(!bindings.containsKey(index)); bindings[index] = null }
                override fun bindLong(index: Int, value: Long) { check(!bindings.containsKey(index)); bindings[index] = value }
                override fun bindDouble(index: Int, value: Double) { check(!bindings.containsKey(index)); bindings[index] = value }
                override fun bindString(index: Int, value: String) { check(!bindings.containsKey(index)); bindings[index] = value }
                override fun bindBlob(index: Int, value: ByteArray) { check(!bindings.containsKey(index)); bindings[index] = value.copyOf() }
                override fun clearBindings() { bindings.clear() }
                override fun close() = Unit
            })
            check(bindings.keys == setOf(1, 2, 3, 4)); capture(query.sql, bindings.values.toList())
            cursorCount.incrementAndGet()
            return active.get()?.also { check(bindings[1] == OWNER); check(it.claimed.compareAndSet(false, true)) }
        }
        fun checkHealthy() {
            failure.get()?.let { throw AssertionError("SQL callback audit failed", it) }
            check(callbackCount.get() == cursorCount.get() && callbackCount.get() > 0L)
        }
    }

    private class PortfolioWindowFactory(private val audit: PortfolioWindowAudit) : androidx.sqlite.db.SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration): androidx.sqlite.db.SupportSQLiteOpenHelper {
            val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
            fun wrap(db: SupportSQLiteDatabase): SupportSQLiteDatabase = object : SupportSQLiteDatabase by db {
                override fun query(query: androidx.sqlite.db.SupportSQLiteQuery): Cursor {
                    if (!audit.isAggregate(query.sql)) return db.query(query)
                    val probe = audit.inspect(query)
                    // Same query/bindings/engine. A never-cancelled token changes no successful
                    // result; it only lets failure cleanup cancel a real native reader.
                    val cursor = if (probe == null) db.query(query) else db.query(query, probe.cancellation)
                    if (probe == null) return cursor
                    try {
                        var unwrapped = cursor
                        repeat(8) { if (unwrapped is android.database.CursorWrapper) unwrapped = (unwrapped as android.database.CursorWrapper).wrappedCursor }
                        val windowed = unwrapped as? android.database.AbstractWindowedCursor
                            ?: error("Not a real framework windowed cursor")
                        return object : android.database.CursorWrapper(cursor) {
                            override fun moveToNext(): Boolean {
                                probe.beforeMove(windowed)
                                return super.moveToNext().also { if (it) probe.afterMove(windowed, db) }
                            }
                        }
                    } catch (t: Throwable) { cursor.close(); throw t }
                }
            }
            return object : androidx.sqlite.db.SupportSQLiteOpenHelper by helper {
                override val writableDatabase: SupportSQLiteDatabase get() = wrap(helper.writableDatabase)
                override val readableDatabase: SupportSQLiteDatabase get() = wrap(helper.readableDatabase)
            }
        }
    }

    private class PortfolioWindowWriter(private val file: File, private val name: String, private val unsafe: Boolean) {
        val attempt = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val committedAt = AtomicLong()
        private val afterBeginAt = AtomicLong()
        private val attemptAt = AtomicLong()
        private val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        private val started = java.util.concurrent.atomic.AtomicBoolean()
        private val thread = Thread({
            try {
                check(file.name == name && file.isFile)
                android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE or android.database.sqlite.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { db ->
                    db.rawQuery("SELECT value FROM app_metadata WHERE key=?", arrayOf("portfolio-window-owned-fixture")).use { c ->
                        check(c.moveToFirst() && c.getString(0) == name && !c.moveToNext())
                    }
                    check(!cancelled.get())
                    attemptAt.set(System.nanoTime()); attempt.countDown()
                    db.beginTransactionNonExclusive()
                    afterBeginAt.set(System.nanoTime())
                    try {
                        check(!cancelled.get())
                        db.compileStatement("UPDATE raw_price_snapshots SET trendValueMinor=1000+3*((effectiveAtEpochMillis-?)/60000), holoTrendValueMinor=2000+5*((effectiveAtEpochMillis-?)/60000) WHERE cardId=? AND source=?").use { statement ->
                            statement.bindLong(1, AT); statement.bindLong(2, AT)
                            statement.bindString(3, PortfolioWindowFixture.CARD); statement.bindString(4, SOURCE)
                            check(statement.executeUpdateDelete() == PortfolioWindowFixture.COUNT)
                        }
                        if (unsafe) db.compileStatement("UPDATE collection_entries SET quantity=1000001 WHERE id=1 AND ownerUid=? AND syncId='window-entry-0' AND cardId=? AND quantity=2").use { statement ->
                            statement.bindString(1, OWNER); statement.bindString(2, PortfolioWindowFixture.CARD)
                            check(statement.executeUpdateDelete() == 1)
                        }
                        check(!cancelled.get())
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                    committedAt.set(System.nanoTime())
                }
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
            finally { done.countDown() }
        }, "portfolio-window-owned-writer").apply { isDaemon = true }
        val isAlive: Boolean get() = started.get() && thread.isAlive
        fun start() { check(started.compareAndSet(false, true)); thread.start() }
        fun joinBounded() {
            if (!started.get()) return
            thread.join(15_000L)
            check(!thread.isAlive) { "Native writer did not terminate; retain its isolated fixture" }
        }
        fun rethrow() { failure.get()?.let { throw AssertionError("Independent fixture writer failed", it) } }
        fun json() = JSONObject().put("attemptAtNanos", attemptAt.get()).put("afterBeginAtNanos", afterBeginAt.get())
            .put("committedAtNanos", committedAt.get()).put("terminated", !isAlive).put("error", failure.get()?.toString() ?: JSONObject.NULL)
    }

    private fun config(): Config {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in localRoomBench; pass release27RoomScale=true", args.getString("release27RoomScale") == "true")
        assertTrue("Never run this benchmark on the physical Xiaomi",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("emulator"))
        val sizes = (args.getString("release27RoomSizes") ?: "1000,5000,20000")
            .split(',').map { it.trim().toInt() }.distinct()
        require(sizes.isNotEmpty() && sizes.all { it in setOf(1_000, 5_000, 20_000) })
        val repeats = (args.getString("release27RoomRepeats") ?: "3").toInt()
        require(repeats in 3..10) { "Use at least three repetitions (maximum ten)" }
        val label = args.getString("release27RoomLabel") ?: "baseline"
        require(label.matches(Regex("[A-Za-z0-9_-]{1,48}")))
        return Config(sizes, repeats, label)
    }

    private fun report(config: Config, kind: String) = JSONObject()
        .put("scope", "localRoomBench; synthetic local Room data; NOT FirebaseSync, network, UI, scanner or real-device latency")
        .put("kind", kind).put("label", config.label).put("status", "RUNNING")
        .put("fixtureVersion", 1).put("sizes", JSONArray(config.sizes)).put("repeats", config.repeats)
        .put("fixture", "N owner-A entries plus 3 owner-B sentinels; 5% owner-A tombstones; 2 price points/card; one large private binder/owner")
        .put("readSampling", "sample 0 first after seed/hash, NOT cold disk; 1..repeats warm resubscriptions; seed/hash/assertions outside read timings")
        .put("memoryScope", "whole-process snapshots, no forced GC, not peak or retained-memory proof")
        .put("queryCounterScope", "actual Room QueryCallback invocations; includes Room bookkeeping, excludes assertions/hash SQL; no remote request counts")
        .put("passCriteria", "exact values, owner isolation, tombstone filtering, unchanged field hashes, valid schema/foreign keys; no time SLA")
        .put("api", Build.VERSION.SDK_INT).put("emulatorModel", Build.MODEL)
        .put("wallClockRecordedAt", System.currentTimeMillis()).put("samples", JSONArray()).put("integrity", JSONArray())

    private suspend fun <T> measured(report: JSONObject, operation: String, size: Int, sample: Int,
        name: String, counter: SqlCounter?, block: suspend () -> T): T {
        val memoryBefore = memory()
        counter?.reset()
        val cpu = Process.getElapsedCpuTime()
        val start = System.nanoTime()
        val value = withTimeout(180_000L) { block() }
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        val cpuMillis = Process.getElapsedCpuTime() - cpu
        // Capture counters before the report/DB-size queries, and memory while the result is held.
        val sqlCount = counter?.total?.get()
        val selects = counter?.selects?.get()
        val item = JSONObject().put("operation", operation).put("size", size).put("sample", sample)
            .put("elapsedMs", elapsed).put("processCpuMs", cpuMillis)
            .put("roomSqlCallbacks", sqlCount ?: JSONObject.NULL).put("roomSelectCallbacks", selects ?: JSONObject.NULL)
            .put("memoryBefore", memoryBefore).put("memoryAfter", memory()).put("databaseBytes", databaseBytes(name))
        report.getJSONArray("samples").put(item)
        Log.i(TAG, "localRoomBench $operation size=$size sample=$sample elapsedMs=$elapsed sql=$sqlCount")
        return value
    }

    /** Deterministic generated records only. Product IDs/names here are synthetic, not card data. */
    private fun seed(db: SupportSQLiteDatabase, size: Int) {
        db.beginTransaction()
        try {
            FixtureWriter(db).use { out ->
                for (owner in listOf(OWNER, OTHER_OWNER)) {
                    for (isDeleted in listOf(false, true)) {
                        val binder = if (isDeleted) "$BINDER-deleted" else BINDER
                        out.insert("private_binders", "id,ownerUid,name,description,coverStyle,minimumPageCount,createdAtEpochMillis,updatedAtEpochMillis,lastOpenedAtEpochMillis,syncState,remoteRevision,deletedAtEpochMillis",
                            binder, owner, "Synthetic binder", "fixture only", "DOMIDEX", (size + 8) / 9, AT, AT + 10, AT + 20, "PENDING", 7L, if (isDeleted) AT + 30 else null)
                        out.insert("private_binder_sync_outbox", "entryKey,mutationId,ownerUid,recordType,recordId,operation,expectedRemoteRevision,updatedAtEpochMillis,createdAtEpochMillis,lastError",
                            "$owner:BINDER:$binder", "fixture-binder-$owner-$isDeleted", owner, "BINDER", binder, if (isDeleted) "DELETE" else "UPSERT", 7L, AT + 10, AT, null)
                    }
                }
                repeat(size / SET_SIZE + 1) { set ->
                    out.insert("binders", "id,setKey,name,heroAsset,createdAtEpochMillis,updatedAtEpochMillis",
                        "set-$set", "POKEMON:fixture-$set", "Synthetic set $set", null, AT, AT + set)
                }
                repeat(size + OTHER_ROWS) { i ->
                    val owner = if (i < size) OWNER else OTHER_OWNER
                    val id = i + 1L
                    val card = "fixture-card-$i"
                    val syncId = "fixture-entry-$i"
                    val removed = i < size && deleted(i)
                    val tombstone = if (removed) AT + 30 else null
                    val status = if (i < size) state(i) else "CLEAN"
                    val product = 90_000_000L + i
                    val set = i / SET_SIZE
                    val reverse = i % 7 == 0
                    out.insert("catalog_cards", "id,tcg,setId,setName,name,collectorNumber,language,finish,variant,createdAtEpochMillis,externalSource,externalProductId,imageUrl,rarity,effectOverride",
                        card, "POKEMON", "fixture-$set", "Synthetic set $set", "Synthetic card $i", "${i % SET_SIZE + 1}/$SET_SIZE", "GERMAN", if (reverse) "Reverse Holo" else "Normal", if (reverse) "Reverse Holo" else "Standard", AT, SOURCE, product, null, "UNKNOWN", null)
                    out.insert("collection_entries", "id,cardId,quantity,condition,acquisitionPriceMinor,acquisitionCurrency,acquisitionDateEpochDay,notes,frontPhotoUri,backPhotoUri,createdAtEpochMillis,updatedAtEpochMillis,externalProductSource,externalProductId,binderId,ownerUid,syncId,syncState,remoteRevision,deletedAtEpochMillis,finishOverride,variantOverride,effectOverride,sourceSetId,sourceCardId",
                        id, card, quantity(i), "Near Mint", 50L + i, "EUR", 20_000L + i, "Synthetic $i; \u00e4|\u03bb", null, null, AT, AT + i, SOURCE, product, "set-$set", owner, syncId, status, (i % 9).toLong(), tombstone, null, null, null, "fixture-$set", "fixture-$set-${i % SET_SIZE + 1}")
                    if (status != "CLEAN") out.insert("collection_sync_outbox", "entryKey,mutationId,ownerUid,syncId,operation,expectedRemoteRevision,state,createdAtEpochMillis,lastError",
                        "$owner:$syncId", "fixture-mutation-$i", owner, syncId, if (removed) "DELETE" else "UPSERT", (i % 9).toLong(), status, AT + i, if (status == "PENDING") null else "synthetic-$status")
                    out.insert("private_binder_placements", "id,ownerUid,binderId,collectionEntryId,slotPosition,holoEffectId,holoIntensity,createdAtEpochMillis,updatedAtEpochMillis,syncState,remoteRevision,deletedAtEpochMillis",
                        "placement-$i", owner, BINDER, id, i.toLong(), if (i % 2 == 0) "rainbow" else null, if (i % 2 == 0) .35 else 0.0, AT, AT + i, "PENDING", (i % 5).toLong(), tombstone)
                    out.insert("private_binder_sync_outbox", "entryKey,mutationId,ownerUid,recordType,recordId,operation,expectedRemoteRevision,updatedAtEpochMillis,createdAtEpochMillis,lastError",
                        "$owner:PLACEMENT:placement-$i", "fixture-placement-mutation-$i", owner, "PLACEMENT", "placement-$i", if (removed) "DELETE" else "UPSERT", (i % 5).toLong(), AT + i, AT + i + 1, null)
                    out.insert("collection_layout", "collectionEntryId,ownerUid,visibleInSetBinder,updatedAtEpochMillis,syncState,remoteRevision",
                        id, owner, if (i % 10 == 0) 0 else 1, AT + i, "CLEAN", 3L)
                    repeat(2) { point ->
                        val trend = 100L + i % 9_000 + point
                        out.insert("raw_price_snapshots", "id,cardId,source,currency,lowValueMinor,averageValueMinor,trendValueMinor,marketValueMinor,average1DayValueMinor,average7DayValueMinor,average30DayValueMinor,holoAverageValueMinor,holoLowValueMinor,holoTrendValueMinor,fetchedAtEpochMillis,effectiveAtEpochMillis",
                            i * 2L + point + 1L, card, SOURCE, "EUR", trend - 20, trend - 10, trend, trend, trend - 1, trend - 2, trend - 3, trend + 45, trend + 40, trend + 50, AT + (point + 1) * 1_000, AT + (point + 1) * 1_000)
                    }
                }
                out.insert("app_metadata", "key,value", "synthetic-benchmark-fixture", "version=1;size=$size")
                out.insert("catalog_sync_state", "source,status,lastAttemptAtEpochMillis,lastSuccessAtEpochMillis,dataCreatedAt,catalogProductCount,priceRecordCount,errorMessage",
                    SOURCE, "SUCCESS", AT, AT, "synthetic", size + OTHER_ROWS, (size + OTHER_ROWS) * 2, null)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun assertSeed(db: SupportSQLiteDatabase, size: Int) {
        assertEquals(size + OTHER_ROWS.toLong(), scalar(db, "SELECT COUNT(*) FROM collection_entries"))
        assertEquals(size / 20L, scalar(db, "SELECT COUNT(*) FROM collection_entries WHERE deletedAtEpochMillis IS NOT NULL"))
        assertEquals(size / 20L, scalar(db, "SELECT COUNT(*) FROM private_binder_placements WHERE deletedAtEpochMillis IS NOT NULL"))
        assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM private_binders WHERE deletedAtEpochMillis IS NOT NULL"))
        for (status in listOf("PENDING", "CONFLICT", "ERROR")) assertTrue(scalar(db, "SELECT COUNT(*) FROM collection_sync_outbox WHERE state='$status'") > 0)
        assertEquals(size + OTHER_ROWS + 4L, scalar(db, "SELECT COUNT(*) FROM private_binder_sync_outbox"))
        assertNoForeignKeyErrors(db)
    }

    private fun assertNoForeignKeyErrors(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { assertEquals("Synthetic graph must have valid foreign keys", 0, it.count) }
    }

    /** Column-wise typed SHA-256; NULL differs from empty text/zero; every existing field included. */
    private fun hashes(db: SupportSQLiteDatabase, tables: List<String>? = null): Map<String, TableHash> {
        val names = tables ?: db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return names.associateWith { table ->
            require(table.matches(Regex("[A-Za-z0-9_]+")))
            val columns = mutableListOf<String>()
            val primary = sortedMapOf<Int, String>()
            db.query("PRAGMA table_info(`$table`)").use { c -> while (c.moveToNext()) {
                columns += c.getString(1)
                if (c.getInt(5) > 0) primary[c.getInt(5)] = c.getString(1)
            } }
            val digests = columns.map { MessageDigest.getInstance("SHA-256") }
            val order = (primary.values.toList().ifEmpty { columns }).joinToString(",") { "`$it`" }
            var rows = 0L
            db.query("SELECT * FROM `$table` ORDER BY $order").use { c -> while (c.moveToNext()) {
                rows++
                columns.indices.forEach { index ->
                    val type = c.getType(index)
                    val bytes = when (type) {
                        Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                        Cursor.FIELD_TYPE_INTEGER -> ByteBuffer.allocate(8).putLong(c.getLong(index)).array()
                        Cursor.FIELD_TYPE_FLOAT -> ByteBuffer.allocate(8).putDouble(c.getDouble(index)).array()
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(index)
                        else -> c.getString(index).toByteArray(Charsets.UTF_8)
                    }
                    digests[index].update(type.toByte())
                    digests[index].update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                    digests[index].update(bytes)
                }
            } }
            TableHash(rows, columns.zip(digests.map { it.digest().joinToString("") { b -> "%02x".format(b) } }).toMap())
        }
    }

    private fun hashJson(hashes: Map<String, TableHash>) = JSONObject().apply {
        hashes.forEach { (table, hash) -> put(table, JSONObject().put("rows", hash.rows).put("columnsSha256", JSONObject(hash.columns))) }
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Long = db.query(sql).use { assertTrue(it.moveToFirst()); it.getLong(0) }
    private fun freshName(kind: String) = "release27-room-scale-$kind-${UUID.randomUUID()}.db"
    private fun databaseBytes(name: String): JSONObject {
        val file = context.getDatabasePath(name)
        return JSONObject().put("main", file.length()).put("wal", File(file.path + "-wal").length())
            .put("shm", File(file.path + "-shm").length())
    }
    private fun memory(): JSONObject {
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        return JSONObject().put("totalPssKiB", info.totalPss).put("dalvikPssKiB", info.dalvikPss)
            .put("nativePssKiB", info.nativePss).put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
    }
    private fun save(report: JSONObject, config: Config) {
        val file = File(context.cacheDir, "release27-room-scale-${config.label}-${report.getString("kind")}.json")
        file.writeText(report.toString(2))
        Log.i(TAG, "localRoomBench report=${file.absolutePath} status=${report.getString("status")}")
    }

    private class FixtureWriter(private val db: SupportSQLiteDatabase) : Closeable {
        private val statements = mutableMapOf<String, SupportSQLiteStatement>()
        fun insert(table: String, columns: String, vararg values: Any?) {
            val sql = "INSERT INTO $table ($columns) VALUES (${values.joinToString(",") { "?" }})"
            val statement = statements.getOrPut(sql) { db.compileStatement(sql) }
            statement.clearBindings()
            values.forEachIndexed { i, value -> when (value) {
                null -> statement.bindNull(i + 1)
                is Float -> statement.bindDouble(i + 1, value.toDouble())
                is Double -> statement.bindDouble(i + 1, value)
                is Number -> statement.bindLong(i + 1, value.toLong())
                else -> statement.bindString(i + 1, value.toString())
            } }
            statement.executeInsert()
        }
        override fun close() = statements.values.forEach { it.close() }
    }
    private class SqlCounter {
        val total = AtomicLong()
        val selects = AtomicLong()
        fun reset() { total.set(0); selects.set(0) }
        fun record(sql: String) { total.incrementAndGet(); if (sql.trimStart().startsWith("SELECT", true)) selects.incrementAndGet() }
    }
    private data class Config(val sizes: List<Int>, val repeats: Int, val label: String)
    private data class TableHash(val rows: Long, val columns: Map<String, String>)
    companion object {
        private const val TAG = "DomiDexRoomScale"
        private const val OWNER = "fixture-owner-a"
        private const val OTHER_OWNER = "fixture-owner-b"
        private const val BINDER = "fixture-private-binder"
        private const val SOURCE = "CARDMARKET_PUBLIC_DOWNLOAD"
        private const val SET_SIZE = 100
        private const val OTHER_ROWS = 3
        private const val AT = 1_700_000_000_000L
        private fun deleted(i: Int) = i % 20 == 0
        private fun quantity(i: Int) = 1 + i % 3
        private fun state(i: Int) = if (deleted(i)) "PENDING" else arrayOf("CLEAN", "PENDING", "CONFLICT", "ERROR")[i % 4]
        private fun valueMinor(i: Int) = 101L + i % 9_000 + if (i % 7 == 0) 50L else 0L
    }
}
