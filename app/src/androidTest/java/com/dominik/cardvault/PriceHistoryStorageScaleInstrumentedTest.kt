package com.dominik.cardvault

import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.util.AtomicFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.PriceHistoryRetention
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant

/**
 * Opt-in isolated-package storage phases, never an ordinary application/phone test.
 * Actual schema, tuples and indexes; no padding, sparse-file growth or network. Each phase
 * persists only its own database and receipts. No phase deletes files or claims a release gate.
 * Process restart means a new idempotent retention pass, NOT a serialized production cursor.
 */
@RunWith(AndroidJUnit4::class)
class PriceHistoryStorageScaleInstrumentedTest {
    @Test fun pilotRealPriceRows() = runBlocking { phase("pilot") { report -> seed(500_000, report) } }
    @Test fun seedThirteenGiB() = runBlocking { phase("seed") { report -> seed(null, report) } }
    @Test fun verifySeededDatabase() = runBlocking { phase("verify") { report ->
        requireSeed(); check(!state.optBoolean("retentionStarted")); verifyIntegrity(report)
        check(scalar("SELECT COUNT(*) FROM raw_price_snapshots") == state.getLong("generatedRows") + 100)
        verifySentinels(); report.put("retainedHash", retainedHash())
        check(report.getString("retainedHash") == state.getString("retainedHash"))
        state.put("verified", true); saveState()
    } }
    @Test fun pruneFirstBatches() = runBlocking { phase("prune") { report ->
        requireSeed(); check(state.optBoolean("verified") && !state.optBoolean("retentionComplete"))
        state.put("retentionStarted", true); saveState()
        val retention = PriceHistoryRetention(room)
        var cursor = retention.beginPass(CUTOFF, collectionHistoryOnly = true)
        var deleted = 0L
        repeat(32) {
            check(!cursor.complete); deadline()
            val batch = retention.pruneBatch(cursor, 1024)
            check(batch.examinedRows in 1..1024 && batch.deletedRows in 0..batch.examinedRows)
            deleted += batch.deletedRows; cursor = batch.next
        }
        check(!cursor.complete && deleted == 98L)
        report.put("batches", 32).put("deletedRowsThisPhase", deleted).put("retentionComplete", false)
        state.put("partialPruneFinished", true); saveState()
    } }
    @Test fun resumeByFreshIdempotentPass() = runBlocking { phase("resume") { report ->
        requireSeed(); check(state.optBoolean("partialPruneFinished"))
        val retention = PriceHistoryRetention(room)
        var cursor = retention.beginPass(CUTOFF, collectionHistoryOnly = true)
        var batches = 0L
        var deleted = 0L
        while (!cursor.complete) {
            deadline()
            val batch = retention.pruneBatch(cursor, 1024)
            check(batch.examinedRows in 0..1024 && batch.deletedRows in 0..batch.examinedRows)
            cursor = batch.next; deleted += batch.deletedRows; batches++
            check(batches <= 100_000L)
            if (batches % 256L == 0L) { checkpoint(); saveProgress("resume", batches) }
        }
        verifyRetained(report); verifyIntegrity(report)
        state.put("retentionComplete", true); saveState()
        report.put("batches", batches).put("deletedRowsThisPhase", deleted)
            .put("restartMode", "FRESH_PASS_RESCAN_NOT_SERIALIZED_CURSOR")
    } }
    @Test fun reclaimOwnedDatabase() = runBlocking { phase("reclaim") { report ->
        requireSeed(); check(state.optBoolean("retentionComplete")); verifyRetained(report)
        checkpoint()
        val before = file.length()
        check(StatFs(file.parent!!).availableBytes >= Math.addExact(Math.multiplyExact(before, 2), 2 * GIB)) {
            "Insufficient conservative free-space reserve for VACUUM"
        }
        // Only this newly-created disposable database. No product singleton/other connection.
        deadline(); sql.execSQL("VACUUM"); deadline(); checkpoint()
        check(file.length() < before && scalar("PRAGMA freelist_count") == 0L)
        verifyIntegrity(report); verifyRetained(report)
        report.put("beforeReclaimMainBytes", before).put("reclaimed", true)
        state.put("reclaimed", true); saveState()
    } }

