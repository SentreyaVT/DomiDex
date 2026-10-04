package com.dominik.cardvault.data

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.PriceHistoryMaintenance
import com.dominik.cardvault.data.local.PriceHistoryMaintenanceCodec
import com.dominik.cardvault.data.sync.CardmarketHistoryReference
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Small disposable offline fixture, NOT a 13GiB migration or production-reclamation claim.
 * 100002 real tuples/index entries; no padding or sparse-file growth. VACUUM is reachable only
 * for this newly owned exact file after its Room instance has closed. The 120s limit is checked
 * around SQLite calls; a native statement is not claimed to be forcibly preemptible.
 */
@RunWith(AndroidJUnit4::class)
class PriceHistoryStorageReclaimInstrumentedTest {
    private lateinit var room: CardVaultDatabase
    private lateinit var root: File
    private lateinit var file: File
    private var roomOpen = false
    private var ownsRoot = false
    private var autoVacuumMode: Long? = null
    private val owner = UUID.randomUUID().toString()
    private var started = 0L
    private val scans = Collections.synchronizedList(mutableListOf<Pair<String, List<Any?>>>())
    private val sql get() = room.openHelper.writableDatabase

    @Before fun setUp() {
        started = SystemClock.elapsedRealtime()
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir.canonicalFile, "history-reclaim-$owner")
        check(!root.exists() && root.mkdir())
        ownsRoot = true
        file = File(root, "fixture.db")
        check(!file.exists() && file.canonicalFile.parentFile == root)
    }

    private fun initializeFixture(explicitNone: Boolean) {
        check(ownsRoot && !file.exists() && file.canonicalFile.parentFile == root)
        if (explicitNone) {
            // Configure only this fresh, still table-free UUID fixture. Persist its empty
            // schema-version0 header so Room's next open observes the chosen file format.
            SQLiteDatabase.openDatabase(file.path, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                DatabaseErrorHandler { throw IllegalStateException("Owned empty fixture corrupt; no recovery deletion permitted") },
            ).use { empty ->
                empty.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)); assertFalse(it.moveToNext())
                }
                empty.execSQL("PRAGMA auto_vacuum=NONE")
                empty.execSQL("PRAGMA user_version=0")
                empty.rawQuery("PRAGMA auto_vacuum", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)); assertFalse(it.moveToNext())
                }
                assertEquals(0, empty.version)
            }
            autoVacuumMode = 0L
        }
        // The default case deliberately lets real Room create the file from nothing.
        openRoom()
        sql.execSQL("INSERT INTO app_metadata (`key`,value) VALUES ('reclaim-fixture-owner',?)", arrayOf(owner))
    }

    private fun openRoom() {
        check(!roomOpen)
        room = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            CardVaultDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback(RoomDatabase.QueryCallback { query, bindings ->
                val normalized = query.trim().replace(Regex("\\s+"), " ")
                if (normalized.startsWith("SELECT id FROM raw_price_snapshots WHERE ")) {
                    scans += normalized to bindings.toList()
                }
            }, Executor { it.run() }).build()
        roomOpen = true
        check(sql.version == 13)
        val actual = scalar("PRAGMA auto_vacuum")
        assertTrue("Unknown Room auto_vacuum=$actual", actual in 0L..2L)
        autoVacuumMode?.let { assertEquals("Room reopen must preserve auto_vacuum", it, actual) }
        autoVacuumMode = actual
    }

    private fun closeRoom() {
        if (roomOpen) { room.close(); roomOpen = false }
    }

    @After fun tearDown() {
        closeRoom()
        if (ownsRoot) {
            val parent = ApplicationProvider.getApplicationContext<Context>().cacheDir.canonicalFile
            check(root.parentFile == parent && root.name == "history-reclaim-$owner")
            val files = root.listFiles() ?: error("Owned fixture directory unavailable")
            val expected = setOf("fixture.db", "fixture.db-wal", "fixture.db-shm", "fixture.db-journal", "front.bin", "back.bin")
            check(files.all { it.name in expected && it.isFile && it.canonicalFile.parentFile == root })
            files.forEach { check(it.delete()) }
            check(root.delete())
        }
    }

    @Test fun realRowsDurableResumeAndOfflineVacuumReleaseOwnedFileWithoutChangingRetainedData(): Unit = runProof(explicitNone = true)

    @Test fun platformDefaultRetentionReleasesRealSpaceWithoutChangingRetainedData(): Unit = runProof(explicitNone = false)

    private fun runProof(explicitNone: Boolean): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(DEADLINE_MS) {
            initializeFixture(explicitNone)
            val mode = requireNotNull(autoVacuumMode)
            if (explicitNone) assertEquals(0L, mode)
            assertEquals(365L * DAY, NOW - CUTOFF)
            assertEquals(CUTOFF, effectiveAt(634))
            assertEquals(CUTOFF - DAY, effectiveAt(633))
            seedBusinessState()
            seedPrices()
            assertEquals(100_002L, scalar("SELECT COUNT(*) FROM raw_price_snapshots"))
            assertEquals(100L, scalar("SELECT COUNT(*) FROM catalog_cards"))
            integrity()
            checkpoint()
            val seeded = space()
            val seededPhysical = physical()
            assertTrue("Actual occupied SQLite pages, not padding", seeded.usedBytes > 8 * MIB)
            val expected = expectedRetained()
            assertEquals(6 * (366 + 1) + 94 + 2, expected.count)
            assertEquals(2298, expected.count)
            val protected = protectedHashes()
            val photos = photoHashes()
            armTicket()

            var examined = 0
            var deleted = 0
            var batches = 0
            suspend fun batch(): PriceHistoryMaintenance.Batch {
                deadline()
                val result = PriceHistoryMaintenance(room).runBatch(1024)
                assertTrue(result.examinedRows in 1..1024)
                assertTrue(result.deletedRows in 0..result.examinedRows)
                examined += result.examinedRows; deleted += result.deletedRows; batches++
                assertTrue("Fixed fixture pass bound", batches <= 98)
                deadline(); fixtureBudget()
                return result
            }
            repeat(3) { assertEquals(PriceHistoryMaintenance.Status.MORE, batch().status) }
            val cursorRaw = requireNotNull(room.cardVaultDao().metadata(PriceHistoryMaintenanceCodec.CURSOR_KEY))
            val cursor = requireNotNull(PriceHistoryMaintenanceCodec.decodeState(cursorRaw))
            assertEquals(3072L, cursor.lastExaminedId)
            assertEquals(100_002L, cursor.upperId)
            checkpoint(); closeRoom(); deadline(); openRoom()
            assertEquals(owner, room.cardVaultDao().metadata("reclaim-fixture-owner"))
            assertEquals(cursorRaw, room.cardVaultDao().metadata(PriceHistoryMaintenanceCodec.CURSOR_KEY))
            synchronized(scans) { scans.clear() }
            assertEquals(PriceHistoryMaintenance.Status.MORE, batch().status)
            val resumedQuery = synchronized(scans) { scans.toList() }
            assertEquals(1, resumedQuery.size)
            assertEquals("SELECT id FROM raw_price_snapshots WHERE id > ? AND id <= ? ORDER BY id LIMIT ?", resumedQuery.single().first)
            assertEquals(listOf(3072L, 100_002L, 1024L), resumedQuery.single().second.map { (it as Number).toLong() })
            assertEquals(4096L, requireNotNull(PriceHistoryMaintenanceCodec.decodeState(
                room.cardVaultDao().metadata(PriceHistoryMaintenanceCodec.CURSOR_KEY))).lastExaminedId)
            var result: PriceHistoryMaintenance.Batch
            do { result = batch() } while (result.status == PriceHistoryMaintenance.Status.MORE)
            assertEquals(PriceHistoryMaintenance.Status.PASS_COMPLETE, result.status)
            assertFalse(result.pendingAnotherPass)
            assertEquals(98, batches)
            assertEquals(100_002, examined)
            assertEquals(97_704, deleted)
            assertNull(room.cardVaultDao().metadata(PriceHistoryMaintenanceCodec.CURSOR_KEY))
            assertNull(room.cardVaultDao().metadata(PriceHistoryMaintenanceCodec.PENDING_KEY))
            assertEquals(expected, retainedDigest())
            assertEquals(protected, protectedHashes())
            assertEquals(photos, photoHashes())
            integrity(); checkpoint()
            val pruned = space()
            val prunedPhysical = physical()
            val observed = "auto_vacuum=$mode explicitNone=$explicitNone seeded=$seeded pruned=$pruned " +
                "seededPhysical=$seededPhysical prunedPhysical=$prunedPhysical"
            Log.i("DomiDexReclaimProof", "post_prune $observed")
            val before: Pair<Long, Long>
            val after: Pair<Long, Long>
            val explicitVacuum = mode != 1L
            if (explicitVacuum) {
                // Preserve the original NONE assertions; INCREMENTAL also retains free
                // pages until explicit reclamation. No automatic mode change is made.
                assertTrue("DELETE must leave real reclaimable pages: $observed", pruned.freePages > 0)
                assertEquals("DELETE must retain file length before explicit reclaim: $observed", seeded.mainBytes, pruned.mainBytes)
                // No production singleton/worker/other connection exists for this UUID file.
                // Budget includes a conservative additional two-file-copy reserve for VACUUM.
                check(Math.addExact(Math.multiplyExact(pruned.mainBytes, 3L), 32 * MIB) <= MAX_FIXTURE_BYTES)
                check(StatFs(root.path).availableBytes >= Math.addExact(Math.multiplyExact(pruned.mainBytes, 2L), 32 * MIB))
                closeRoom()
                check(!roomOpen && file.canonicalFile == file && file.parentFile == root)
                before = physical()
                deadline()
                SQLiteDatabase.openDatabase(file.path, null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                    DatabaseErrorHandler { throw IllegalStateException("Owned reclaim fixture corrupt; no recovery deletion permitted") },
                ).use { offline ->
                    assertEquals(13, offline.version)
                    offline.rawQuery("PRAGMA auto_vacuum", null).use {
                        assertTrue(it.moveToFirst()); assertEquals(mode, it.getLong(0)); assertFalse(it.moveToNext())
                    }
                    offline.rawQuery("SELECT value FROM app_metadata WHERE `key`='reclaim-fixture-owner'", null).use {
                        assertTrue(it.moveToFirst()); assertEquals(owner, it.getString(0)); assertFalse(it.moveToNext())
                    }
                    check(!offline.inTransaction())
                    offline.execSQL("VACUUM")
                    deadline()
                    offline.rawQuery("PRAGMA integrity_check", null).use {
                        assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)); assertFalse(it.moveToNext())
                    }
                }
                deadline(); fixtureBudget()
                after = physical()
            } else {
                assertFalse("Explicit NONE must never take FULL branch", explicitNone)
                assertEquals("FULL must reclaim its free pages: $observed", 0L, pruned.freePages)
                assertTrue("FULL must reduce real page count: $observed", pruned.pages < seeded.pages)
                before = seededPhysical
                after = prunedPhysical
                // FULL evidence is DELETE+checkpoint only, never an extra VACUUM.
                closeRoom()
            }
            assertTrue("SQLite file length must actually decrease: $observed before=$before after=$after", after.first < before.first)
            assertTrue("Guest filesystem allocated bytes must actually decrease: $observed before=$before after=$after", after.second < before.second)
            openRoom()
            integrity(); checkpoint()
            val reclaimed = space()
            val reclaimedPhysical = physical()
            assertEquals(0L, reclaimed.freePages)
            if (explicitVacuum) assertTrue(reclaimed.pages < pruned.pages)
            else assertEquals("FULL shrink must survive reopen", pruned, reclaimed)
            assertEquals(expected, retainedDigest())
            assertEquals(protected, protectedHashes())
            assertEquals(photos, photoHashes())
            assertEquals(owner, room.cardVaultDao().metadata("reclaim-fixture-owner"))
            deadline()
            Log.i("DomiDexReclaimProof", JSONObject().put("schema", 1).put("status", "PASS")
                .put("scope", "SMALL_ISOLATED_FIXTURE_NOT_13GIB_OR_PRODUCTION_RECLAIM")
                .put("fixtureMode", if (explicitNone) "EXPLICIT_NONE" else "ACTUAL_ROOM_DEFAULT")
                .put("autoVacuum", mode).put("forceNone", explicitNone).put("explicitVacuum", explicitVacuum)
                .put("packageName", ApplicationProvider.getApplicationContext<Context>().packageName).put("fixtureId", owner)
                .put("reclaimMechanism", if (explicitVacuum) "OFFLINE_OWNED_FILE_VACUUM" else "FULL_DELETE_AND_CHECKPOINT")
                .put("generatedRows", 100002).put("retainedRows", expected.count).put("retainedSha256", expected.sha256)
                .put("batches", batches).put("examinedRows", examined).put("deletedRows", deleted)
                .put("seedMainBytes", seeded.mainBytes).put("beforeReclaimBytes", before.first).put("afterReclaimBytes", after.first)
                .put("seedAllocatedBytes", seededPhysical.second).put("prunedMainBytes", prunedPhysical.first)
                .put("prunedAllocatedBytes", prunedPhysical.second).put("reclaimedMainBytes", reclaimedPhysical.first)
                .put("reclaimedAllocatedBytes", reclaimedPhysical.second).put("seedPages", seeded.pages).put("postPrunePages", pruned.pages)
                .put("beforeAllocatedBytes", before.second).put("afterAllocatedBytes", after.second)
                .put("beforeFreePages", pruned.freePages).put("afterFreePages", reclaimed.freePages)
                .put("protectedTables", protected.size).put("elapsedMillis", SystemClock.elapsedRealtime() - started).toString())
        }
    }

    private suspend fun seedBusinessState() = room.withTransaction {
        for (name in listOf("front.bin", "back.bin")) {
            val photo = File(root, name)
            check(photo.createNewFile())
            photo.writeBytes(ByteArray(64) { (it + name.length).toByte() })
        }
        for (product in 0 until 100) insert("catalog_cards",
            "id,tcg,setId,setName,name,collectorNumber,language,finish,variant,createdAtEpochMillis,externalSource,externalProductId",
            card(product), "POKEMON", "fixture", "Synthetic", "Synthetic $product", "$product", "GERMAN", "Normal", "Standard", NOW, SOURCE, 9000000L + product)
        insert("binders", "id,setKey,name,createdAtEpochMillis,updatedAtEpochMillis", "fixture", "fixture", "Synthetic", NOW, NOW)
        for (i in 0..5) {
            val uid = listOf("__domidex_guest__", "fixture-owner-a", "fixture-owner-b")[i / 2]
            val tombstone = if (i % 2 == 1) NOW else null
            val status = listOf("PENDING", "CONFLICT", "ERROR")[i / 2]
            insert("collection_entries", "id,cardId,quantity,condition,notes,frontPhotoUri,backPhotoUri,createdAtEpochMillis,updatedAtEpochMillis,binderId,ownerUid,syncId,syncState,remoteRevision,deletedAtEpochMillis,externalProductSource,externalProductId",
                i + 1, card(i), i + 1, "Near Mint", "kept ä|λ", File(root, "front.bin").toURI().toString(), File(root, "back.bin").toURI().toString(), NOW, NOW, "fixture", uid, "entry-$i", status, 7, tombstone, SOURCE, 9000000L + i)
            insert("collection_sync_outbox", "entryKey,mutationId,ownerUid,syncId,operation,expectedRemoteRevision,state,createdAtEpochMillis,lastError",
                "entry-$i", "mutation-$i", uid, "entry-$i", "UPSERT", 7, status, NOW, "preserved")
            insert("private_binders", "id,ownerUid,name,description,coverStyle,minimumPageCount,createdAtEpochMillis,updatedAtEpochMillis,syncState,remoteRevision,deletedAtEpochMillis",
                "binder-$i", uid, "Synthetic", "Preserved", "DOMIDEX", 2, NOW, NOW, status, 7, tombstone)
            insert("private_binder_placements", "id,ownerUid,binderId,collectionEntryId,slotPosition,holoEffectId,holoIntensity,createdAtEpochMillis,updatedAtEpochMillis,syncState,remoteRevision,deletedAtEpochMillis",
                "placement-$i", uid, "binder-$i", i + 1, 17, "synthetic", 0.5, NOW, NOW, status, 7, tombstone)
            insert("private_binder_sync_outbox", "entryKey,mutationId,ownerUid,recordType,recordId,operation,expectedRemoteRevision,updatedAtEpochMillis,createdAtEpochMillis,lastError",
                "binder-$i", "private-$i", uid, "BINDER", "binder-$i", "UPSERT", 7, NOW, NOW, "preserved")
            insert("collection_layout", "collectionEntryId,ownerUid,visibleInSetBinder,updatedAtEpochMillis,syncState,remoteRevision", i + 1, uid, i % 2, NOW, status, 7)
            insert("collection_price_selections", "collectionEntryId,ownerUid,entrySyncId,cleared,productId,amountMinor,currency,updatedAtEpochMillis,syncState,remoteRevision",
                i + 1, uid, "entry-$i", i % 2, 9000000L + i, 321, "EUR", NOW, status, 7)
            insert("grading_analyses", "collectionEntryId,status,createdAtEpochMillis", i + 1, "SYNTHETIC", NOW)
        }
        insert("graded_market_records", "cardId,gradingCompany,grade,recordType,valueMinor,currency,source,recordedAtEpochMillis",
            card(0), "SYNTHETIC", 9.5, "SYNTHETIC", 123, "EUR", "OTHER", NOW)
        insert("catalog_sync_state", "source,status,catalogProductCount,priceRecordCount", SOURCE, "SYNTHETIC", 100, 100000)
        insert("app_metadata", "key,value", CardmarketHistoryReference.METADATA_KEY, NOW.toString())
    }

    private suspend fun seedPrices() {
        for (base in 0 until 100000 step 1000) {
            deadline()
            room.withTransaction {
                // A bounded 10x10x10 real-row generator; all PK/FK/unique indexes stay enabled.
                sql.execSQL("""
                    WITH digits(n) AS (VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9)),
                    seed(i) AS (SELECT ? + a.n + 10*b.n + 100*c.n FROM digits a CROSS JOIN digits b CROSS JOIN digits c),
                    source(i,p,d) AS (SELECT i,i%100,i/100 FROM seed)
                    INSERT INTO raw_price_snapshots(id,cardId,source,currency,lowValueMinor,averageValueMinor,
                        trendValueMinor,marketValueMinor,average1DayValueMinor,average7DayValueMinor,average30DayValueMinor,
                        holoAverageValueMinor,holoLowValueMinor,holoTrendValueMinor,fetchedAtEpochMillis,effectiveAtEpochMillis)
                    SELECT i+1,printf('reclaim-%03d',p),?,'EUR',CASE WHEN i%7=0 THEN NULL ELSE i%10000 END,
                        -(i%1000),CASE WHEN i%11=0 THEN NULL WHEN i%13=0 THEN 0 ELSE i%80000 END,-i-1,
                        NULL,0,i+3,-i-5,CASE WHEN i%17=0 THEN NULL ELSE i+7 END,
                        CASE WHEN i%19=0 THEN NULL ELSE -(i%123) END,?+i,?+d*86400000 FROM source ORDER BY i
                """.trimIndent(), arrayOf<Any?>(base, SOURCE, NOW, OLDEST))
            }
            fixtureBudget()
        }
        room.withTransaction {
            for (id in 100001L..100002L) insert("raw_price_snapshots",
                "id,cardId,source,currency,lowValueMinor,averageValueMinor,trendValueMinor,marketValueMinor,fetchedAtEpochMillis,effectiveAtEpochMillis",
                id, card(if (id == 100001L) 0 else 99), "OTHER_SOURCE", "USD", -1, 0, null, 777, NOW, OLDEST - id)
        }
        deadline(); fixtureBudget()
    }

    private suspend fun armTicket() = room.withTransaction {
        val reference = requireNotNull(CardmarketHistoryReference.fromPriceGuideResponse(
            DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(NOW).atZone(ZoneOffset.UTC)), NOW))
        val ticket = PriceHistoryMaintenanceCodec.freshTicket(reference)
        assertEquals(CUTOFF, ticket.cutoffEpochMillis)
        insert("app_metadata", "key,value", PriceHistoryMaintenanceCodec.PENDING_KEY, PriceHistoryMaintenanceCodec.encodeTicket(ticket))
    }

    private fun insert(table: String, columns: String, vararg values: Any?) {
        sql.execSQL("INSERT INTO $table ($columns) VALUES (${values.joinToString(",") { "?" }})", values)
    }
    private suspend fun deadline() {
        currentCoroutineContext().ensureActive()
        check(SystemClock.elapsedRealtime() - started < DEADLINE_MS) { "Fixed 120s fixture deadline exceeded" }
    }
    private fun fixtureBudget() {
        val observed = listOf("", "-wal", "-shm", "-journal").sumOf { File(file.path + it).length() }
        check(observed <= MAX_FIXTURE_BYTES) { "Fixed 150MiB SQLite fixture budget exceeded" }
    }
    private fun scalar(query: String): Long = sql.query(query).use { check(it.moveToFirst()); it.getLong(0) }
    private fun checkpoint() {
        check(!sql.inTransaction())
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(0, it.getInt(1)); assertEquals(0, it.getInt(2)); assertFalse(it.moveToNext())
        }
    }
    private fun integrity() {
        sql.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)); assertFalse(it.moveToNext()) }
        sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
    private data class Space(val mainBytes: Long, val pages: Long, val freePages: Long, val usedBytes: Long)
    private fun space(): Space {
        val pages = scalar("PRAGMA page_count"); val free = scalar("PRAGMA freelist_count"); val size = scalar("PRAGMA page_size")
        assertEquals(pages * size, file.length())
        return Space(file.length(), pages, free, (pages - free) * size)
    }
    private fun physical(): Pair<Long, Long> {
        val stat = Os.stat(file.path)
        assertEquals(file.length(), stat.st_size)
        assertTrue(stat.st_blocks > 0)
        return stat.st_size to Math.multiplyExact(stat.st_blocks, 512L)
    }

    private data class Digest(val count: Int, val sha256: String)
    private fun retainedDigest(): Digest {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        sql.query("SELECT * FROM raw_price_snapshots ORDER BY id").use { cursor ->
            assertEquals(RAW_COLUMNS, cursor.columnNames.toSet())
            while (cursor.moveToNext()) { digest.update(typedRow(cursor)); count++ }
        }
        return Digest(count, hex(digest.digest()))
    }
    private fun expectedRetained(): Digest {
        // Independent fixture arithmetic, not a query/production-retention oracle.
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        for (day in 0 until 1000) for (product in 0 until 100) {
            if (!(product < 6 && day >= 633 || product >= 6 && day == 999)) continue
            val i = day * 100L + product
            val values = mapOf<String, Any?>(
                "id" to i + 1, "cardId" to card(product), "source" to SOURCE, "currency" to "EUR",
                "lowValueMinor" to if (i % 7 == 0L) null else i % 10000, "averageValueMinor" to -(i % 1000),
                "trendValueMinor" to if (i % 11 == 0L) null else if (i % 13 == 0L) 0L else i % 80000,
                "marketValueMinor" to -i - 1, "average1DayValueMinor" to null, "average7DayValueMinor" to 0L,
                "average30DayValueMinor" to i + 3, "holoAverageValueMinor" to -i - 5,
                "holoLowValueMinor" to if (i % 17 == 0L) null else i + 7,
                "holoTrendValueMinor" to if (i % 19 == 0L) null else -(i % 123),
                "fetchedAtEpochMillis" to NOW + i, "effectiveAtEpochMillis" to effectiveAt(day),
            )
            digest.update(typedExpected(values)); count++
        }
        for (id in 100001L..100002L) {
            val values = RAW_COLUMNS.associateWith<String, Any?> { null }.toMutableMap()
            values.putAll(mapOf("id" to id, "cardId" to card(if (id == 100001L) 0 else 99), "source" to "OTHER_SOURCE",
                "currency" to "USD", "lowValueMinor" to -1L, "averageValueMinor" to 0L, "trendValueMinor" to null,
                "marketValueMinor" to 777L, "fetchedAtEpochMillis" to NOW, "effectiveAtEpochMillis" to OLDEST - id))
            digest.update(typedExpected(values)); count++
        }
        return Digest(count, hex(digest.digest()))
    }

    private fun protectedHashes(): Map<String, String> {
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('raw_price_snapshots','android_metadata','room_master_table') ORDER BY name").use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        assertEquals(PROTECTED_TABLES, tables.toSet())
        return tables.associateWith { table ->
            val filter = if (table == "app_metadata") " WHERE `key` NOT IN ('${PriceHistoryMaintenanceCodec.PENDING_KEY}','${PriceHistoryMaintenanceCodec.CURSOR_KEY}')" else ""
            val rows = sql.query("SELECT * FROM `$table`$filter").use { cursor -> buildList {
                while (cursor.moveToNext()) add(hex(MessageDigest.getInstance("SHA-256").digest(typedRow(cursor))))
            } }
            assertTrue("Every protected table has nonempty real fixture data: $table", rows.isNotEmpty())
            // VACUUM may renumber implicit rowids. Preserve exact typed row multiset, not storage order.
            hex(MessageDigest.getInstance("SHA-256").digest(rows.sorted().joinToString("\n").toByteArray(Charsets.UTF_8)))
        }
    }
    private fun photoHashes() = listOf("front.bin", "back.bin").associateWith {
        val photo = File(root, it); check(photo.canonicalFile.parentFile == root)
        hex(MessageDigest.getInstance("SHA-256").digest(photo.readBytes()))
    }
    private fun typedRow(cursor: Cursor): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(cursor.columnCount)
            for (name in cursor.columnNames.sorted()) {
                val column = cursor.getColumnIndexOrThrow(name)
                out.writeUTF(name); val type = cursor.getType(column); out.writeInt(type)
                when (type) {
                    Cursor.FIELD_TYPE_NULL -> Unit
                    Cursor.FIELD_TYPE_INTEGER -> out.writeLong(cursor.getLong(column))
                    Cursor.FIELD_TYPE_FLOAT -> out.writeLong(java.lang.Double.doubleToRawLongBits(cursor.getDouble(column)))
                    Cursor.FIELD_TYPE_STRING -> bytes(out, cursor.getString(column).toByteArray(Charsets.UTF_8))
                    Cursor.FIELD_TYPE_BLOB -> bytes(out, cursor.getBlob(column))
                    else -> error("Unknown SQLite type")
                }
            }
        }
        return output.toByteArray()
    }
    private fun typedExpected(values: Map<String, Any?>): ByteArray {
        assertEquals(RAW_COLUMNS, values.keys)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(values.size)
            for ((name, value) in values.toSortedMap()) {
                out.writeUTF(name)
                when (value) {
                    null -> out.writeInt(Cursor.FIELD_TYPE_NULL)
                    is Long -> { out.writeInt(Cursor.FIELD_TYPE_INTEGER); out.writeLong(value) }
                    is String -> { out.writeInt(Cursor.FIELD_TYPE_STRING); bytes(out, value.toByteArray(Charsets.UTF_8)) }
                    else -> error("Unexpected independent oracle type")
                }
            }
        }
        return output.toByteArray()
    }
    private fun bytes(out: DataOutputStream, bytes: ByteArray) { out.writeInt(bytes.size); out.write(bytes) }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun card(product: Int) = "reclaim-${product.toString().padStart(3, '0')}"
    private fun effectiveAt(day: Int) = OLDEST + day * DAY
    companion object {
        private const val SOURCE = CardmarketSyncContract.SOURCE
        private const val MIB = 1024L * 1024
        private const val DAY = 86_400_000L
        private const val DEADLINE_MS = 120_000L
        private const val MAX_FIXTURE_BYTES = 150 * MIB
        private val NOW = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
        private val CUTOFF = Instant.parse("2025-10-04T12:00:00Z").toEpochMilli()
        private val OLDEST = NOW - 999 * DAY
        private val RAW_COLUMNS = setOf("id", "cardId", "source", "currency", "lowValueMinor", "averageValueMinor",
            "trendValueMinor", "marketValueMinor", "average1DayValueMinor", "average7DayValueMinor", "average30DayValueMinor",
            "holoAverageValueMinor", "holoLowValueMinor", "holoTrendValueMinor", "fetchedAtEpochMillis", "effectiveAtEpochMillis")
        private val PROTECTED_TABLES = setOf("catalog_cards", "binders", "collection_entries", "collection_sync_outbox",
            "private_binders", "private_binder_placements", "private_binder_sync_outbox", "collection_layout", "collection_price_selections",
            "graded_market_records", "grading_analyses", "app_metadata", "catalog_sync_state", "sqlite_sequence")
    }
}
