package com.dominik.cardvault.data

import android.content.Context
import android.database.Cursor
import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.*
import com.dominik.cardvault.data.sync.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated file Room DB. No singleton, worker, network, clock mutation or production file. */
@RunWith(AndroidJUnit4::class)
class PriceHistoryMaintenanceInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private lateinit var root: File
    private lateinit var databaseFile: File
    private var ownsRoot = false
    private val fixtureId = UUID.randomUUID().toString()
    private val cancellation = AtomicReference<Pair<String, Job>?>()
    private val dao get() = database.cardVaultDao()
    private val reference = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
    private val cutoff = Instant.parse("2025-10-04T12:00:00Z").toEpochMilli()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir.canonicalFile, "history-maintenance-$fixtureId")
        check(!root.exists() && root.mkdir())
        ownsRoot = true
        databaseFile = File(root, "fixture.db")
        check(databaseFile.canonicalFile.parentFile == root && !databaseFile.exists())
        openDatabase()
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO app_metadata (`key`,value) VALUES ('maintenance-test-owner',?)", arrayOf(fixtureId))
    }

    private fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.databaseBuilder(context, CardVaultDatabase::class.java, databaseFile.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, bindings ->
                val normalized = sql.trim().replace(Regex("\\s+"), " ")
                val armed = cancellation.get()
                if (armed != null && normalized.startsWith(armed.first) &&
                    (armed.first.startsWith("WITH ") || bindings.contains(PriceHistoryMaintenanceCodec.CURSOR_KEY)) &&
                    cancellation.compareAndSet(armed, null)) armed.second.cancel()
            }, Executor { it.run() }).build()
        database.openHelper.writableDatabase // Create/open only our exact file, before test assertions.
    }

    @After fun tearDown() {
        if (::database.isInitialized) database.close()
        if (ownsRoot) {
            val parent = ApplicationProvider.getApplicationContext<Context>().cacheDir.canonicalFile
            check(root.parentFile == parent && root.name == "history-maintenance-$fixtureId")
            val files = root.listFiles() ?: error("Owned fixture directory unavailable")
            val names = setOf("fixture.db", "fixture.db-wal", "fixture.db-shm", "fixture.db-journal")
            check(files.all { it.name in names && it.isFile && it.canonicalFile.parentFile == root })
            files.forEach { check(it.delete()) }
            check(root.delete())
        }
    }

    @Test fun freshImportTicketCommitsWithPricesAndMissingClockNeverRearmsIt(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            importProduct(42, reference, verified(reference))
            val firstRaw = requireNotNull(dao.metadata(PENDING))
            val first = requireNotNull(PriceHistoryMaintenanceCodec.decodeTicket(firstRaw))
            assertEquals(reference, first.referenceEpochMillis)
            assertEquals(cutoff, first.cutoffEpochMillis)
            assertEquals("SUCCESS", dao.syncState(SOURCE)?.status)
            assertEquals(reference.toString(), dao.metadata(CardmarketHistoryReference.METADATA_KEY))
            assertEquals(1L, countPrices())
            importProduct(42, reference, null, 222)
            assertEquals(firstRaw, dao.metadata(PENDING))
            assertEquals(reference.toString(), dao.metadata(CardmarketHistoryReference.METADATA_KEY))
            assertEquals(222L, trends().single())
            importProduct(42, reference, verified(reference), 333)
            val second = requireNotNull(PriceHistoryMaintenanceCodec.decodeTicket(dao.metadata(PENDING)))
            assertNotEquals(first.id, second.id) // Same Date/source correction is still a distinct intent.
            assertEquals(first.referenceEpochMillis, second.referenceEpochMillis)
            assertEquals(first.cutoffEpochMillis, second.cutoffEpochMillis)
            assertNull(dao.metadata(STATE))
        }
    }

    @Test fun lateTicketFailureRollsBackPricesClockSuccessAndPreviousTicket(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            importProduct(42, reference, verified(reference))
            val before = allRows()
            val next = reference + 86_400_000L
            database.openHelper.writableDatabase.execSQL("""
                CREATE TEMP TRIGGER fail_new_maintenance_ticket AFTER INSERT ON main.app_metadata
                WHEN NEW.key='$PENDING'
                BEGIN SELECT CASE WHEN
                    (SELECT COUNT(*) FROM main.raw_price_snapshots)=2 AND
                    (SELECT value FROM main.app_metadata WHERE `key`='${CardmarketHistoryReference.METADATA_KEY}')='$next' AND
                    (SELECT status FROM main.catalog_sync_state WHERE source='$SOURCE')='SUCCESS'
                    THEN RAISE(ABORT,'TICKET_AFTER_PRICE_AND_CLOCK')
                    ELSE RAISE(ABORT,'TICKET_UNEXPECTED_ORDER') END; END;
            """.trimIndent())
            try {
                val failure = runCatching { importProduct(42, next, verified(next), 444) }.exceptionOrNull()
                assertFailure(failure, "TICKET_AFTER_PRICE_AND_CLOCK")
                assertEquals(before, allRows())
            } finally { database.openHelper.writableDatabase.execSQL("DROP TRIGGER temp.fail_new_maintenance_ticket") }
        }
    }

    @Test fun cursorWriteFailureRollsBackRealDeleteAndBothMetadataKeys(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedUnowned(4); arm()
            val before = allRows()
            database.openHelper.writableDatabase.execSQL("""
                CREATE TEMP TRIGGER fail_cursor_commit BEFORE INSERT ON main.app_metadata
                WHEN NEW.key='$STATE'
                BEGIN SELECT CASE WHEN (SELECT COUNT(*) FROM main.raw_price_snapshots)=3
                    THEN RAISE(ABORT,'CURSOR_AFTER_REAL_DELETE')
                    ELSE RAISE(ABORT,'CURSOR_NO_DELETE') END; END;
            """.trimIndent())
            try {
                assertFailure(runCatching { PriceHistoryMaintenance(database).runBatch(1) }.exceptionOrNull(), "CURSOR_AFTER_REAL_DELETE")
                assertEquals(before, allRows())
            } finally { database.openHelper.writableDatabase.execSQL("DROP TRIGGER temp.fail_cursor_commit") }
            assertEquals(1, PriceHistoryMaintenance(database).runBatch(1).deletedRows)
        }
    }

    @Test fun cancellationAtDeleteOrCursorSqlRollsBackAllChanges(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedUnowned(4); arm()
            val before = allRows()
            for (prefix in listOf("WITH protected_cards(cardId) AS (", "INSERT OR IGNORE INTO app_metadata")) {
                val child = launch {
                    cancellation.set(prefix to currentCoroutineContext()[Job]!!)
                    PriceHistoryMaintenance(database).runBatch(1)
                    error("Cancelled batch must not succeed")
                }
                child.join()
                assertTrue(child.isCancelled)
                assertNull(cancellation.get())
                assertEquals(before, allRows())
            }
            assertEquals(1, PriceHistoryMaintenance(database).runBatch(1).deletedRows)
        }
    }

    @Test fun outerRollbackDoesNotLeaveDeletionOrDurableProgressAheadOfCommit(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedUnowned(4); arm()
            val before = allRows()
            try {
                database.withTransaction {
                    assertEquals(1, PriceHistoryMaintenance(database).runBatch(1).deletedRows)
                    assertNotNull(dao.metadata(STATE))
                    throw ExpectedRollback()
                }
            } catch (_: ExpectedRollback) { }
            assertEquals(before, allRows())
            assertEquals(1, PriceHistoryMaintenance(database).runBatch(1).deletedRows)
        }
    }

    @Test fun fileDatabaseResumeCrossesLongProtectedPrefixWithoutRescanningIt(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            dao.insertCatalogCard(catalog("protected"))
            protect("protected")
            dao.insertRawPrices((0 until 4097).map { price("protected", cutoff + it, it.toLong()) })
            seedUnowned(4); arm()
            val protectedBefore = rows("SELECT * FROM raw_price_snapshots WHERE cardId='protected' ORDER BY id")
            val businessBefore = protectedTables()
            val first = PriceHistoryMaintenance(database).runBatch(1024)
            assertEquals(1024, first.examinedRows); assertEquals(0, first.deletedRows)
            assertEquals(1024L, state().lastExaminedId)
            val stateBeforeClose = dao.metadata(STATE)
            database.close()
            openDatabase()
            assertEquals(fixtureId, dao.metadata("maintenance-test-owner"))
            assertEquals(stateBeforeClose, dao.metadata(STATE))
            var examined = first.examinedRows
            var deleted = first.deletedRows
            val results = mutableListOf<PriceHistoryMaintenance.Batch>()
            repeat(4) { results += PriceHistoryMaintenance(database).runBatch(1024) }
            results.forEach { examined += it.examinedRows; deleted += it.deletedRows }
            assertEquals(listOf(1024, 1024, 1024, 5), results.map { it.examinedRows })
            assertEquals(4101, examined); assertEquals(3, deleted)
            assertEquals(PriceHistoryMaintenance.Status.PASS_COMPLETE, results.last().status)
            assertFalse(results.last().pendingAnotherPass)
            assertEquals(4098L, countPrices())
            assertEquals(protectedBefore, rows("SELECT * FROM raw_price_snapshots WHERE cardId='protected' ORDER BY id"))
            assertEquals(businessBefore, protectedTables())
            assertNull(dao.metadata(STATE)); assertNull(dao.metadata(PENDING))
        }
    }

    @Test fun twoCoordinatorsReadCommittedCursorInsteadOfDuplicatingAStaleBatch(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedUnowned(20); arm()
            val start = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) { start.await(); PriceHistoryMaintenance(database).runBatch(5) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { start.await(); PriceHistoryMaintenance(database).runBatch(5) }
            start.complete(Unit)
            val batches = listOf(first.await(), second.await())
            assertTrue(batches.all { it.status == PriceHistoryMaintenance.Status.MORE && it.examinedRows == 5 && it.deletedRows == 5 })
            assertEquals(10L, state().lastExaminedId)
            assertEquals(20L, state().upperId)
            assertEquals(10L, countPrices())
            assertEquals((11L..20L).toList(), ids())
        }
    }

    @Test fun newImportPreservesActivePassAndOldCompletionCannotClearNewTicket(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedUnowned(4)
            val old = arm()
            PriceHistoryMaintenance(database).runBatch(1)
            val active = requireNotNull(dao.metadata(STATE))
            val newerReference = reference + 86_400_000L
            importProduct(99, newerReference, verified(newerReference))
            val newRaw = requireNotNull(dao.metadata(PENDING))
            val newer = requireNotNull(PriceHistoryMaintenanceCodec.decodeTicket(newRaw))
            assertNotEquals(old.id, newer.id)
            assertEquals(active, dao.metadata(STATE))
            assertEquals(old.cutoffEpochMillis, state().ticket.cutoffEpochMillis)
            assertEquals(4L, state().upperId)
            val finishedOld = PriceHistoryMaintenance(database).runBatch(3)
            assertEquals(old.id, finishedOld.ticketId)
            assertEquals(PriceHistoryMaintenance.Status.PASS_COMPLETE, finishedOld.status)
            assertTrue(finishedOld.pendingAnotherPass)
            assertEquals(newRaw, dao.metadata(PENDING)); assertNull(dao.metadata(STATE))
            val next = PriceHistoryMaintenance(database).runBatch(1)
            assertEquals(newer.id, next.ticketId)
            assertEquals(5L, state().upperId)
            assertEquals(newer.cutoffEpochMillis, state().ticket.cutoffEpochMillis)
            val final = PriceHistoryMaintenance(database).runBatch(1)
            assertEquals(PriceHistoryMaintenance.Status.PASS_COMPLETE, final.status)
            assertFalse(final.pendingAnotherPass)
            assertNull(dao.metadata(PENDING)); assertNull(dao.metadata(STATE))
            assertEquals(2L, countPrices())
        }
    }

    @Test fun damagedCursorRescansOnlyWithValidTicketAndNeverUsesStoredClockAlone(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedUnowned(4)
            dao.putMetadata(AppMetadataEntity(STATE, "damaged progress"))
            dao.putMetadata(AppMetadataEntity(CardmarketHistoryReference.METADATA_KEY, reference.toString()))
            val noTicket = allRows()
            assertEquals(PriceHistoryMaintenance.Status.NO_TICKET, PriceHistoryMaintenance(database).runBatch(1).status)
            assertEquals(noTicket, allRows())
            dao.putMetadata(AppMetadataEntity(PENDING, "pht1|untrusted"))
            val invalid = allRows()
            assertEquals(PriceHistoryMaintenance.Status.INVALID_TICKET, PriceHistoryMaintenance(database).runBatch(1).status)
            assertEquals(invalid, allRows())
            val valid = arm()
            val restarted = PriceHistoryMaintenance(database).runBatch(1)
            assertEquals(valid.id, restarted.ticketId)
            assertEquals(1, restarted.deletedRows)
            assertEquals(1L, state().lastExaminedId)
            assertEquals(4L, state().upperId)
        }
    }

    @Test fun realSqlCursorConflictRollsBackInsteadOfOverwritingChangedState(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedUnowned(4); arm()
            PriceHistoryMaintenance(database).runBatch(1)
            val before = allRows()
            database.openHelper.writableDatabase.execSQL("""
                CREATE TEMP TRIGGER change_cursor_during_delete AFTER DELETE ON main.raw_price_snapshots
                BEGIN UPDATE app_metadata SET value='concurrent-state-probe' WHERE `key`='$STATE'; END;
            """.trimIndent())
            try {
                assertFailure(runCatching { PriceHistoryMaintenance(database).runBatch(1) }.exceptionOrNull(), "Maintenance cursor changed")
                assertEquals(before, allRows())
            } finally { database.openHelper.writableDatabase.execSQL("DROP TRIGGER temp.change_cursor_during_delete") }
            assertEquals(1, PriceHistoryMaintenance(database).runBatch(1).deletedRows)
        }
    }

    @Test fun ownershipCommittedBetweenBatchesProtectsRemainingWindowRowsAndOutbox(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedUnowned(5); arm()
            assertEquals(1, PriceHistoryMaintenance(database).runBatch(1).deletedRows)
            protect("unowned")
            val business = protectedTables()
            val remaining = rows("SELECT * FROM raw_price_snapshots ORDER BY id")
            repeat(2) { assertEquals(0, PriceHistoryMaintenance(database).runBatch(2).deletedRows) }
            assertEquals(remaining, rows("SELECT * FROM raw_price_snapshots ORDER BY id"))
            assertEquals(business, protectedTables())
            assertNull(dao.metadata(PENDING)); assertNull(dao.metadata(STATE))
        }
    }

    @Test fun emptyPassCompletesAndInvalidBatchSizesCannotMutateAnything(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            arm()
            val before = allRows()
            for (size in listOf(0, -1, 1025, Int.MAX_VALUE)) {
                assertTrue(runCatching { PriceHistoryMaintenance(database).runBatch(size) }.exceptionOrNull() is IllegalArgumentException)
                assertEquals(before, allRows())
            }
            val empty = PriceHistoryMaintenance(database).runBatch()
            assertEquals(PriceHistoryMaintenance.Status.PASS_COMPLETE, empty.status)
            assertEquals(0, empty.examinedRows); assertEquals(0, empty.deletedRows)
            assertNull(dao.metadata(PENDING)); assertNull(dao.metadata(STATE))
            assertEquals(PriceHistoryMaintenance.Status.NO_TICKET, PriceHistoryMaintenance(database).runBatch().status)
        }
    }

    @Test fun completionTicketFailureRollsBackDeletionAndCursorRemovalTogether(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedUnowned(4); arm()
            PriceHistoryMaintenance(database).runBatch(1)
            val before = allRows()
            database.openHelper.writableDatabase.execSQL("""
                CREATE TEMP TRIGGER fail_ticket_completion BEFORE DELETE ON main.app_metadata
                WHEN OLD.key='$PENDING'
                BEGIN SELECT CASE WHEN (SELECT COUNT(*) FROM main.raw_price_snapshots)=1 AND
                    (SELECT COUNT(*) FROM main.app_metadata WHERE `key`='$STATE')=0
                    THEN RAISE(ABORT,'COMPLETION_AFTER_DELETE_AND_CURSOR')
                    ELSE RAISE(ABORT,'COMPLETION_UNEXPECTED_ORDER') END; END;
            """.trimIndent())
            try {
                assertFailure(runCatching { PriceHistoryMaintenance(database).runBatch(3) }.exceptionOrNull(), "COMPLETION_AFTER_DELETE_AND_CURSOR")
                assertEquals(before, allRows())
            } finally { database.openHelper.writableDatabase.execSQL("DROP TRIGGER temp.fail_ticket_completion") }
            assertEquals(PriceHistoryMaintenance.Status.PASS_COMPLETE, PriceHistoryMaintenance(database).runBatch(3).status)
            assertEquals(1L, countPrices())
            assertNull(dao.metadata(PENDING)); assertNull(dao.metadata(STATE))
        }
    }

    @Test fun missingOrMismatchedVerifiedClockCannotAuthorizeEvenAWellFormedTicket(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedUnowned(4); arm()
            PriceHistoryMaintenance(database).runBatch(1)
            val active = dao.metadata(STATE)
            for (clock in listOf(null, "${reference + 1}", "0$reference", "future-clock")) {
                if (clock == null) dao.deleteMetadata(CardmarketHistoryReference.METADATA_KEY)
                else dao.putMetadata(AppMetadataEntity(CardmarketHistoryReference.METADATA_KEY, clock))
                val before = allRows()
                assertEquals(PriceHistoryMaintenance.Status.INVALID_TICKET, PriceHistoryMaintenance(database).runBatch(1).status)
                assertEquals(before, allRows())
                assertEquals(active, dao.metadata(STATE))
            }
            dao.putMetadata(AppMetadataEntity(CardmarketHistoryReference.METADATA_KEY, reference.toString()))
            assertEquals(1, PriceHistoryMaintenance(database).runBatch(1).deletedRows)
            assertEquals(2L, state().lastExaminedId)
        }
    }

    @Test fun regressedVerifiedClockPausesByteExactlyAndFreshTicketResumesOriginalCursor(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            dao.insertCatalogCard(catalog("owned-clock-window"))
            protect("owned-clock-window")
            val ownedTimes = listOf(
                cutoff - 2 * 86_400_000L, cutoff - 12 * 3_600_000L,
                cutoff - 6 * 3_600_000L, cutoff - 3_600_000L,
                cutoff + 1, cutoff + 2,
            )
            dao.insertRawPrices(ownedTimes.mapIndexed { index, at ->
                price("owned-clock-window", at, index.toLong() + 1)
            })
            val original = arm()
            assertEquals(1, PriceHistoryMaintenance(database).runBatch(1).deletedRows)
            val activeRaw = requireNotNull(dao.metadata(STATE))
            val active = state()
            assertEquals(original, active.ticket)
            assertEquals(6L, active.upperId)
            assertEquals(1L, active.lastExaminedId)

            // A real accepted response: unchanged provider Date, device receipt 24h behind.
            // The existing 48h plausibility contract accepts it and clamps the reference back.
            val receivedBehind = reference - 86_400_000L
            val backward = requireNotNull(CardmarketHistoryReference.fromPriceGuideResponse(
                DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(reference).atZone(ZoneOffset.UTC)),
                receivedBehind,
            ))
            assertEquals(receivedBehind, backward.epochMillis)
            importProduct(99, receivedBehind, backward)
            val earlierRaw = requireNotNull(dao.metadata(PENDING))
            val earlier = requireNotNull(PriceHistoryMaintenanceCodec.decodeTicket(earlierRaw))
            assertTrue(earlier.cutoffEpochMillis < original.cutoffEpochMillis)
            assertTrue(ownedTimes[1] >= earlier.cutoffEpochMillis && ownedTimes[1] < original.cutoffEpochMillis)
            assertTrue(ownedTimes[2] >= earlier.cutoffEpochMillis && ownedTimes[2] < original.cutoffEpochMillis)
            assertEquals(backward.epochMillis.toString(), dao.metadata(CardmarketHistoryReference.METADATA_KEY))
            assertEquals(activeRaw, dao.metadata(STATE))
            val pausedBefore = allRows()
            val paused = PriceHistoryMaintenance(database).runBatch(2)
            assertEquals("Paused clock must preserve ALL rows and metadata byte-for-byte", pausedBefore, allRows())
            assertEquals(PriceHistoryMaintenance.Batch(PriceHistoryMaintenance.Status.INVALID_TICKET), paused)
            assertEquals(earlierRaw, dao.metadata(PENDING))
            assertEquals(activeRaw, dao.metadata(STATE))

            val ahead = reference + 86_400_000L
            importProduct(100, ahead, verified(ahead))
            val freshRaw = requireNotNull(dao.metadata(PENDING))
            val fresh = requireNotNull(PriceHistoryMaintenanceCodec.decodeTicket(freshRaw))
            assertTrue(fresh.cutoffEpochMillis > original.cutoffEpochMillis)
            assertNotEquals(original.id, fresh.id)
            assertEquals(activeRaw, dao.metadata(STATE))
            val expectedRows = rows("SELECT * FROM raw_price_snapshots WHERE id NOT IN (2,3) ORDER BY id")
            val business = protectedTables()
            val resumed = PriceHistoryMaintenance(database).runBatch(2)
            assertEquals(PriceHistoryMaintenance.Status.MORE, resumed.status)
            assertEquals(original.id, resumed.ticketId)
            assertEquals(2, resumed.examinedRows)
            assertEquals(2, resumed.deletedRows)
            assertEquals(original, state().ticket)
            assertEquals(6L, state().upperId) // Not a fresh pass over newly imported IDs 7/8.
            assertEquals(3L, state().lastExaminedId)
            assertEquals(expectedRows, rows("SELECT * FROM raw_price_snapshots ORDER BY id"))
            assertEquals(business, protectedTables())
            assertEquals(freshRaw, dao.metadata(PENDING))
            val completed = PriceHistoryMaintenance(database).runBatch(3)
            assertEquals(PriceHistoryMaintenance.Status.PASS_COMPLETE, completed.status)
            assertEquals(original.id, completed.ticketId)
            assertEquals(3, completed.examinedRows)
            assertEquals(0, completed.deletedRows)
            assertTrue(completed.pendingAnotherPass)
            assertEquals(listOf(4L, 5L, 6L, 7L, 8L), ids())
            assertEquals(freshRaw, dao.metadata(PENDING))
            assertNull(dao.metadata(STATE))
        }
    }

    private suspend fun arm(): PriceHistoryMaintenanceCodec.Ticket {
        val ticket = PriceHistoryMaintenanceCodec.freshTicket(verified(reference))
        database.withTransaction {
            dao.putMetadata(AppMetadataEntity(CardmarketHistoryReference.METADATA_KEY, ticket.referenceEpochMillis.toString()))
            dao.putMetadata(AppMetadataEntity(PENDING, PriceHistoryMaintenanceCodec.encodeTicket(ticket)))
        }
        return ticket
    }
    private suspend fun state() = requireNotNull(PriceHistoryMaintenanceCodec.decodeState(dao.metadata(STATE)))
    private fun verified(at: Long) = requireNotNull(CardmarketHistoryReference.fromPriceGuideResponse(
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC)), at))

    private suspend fun importProduct(id: Long, at: Long, clock: CardmarketHistoryReference?, trend: Long = 123) {
        val sourceTime = Instant.ofEpochMilli(at).toString()
        val prepared = prepareCardmarketImport(
            ParsedDownload(1, sourceTime, listOf(CardmarketProduct(id, "Synthetic $id", 51, null, null))),
            ParsedDownload(1, sourceTime, listOf(CardmarketPrice(id, 51, null, null, trend, null, null, null, null, null, null))),
        )
        persistCardmarketImport(database, prepared, at, clock)
    }
    private suspend fun seedUnowned(count: Int) {
        dao.insertCatalogCard(catalog("unowned"))
        dao.insertRawPrices((1..count).map { price("unowned", cutoff + it, it.toLong()) })
    }
    private suspend fun protect(card: String) {
        dao.insertCollectionEntry(CollectionEntryEntity(cardId = card, quantity = 1, condition = "Near Mint",
            acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null,
            notes = "preserve", frontPhotoUri = "content://fixture/preserved", backPhotoUri = null,
            createdAtEpochMillis = 1, updatedAtEpochMillis = 2, ownerUid = GUEST_OWNER_UID,
            syncId = "protected-entry", syncState = SYNC_PENDING, deletedAtEpochMillis = 3))
        dao.upsertSyncOutbox(CollectionSyncOutboxEntity("protected-key", "unacknowledged", GUEST_OWNER_UID,
            "protected-entry", "DELETE", 0, SYNC_PENDING, 4, "preserved error"))
    }
    private fun catalog(id: String) = CatalogCardEntity(id, "POKEMON", "fixture", "Fixture", id,
        "001", "GERMAN", "Normal", "Standard", 1)
    private fun price(card: String, at: Long, value: Long) = RawPriceSnapshotEntity(cardId = card,
        source = SOURCE, currency = "EUR", lowValueMinor = null, averageValueMinor = -1,
        trendValueMinor = value, marketValueMinor = value, holoTrendValueMinor = 0,
        fetchedAtEpochMillis = reference, effectiveAtEpochMillis = at)
    private fun ids() = database.openHelper.readableDatabase.query("SELECT id FROM raw_price_snapshots ORDER BY id").use {
        buildList { while (it.moveToNext()) add(it.getLong(0)) }
    }
    private fun trends() = database.openHelper.readableDatabase.query("SELECT trendValueMinor FROM raw_price_snapshots ORDER BY id").use {
        buildList { while (it.moveToNext()) add(it.getLong(0)) }
    }
    private fun countPrices() = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM raw_price_snapshots").use {
        check(it.moveToFirst()); it.getLong(0)
    }
    private fun protectedTables() = allRows().filterKeys { it != "raw_price_snapshots" }.toMutableMap().also {
        it["app_metadata"] = rows("SELECT * FROM app_metadata WHERE `key` NOT IN ('$PENDING','$STATE') ORDER BY rowid")
    }
    private fun allRows(): Map<String, List<String>> {
        val names = database.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table') ORDER BY name").use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        return names.associateWith { name -> check(name.matches(Regex("[a-z_]+"))); rows("SELECT * FROM `$name` ORDER BY rowid") }
    }
    private fun rows(sql: String): List<String> = database.openHelper.readableDatabase.query(sql).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                repeat(cursor.columnCount) { column ->
                    out.writeUTF(cursor.getColumnName(column))
                    val type = cursor.getType(column); out.writeInt(type)
                    when (type) {
                        Cursor.FIELD_TYPE_NULL -> Unit
                        Cursor.FIELD_TYPE_INTEGER -> out.writeLong(cursor.getLong(column))
                        Cursor.FIELD_TYPE_FLOAT -> out.writeLong(java.lang.Double.doubleToRawLongBits(cursor.getDouble(column)))
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(column).toByteArray(Charsets.UTF_8).let { out.writeInt(it.size); out.write(it) }
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).let { out.writeInt(it.size); out.write(it) }
                        else -> error("Unknown SQLite type")
                    }
                }
            }
            add(Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
        }
    } }
    private fun assertFailure(failure: Throwable?, marker: String) {
        assertNotNull(failure)
        assertTrue(generateSequence(requireNotNull(failure)) { it.cause }.any { it.message.orEmpty().contains(marker) })
    }
    private class ExpectedRollback : RuntimeException()
    companion object {
        private const val SOURCE = CardmarketSyncContract.SOURCE
        private const val PENDING = PriceHistoryMaintenanceCodec.PENDING_KEY
        private const val STATE = PriceHistoryMaintenanceCodec.CURSOR_KEY
    }
}
