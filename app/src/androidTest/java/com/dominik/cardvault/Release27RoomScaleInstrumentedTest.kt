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