    private suspend fun phase(name: String, block: suspend Fixture.(JSONObject) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        val run = checkNotNull(args.getString("storageProofRun"))
        val invocation = checkNotNull(args.getString("storageInvocation"))
        check(run.matches(Regex("[a-f0-9]{32}")) && invocation.matches(Regex("[a-f0-9]{32}")))
        check(args.getString("storagePhase") == name)
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.dominik.cardvault.storageproof.r$run")
        check(Build.HARDWARE == "ranchu" && Build.VERSION.SDK_INT == 36)
        val data = context.dataDir.canonicalFile
        val files = File(data, "files"); check(context.filesDir.canonicalFile == files)
        val reportFile = File(files, "storage-$invocation.json")
        check(!reportFile.exists() && !File(reportFile.path + ".bak").exists())
        val report = JSONObject().put("schema", 1).put("buildRun", run).put("invocation", invocation)
            .put("phase", name).put("status", "RUNNING").put("api", Build.VERSION.SDK_INT)
            .put("collectionHistoryOnly", true)
            .put("scope", "SYNTHETIC_OWNED_ROOM_PHASE_NOT_RELEASE_GATE")
        var fixture: Fixture? = null
        try {
            fixture = Fixture(context, run, name)
            fixture.block(report)
            fixture.checkpoint()
            report.put("database", fixture.statistics())
                .put("generatedRows", fixture.state.getLong("generatedRows"))
                .put("seedUsedBytes", fixture.state.optLong("seedUsedBytes"))
                .put("target13GiBReached", name != "pilot" && fixture.state.optLong("seedUsedBytes") >= TARGET)
            if (name !in setOf("seed", "pilot")) fixture.verifyProtected()
            report.put("nonPriceTablesUnchanged", true).put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failure", failure.javaClass.name + ": " + failure.message?.take(400))
            throw failure
        } finally {
            try { fixture?.room?.close(); report.put("closed", true) }
            catch (failure: Throwable) { report.put("closed", false).put("status", "FAIL"); throw failure }
            finally { atomicWrite(reportFile, report) }
        }
    }

    private class Fixture(context: Context, run: String, val phase: String) {
        val file: File
        val room: CardVaultDatabase
        val sql: SupportSQLiteDatabase
        val stateFile: File
        var state: JSONObject
        private val started = SystemClock.elapsedRealtime()
        private val maxMillis = when (phase) { "seed" -> 28_500_000L; "pilot", "prune" -> 1_700_000L; else -> 14_000_000L }

        init {
            val kind = if (phase == "pilot") "pilot" else "scale"
            val root = context.dataDir.canonicalFile
            file = File(File(root, "databases"), "storage-$kind-$run.db")
            check(context.getDatabasePath(file.name).canonicalFile == file)
            stateFile = File(File(root, "files"), "storage-$kind-$run.state.json")
            check(stateFile.canonicalFile == stateFile)
            if (stateFile.exists() || File(stateFile.path + ".bak").exists()) {
                state = JSONObject(AtomicFile(stateFile).openRead().bufferedReader().use { it.readText() })
                check(state.getString("run") == run && state.getString("database") == file.name)
                check(file.isFile && file.canonicalFile == file)
            } else {
                check(phase in setOf("pilot", "seed"))
                check(listOf("", "-wal", "-shm", "-journal").none { File(file.path + it).exists() })
                state = JSONObject().put("run", run).put("database", file.name).put("generatedRows", 0)
                // The exclusive package is the outer ownership boundary. Marker precedes opening.
                atomicWrite(stateFile, state)
            }
            room = Room.databaseBuilder(context, CardVaultDatabase::class.java, file.name)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
            try {
                sql = room.openHelper.writableDatabase
                check(sql.version == 13)
                check(StatFs(file.parent!!).totalBytes >= 44 * GIB) { "Dedicated 48G AVD required" }
                if (!state.optBoolean("initialized")) {
                    check(scalar("SELECT COUNT(*) FROM catalog_cards") == 0L)
                    initialize()
                    state.put("initialized", true); saveState()
                }
            } catch (failure: Throwable) { room.close(); throw failure }
        }

