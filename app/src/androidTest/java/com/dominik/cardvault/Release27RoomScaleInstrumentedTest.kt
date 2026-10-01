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
 *   release27RoomScale=true                 (otherwise both tests SKIP)
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