        fun deadline() { check(SystemClock.elapsedRealtime() - started < maxMillis) { "Bounded phase deadline reached" } }
        fun scalar(query: String): Long = sql.query(query).use { c -> check(c.moveToFirst()); c.getLong(0) }
        fun saveState() = atomicWrite(stateFile, state)
        fun saveProgress(operation: String, count: Long) {
            deadline()
            atomicWrite(File(stateFile.path + ".progress.json"), JSONObject().put("operation", operation)
                .put("count", count).put("elapsedMillis", SystemClock.elapsedRealtime() - started).put("database", statistics()))
        }
        fun statistics(): JSONObject {
            val pages = scalar("PRAGMA page_count"); val free = scalar("PRAGMA freelist_count")
            val size = scalar("PRAGMA page_size")
            return JSONObject().put("mainBytes", file.length()).put("walBytes", File(file.path + "-wal").length())
                .put("pageSize", size).put("pageCount", pages).put("freePages", free)
                .put("usedBytes", (pages - free) * size).put("dataAvailableBytes", StatFs(file.parent!!).availableBytes)
        }
        fun checkpoint() {
            sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c -> check(c.moveToFirst()); check(c.getInt(0) == 0) }
        }
        private fun transaction(block: () -> Unit) {
            sql.beginTransaction()
            try { block(); sql.setTransactionSuccessful() } finally { sql.endTransaction() }
        }
        private fun numbers() = transaction {
            // TEMP belongs to one connection. Build/read it on the pinned primary writer;
            // every later INSERT...SELECT also runs on that writer inside transaction().
            sql.execSQL("CREATE TEMP TABLE IF NOT EXISTS storage_numbers(n INTEGER PRIMARY KEY)")
            if (scalar("SELECT COUNT(*) FROM storage_numbers") == 0L) {
                sql.compileStatement("INSERT INTO storage_numbers VALUES(?)").use { statement ->
                    repeat(BATCH) { statement.bindLong(1, it.toLong()); statement.executeInsert() }
                }
            }
            check(scalar("SELECT COUNT(*) FROM storage_numbers") == BATCH.toLong())
        }
        private fun insert(table: String, columns: String, vararg values: Any?) {
            sql.execSQL("INSERT INTO $table ($columns) VALUES (${values.joinToString(",") { "?" }})", values)
        }
        private fun initialize() {
            numbers()
            transaction {
                for (base in 0 until PRODUCTS step BATCH) sql.execSQL("""
                    INSERT INTO catalog_cards(id,tcg,setId,setName,name,collectorNumber,language,finish,variant,
                        createdAtEpochMillis,externalSource,externalProductId,imageUrl,rarity,effectOverride)
                    SELECT printf('storage-p%05d',n+?), 'POKEMON','storage-set','Synthetic storage set',
                        printf('Synthetic card %d',n+?), printf('%05d',n+?), 'GERMAN','Normal','Standard',
                        ?,?,90000000+n+?,NULL,'UNKNOWN',NULL FROM storage_numbers
                """, arrayOf<Any?>(base, base, base, NOW, SOURCE, base))
                insert("catalog_cards", "id,tcg,setId,setName,name,collectorNumber,language,finish,variant,createdAtEpochMillis,externalSource,externalProductId",
                    "storage-old-only", "POKEMON", "storage-set", "Synthetic", "Old only", "old", "GERMAN", "Normal", "Standard", NOW, SOURCE, 91000000L)
                insert("binders", "id,setKey,name,createdAtEpochMillis,updatedAtEpochMillis", "storage-set", "POKEMON:storage", "Synthetic", NOW, NOW)
                for (i in 0..5) {
                    val owner = listOf("__domidex_guest__", "storage-owner-a", "storage-owner-b")[i / 2]
                    val tombstone = if (i % 2 == 1) NOW else null
                    val status = listOf("PENDING", "CONFLICT", "ERROR")[i / 2]
                    insert("collection_entries", "id,cardId,quantity,condition,notes,createdAtEpochMillis,updatedAtEpochMillis,binderId,ownerUid,syncId,syncState,remoteRevision,deletedAtEpochMillis,externalProductSource,externalProductId",
                        i + 1, "storage-p%05d".format(i), i + 1, "Near Mint", "Synthetic ä|λ", NOW, NOW, "storage-set", owner, "entry-$i", status, 7, tombstone, SOURCE, 90000000L + i)
                    insert("collection_sync_outbox", "entryKey,mutationId,ownerUid,syncId,operation,expectedRemoteRevision,state,createdAtEpochMillis,lastError",
                        "entry-$i", "mutation-$i", owner, "entry-$i", if (tombstone == null) "UPSERT" else "DELETE", 7, status, NOW, "Synthetic")
                    insert("private_binders", "id,ownerUid,name,description,coverStyle,minimumPageCount,createdAtEpochMillis,updatedAtEpochMillis,syncState,remoteRevision,deletedAtEpochMillis",
                        "binder-$i", owner, "Synthetic", "Test", "DOMIDEX", 1, NOW, NOW, status, 7, tombstone)
                    insert("private_binder_placements", "id,ownerUid,binderId,collectionEntryId,slotPosition,createdAtEpochMillis,updatedAtEpochMillis,syncState,remoteRevision,deletedAtEpochMillis",
                        "placement-$i", owner, "binder-$i", i + 1, 0, NOW, NOW, status, 7, tombstone)
                    insert("private_binder_sync_outbox", "entryKey,mutationId,ownerUid,recordType,recordId,operation,expectedRemoteRevision,updatedAtEpochMillis,createdAtEpochMillis,lastError",
                        "binder-$i", "private-mutation-$i", owner, "BINDER", "binder-$i", if (tombstone == null) "UPSERT" else "DELETE", 7, NOW, NOW, "Synthetic")
                    insert("collection_layout", "collectionEntryId,ownerUid,visibleInSetBinder,updatedAtEpochMillis,syncState,remoteRevision", i + 1, owner, i % 2, NOW, status, 7)
                    insert("collection_price_selections", "collectionEntryId,ownerUid,entrySyncId,cleared,updatedAtEpochMillis,syncState,remoteRevision", i + 1, owner, "entry-$i", 1, NOW, status, 7)
                    insert("grading_analyses", "collectionEntryId,status,createdAtEpochMillis", i + 1, "SYNTHETIC", NOW)
                }
                insert("graded_market_records", "cardId,gradingCompany,grade,recordType,valueMinor,currency,source,recordedAtEpochMillis", "storage-p00000", "SYNTHETIC", 9.0, "SYNTHETIC", 100, "EUR", "SYNTHETIC", NOW)
                insert("app_metadata", "key,value", "storage-proof", state.getString("run"))
                insert("catalog_sync_state", "source,status,catalogProductCount,priceRecordCount", SOURCE, "SYNTHETIC", PRODUCTS + 1, 0)
                insert("raw_price_snapshots", "id,cardId,source,currency,trendValueMinor,fetchedAtEpochMillis,effectiveAtEpochMillis", -1, "storage-p00000", "STORAGE_OTHER_SOURCE", "EUR", 321, NOW, NOW - 3000 * DAY)
                insert("raw_price_snapshots", "id,cardId,source,currency,trendValueMinor,fetchedAtEpochMillis,effectiveAtEpochMillis", -2, "storage-old-only", SOURCE, "EUR", 654, NOW, NOW - 3000 * DAY)
                // Early-PK obsolete observations make the deliberately short first prune commit
                // real deletions before a new process demonstrates safe restart by rescan.
                for (id in -100..-3) insert("raw_price_snapshots", "id,cardId,source,currency,trendValueMinor,fetchedAtEpochMillis,effectiveAtEpochMillis",
                    id, "storage-p00000", SOURCE, "EUR", 100, NOW, NOW - (4000L - id) * DAY)
            }
        }

        fun seed(pilotRows: Long?, report: JSONObject) {
            check(!state.optBoolean("retentionStarted"))
            val protected = protectedHash(includeRawSequence = false)
            var rows = scalar("SELECT COALESCE(MAX(id),0) FROM raw_price_snapshots").coerceAtLeast(0)
            check(rows % BATCH == 0L && scalar("SELECT COUNT(*) FROM raw_price_snapshots") == rows + 100)
            numbers()
            while (true) {
                deadline()
                if (rows % PRODUCTS == 0L) {
                    checkpoint()
                    val used = statistics().getLong("usedBytes")
                    if (pilotRows != null && rows == pilotRows ||
                        pilotRows == null && used >= TARGET && rows >= RETAINED_DAYS * PRODUCTS) break
                }
                check(rows + BATCH <= MAX_ROWS && (pilotRows == null || rows + BATCH <= pilotRows))
                check(StatFs(file.parent!!).availableBytes >= 2 * GIB) { "Preserve free-space reserve" }
                transaction {
                    sql.execSQL("""
                        INSERT INTO raw_price_snapshots(id,cardId,source,currency,lowValueMinor,averageValueMinor,
                            trendValueMinor,marketValueMinor,average1DayValueMinor,average7DayValueMinor,
                            average30DayValueMinor,holoAverageValueMinor,holoLowValueMinor,holoTrendValueMinor,
                            fetchedAtEpochMillis,effectiveAtEpochMillis)
                        SELECT n+?+1, printf('storage-p%05d',(n+?)%50000), ?, 'EUR',
                            80+(n+?)%9000,90+(n+?)%9000,
                            CASE WHEN (n+?)%17=0 THEN NULL WHEN (n+?)%19=0 THEN -1 ELSE 100+(n+?)%9000 END,
                            100+(n+?)%9000,99+(n+?)%9000,98+(n+?)%9000,97+(n+?)%9000,
                            145+(n+?)%9000,140+(n+?)%9000,
                            CASE WHEN (n+?)%23=0 THEN 0 ELSE 150+(n+?)%9000 END,
                            ?,? - ((n+?)/50000)*86400000 FROM storage_numbers ORDER BY n
                    """, arrayOf<Any?>(rows, rows, SOURCE, rows, rows, rows, rows, rows, rows, rows, rows, rows,
                        rows, rows, rows, rows, NOW, NOW, rows))
                }
                rows += BATCH
                if (rows % 500_000L == 0L) saveProgress("seed", rows)
            }
            check(protected == protectedHash(includeRawSequence = false))
            state.put("generatedRows", rows).put("seedUsedBytes", statistics().getLong("usedBytes"))
                .put("protectedHash", protectedHash()).put("retainedHash", retainedHash(rows))
            saveState(); report.put("seedElapsedMillis", SystemClock.elapsedRealtime() - started)
        }

        fun requireSeed() {
            check(state.optLong("seedUsedBytes") >= TARGET && state.getLong("generatedRows") % PRODUCTS == 0L)
            check(state.getLong("generatedRows") >= RETAINED_DAYS * PRODUCTS)
            verifyProtected()
        }
        fun verifyProtected() { check(protectedHash() == state.getString("protectedHash")) }
        fun verifySentinels() {
            check(scalar("SELECT COUNT(*) FROM raw_price_snapshots WHERE id IN(-2,-1)") == 2L)
            check(scalar("SELECT trendValueMinor FROM raw_price_snapshots WHERE id=-1") == 321L)
            check(scalar("SELECT trendValueMinor FROM raw_price_snapshots WHERE id=-2") == 654L)
        }
        fun verifyRetained(report: JSONObject) {
            // Independent fixture oracle, not a second execution of retention SQL: every
            // product keeps its day0 row; exactly six local product IDs keep days1..366.
            // All three owners, unsynced states and the three tombstones count as protected.
            val expected = PRODUCTS + (RETAINED_DAYS - 1) * PROTECTED_PRODUCTS + 2
            check(expected == 52_198L)
            check(scalar("SELECT COUNT(*) FROM raw_price_snapshots") == expected)
            check(scalar("SELECT COUNT(*) FROM raw_price_snapshots WHERE NOT (${retainedPredicate()})") == 0L)
            verifySentinels(); check(retainedHash() == state.getString("retainedHash")); verifyProtected()
            report.put("retentionComplete", true).put("retainedRowsMatch", true).put("retainedHashMatch", true)
                .put("retainedRows", expected)
        }
        fun verifyIntegrity(report: JSONObject) {
            deadline()
            sql.query("PRAGMA integrity_check").use { c -> check(c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext()) }
            sql.query("PRAGMA foreign_key_check").use { c -> check(!c.moveToFirst()) }
            report.put("integrity", "ok").put("foreignKeyErrors", 0)
        }
        private fun retainedPredicate(rows: Long = state.getLong("generatedRows")): String =
            "id IN (-2,-1) OR (id>0 AND (id<=$PRODUCTS OR " +
                "((id-1)%$PRODUCTS<$PROTECTED_PRODUCTS AND id<=${minOf(rows, RETAINED_DAYS * PRODUCTS)})))"
        fun retainedHash(rows: Long = state.getLong("generatedRows")): String = hashQueries(listOf(
            "SELECT * FROM raw_price_snapshots WHERE ${retainedPredicate(rows)} ORDER BY id"))
        private fun protectedHash(includeRawSequence: Boolean = true): String {
            val queries = mutableListOf<String>()
            sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('raw_price_snapshots','room_master_table','android_metadata') ORDER BY name").use { c ->
                while (c.moveToNext()) {
                    val table = c.getString(0); check(table.matches(Regex("[a-z_]+")))
                    val columns = mutableListOf<String>(); val primary = sortedMapOf<Int, String>()
                    sql.query("PRAGMA table_info(`$table`)").use { info -> while (info.moveToNext()) {
                        columns += info.getString(1); if (info.getInt(5) > 0) primary[info.getInt(5)] = info.getString(1)
                    } }
                    val order = primary.values.toList().ifEmpty { columns }.joinToString(",") { "`$it`" }
                    val where = if (table == "sqlite_sequence" && !includeRawSequence) " WHERE name!='raw_price_snapshots'" else ""
                    queries += "SELECT * FROM `$table`$where ORDER BY $order"
                }
            }
            check(queries.size == 14) { "All thirteen non-price entities plus sqlite_sequence required" }
            return hashQueries(queries)
        }
        private fun hashQueries(queries: List<String>): String {
            val hash = MessageDigest.getInstance("SHA-256")
            val number = ByteBuffer.allocate(8)
            fun length(value: Long) { number.clear(); number.putLong(value); hash.update(number.array()) }
            for (query in queries) {
                val queryBytes = query.toByteArray(); length(queryBytes.size.toLong()); hash.update(queryBytes)
                sql.query(query).use { c ->
                    for (column in c.columnNames) { val bytes = column.toByteArray(); length(bytes.size.toLong()); hash.update(bytes) }
                    var count = 0L
                    while (c.moveToNext()) {
                        if (++count % 4096L == 0L) deadline()
                        for (index in 0 until c.columnCount) {
                            val type = c.getType(index); hash.update(type.toByte())
                            when (type) {
                                Cursor.FIELD_TYPE_NULL -> length(0)
                                Cursor.FIELD_TYPE_INTEGER -> { length(8); length(c.getLong(index)) }
                                Cursor.FIELD_TYPE_FLOAT -> { length(8); length(java.lang.Double.doubleToRawLongBits(c.getDouble(index))) }
                                else -> { val bytes = if (type == Cursor.FIELD_TYPE_BLOB) c.getBlob(index) else c.getString(index).toByteArray()
                                    length(bytes.size.toLong()); hash.update(bytes) }
                            }
                        }
                    }
                    length(count)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }

    companion object {
        private const val GIB = 1024L * 1024 * 1024
        private const val TARGET = 13 * GIB
        private const val PRODUCTS = 50_000
        private const val PROTECTED_PRODUCTS = 6
        private const val BATCH = 5_000
        private const val MAX_ROWS = 100_000_000L
        private const val DAY = 86_400_000L
        // Newest-first rows make the retained oracle an independently known PK prefix.
        private val NOW = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
        private val CUTOFF = Instant.parse("2025-10-04T12:00:00Z").toEpochMilli()
        private const val RETAINED_DAYS = 367L // age0..365 inclusive, plus last state before the window.
        private const val SOURCE = "CARDMARKET_PUBLIC_DOWNLOAD"
        private fun atomicWrite(file: File, value: JSONObject) {
            check(file.canonicalFile == file)
            val atomic = AtomicFile(file); val stream = atomic.startWrite()
            try { stream.write(value.toString().toByteArray()); atomic.finishWrite(stream) }
            catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        }
    }
}
