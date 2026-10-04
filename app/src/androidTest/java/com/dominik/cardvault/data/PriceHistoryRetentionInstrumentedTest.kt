package com.dominik.cardvault.data

import android.content.Context
import android.database.Cursor
import android.util.Base64
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.*
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import com.dominik.cardvault.model.PriceHistoryWindowPolicy
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated in-memory Room only. No importer, singleton DB, auth, worker, camera or network. */
@RunWith(AndroidJUnit4::class)
class PriceHistoryRetentionInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private lateinit var retention: PriceHistoryRetention
    private lateinit var photoRoot: File
    private val dao get() = database.cardVaultDao()
    private val statements = Collections.synchronizedList(mutableListOf<Pair<String, List<Any?>>>())
    private val cancelAtDelete = AtomicReference<Job?>()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        photoRoot = File(context.cacheDir.canonicalFile, "retention-photos-${UUID.randomUUID()}")
        check(photoRoot.mkdir())
        database = Room.inMemoryDatabaseBuilder(context, CardVaultDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, bindings ->
                val normalized = sql.trim().replace(Regex("\\s+"), " ")
                statements += normalized to bindings.toList()
                if (normalized.startsWith("DELETE FROM raw_price_snapshots ") ||
                    normalized.startsWith("WITH protected_cards(cardId) AS (")) {
                    cancelAtDelete.getAndSet(null)?.cancel()
                }
            }, Executor { it.run() }).build()
        retention = PriceHistoryRetention(database)
    }

    @After fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::photoRoot.isInitialized) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            check(photoRoot.parentFile == context.cacheDir.canonicalFile)
            check(photoRoot.name.startsWith("retention-photos-"))
            // Exact files created by this fixture only; never recursive cleanup of app storage.
            val files = photoRoot.listFiles().orEmpty()
            check(files.all { it.name in PHOTO_NAMES && it.canonicalFile.parentFile == photoRoot })
            files.forEach { check(it.delete()) }
            check(photoRoot.delete())
        }
    }

    @Test fun utcLeapBoundaryKeepsExactLastStateAndEveryOriginalWindowField() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            val cutoff = PriceHistoryWindowPolicy.startEpochMillis(epoch("2024-02-29T12:34:56.789Z"))
            assertEquals(epoch("2023-02-28T12:34:56.789Z"), cutoff)
            val input = mutableListOf<RawPriceSnapshotEntity>()
            listOf<Long?>(null, 0L, -19L).forEachIndexed { index, anchor ->
                val card = "boundary-$index"
                dao.insertCatalogCard(catalog(card))
                input += price(card, cutoff - 20, 800L)
                input += price(card, cutoff - 1, anchor).copy(holoTrendValueMinor = anchor)
                input += price(card, cutoff, null)
                input += price(card, cutoff + 1, -4L)
                input += price(card, Long.MAX_VALUE, 999L)
                // Newer other-source history cannot make this source's anchor deletable.
                input += price(card, cutoff - 2, 123L).copy(source = OTHER_SOURCE)
                input += price(card, cutoff - 1, 456L).copy(source = OTHER_SOURCE)
            }
            dao.insertCatalogCard(catalog("only-old"))
            input += price("only-old", Long.MIN_VALUE, 3L)
            input += price("only-old", cutoff - 7, null)
            dao.insertCatalogCard(catalog("no-old"))
            input += price("no-old", cutoff, 5L)
            dao.insertRawPrices(input.reversed()) // PK order deliberately differs from chronology.
            val before = rawRows()
            val expected = expectedRetained(before, cutoff)
            val results = drain(retention.beginPass(cutoff), 3)
            assertEquals(4, results.sumOf { it.deletedRows })
            assertEquals(expected, rawRows()) // All 16 stored columns, IDs and timestamps, not just trends.
            assertEquals(before.filter { it.source == OTHER_SOURCE }, rawRows().filter { it.source == OTHER_SOURCE })
        }
    }

    @Test fun scanLimitCountsNonDeletableRowsAndEachNonemptyBatchUsesOneRangeDelete() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            dao.insertCatalogCard(catalog("bound"))
            dao.insertRawPrices((0 until 1024).map { price("bound", it.toLong(), 1L).copy(source = OTHER_SOURCE) } +
                listOf(price("bound", 1L, 8L), price("bound", 2L, null), price("bound", 100L, 9L)))
            val cursor = retention.beginPass(100L)
            statements.clear()
            val first = retention.pruneBatch(cursor)
            assertEquals(1024, first.examinedRows)
            assertEquals(0, first.deletedRows)
            assertFalse(first.next.complete)
            assertEquals(1024L, first.next.lastExaminedId)
            val second = retention.pruneBatch(first.next)
            assertEquals(3, second.examinedRows)
            assertEquals(1, second.deletedRows)
            assertTrue(second.next.complete)
            val deletes = statementSnapshot().filter { it.first.startsWith("DELETE FROM raw_price_snapshots ") }
            assertEquals(2, deletes.size)
            assertEquals(listOf(1L, 1024L, SOURCE, 100L, 100L), deletes[0].second)
            assertEquals(listOf(1025L, 1027L, SOURCE, 100L, 100L), deletes[1].second)
            val selects = statementSnapshot().filter { it.first.startsWith("SELECT id FROM raw_price_snapshots WHERE") }
            assertEquals(2, selects.size)
            assertTrue(selects.all { it.first.endsWith("ORDER BY id LIMIT ?") && it.second.last() == 1024L })
        }
    }

    @Test fun interruptedPassReplayFreshPassAndHighWaterAreIdempotent() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            dao.insertCatalogCard(catalog("resume"))
            dao.insertRawPrices((1L..8L).map { price("resume", it, it) })
            val original = retention.beginPass(8L)
            val first = retention.pruneBatch(original, 2)
            assertEquals(2, first.deletedRows)
            // Simulate a lost progress result: replaying the INPUT cursor can only remove obsolete rows.
            val replay = retention.pruneBatch(original, 2)
            assertEquals(2, replay.deletedRows)
            assertEquals(8L, original.upperId)
            dao.insertRawPrices(listOf(price("resume", 0L, 777L))) // New ID beyond this pass, older time.
            drain(first.next, 2)
            assertEquals(listOf(7L, 8L, 0L), rawRows().map { it.at })
            // A new helper after a process-style restart deliberately rescans, without a false durable token.
            val fresh = PriceHistoryRetention(database)
            var cursor = fresh.beginPass(8L)
            var deleted = 0
            while (!cursor.complete) {
                val batch = fresh.pruneBatch(cursor, 2)
                deleted += batch.deletedRows
                cursor = batch.next
            }
            assertEquals(1, deleted)
            assertEquals(listOf(7L, 8L), rawRows().map { it.at })
            val stable = rawRows()
            assertEquals(0, drain(retention.beginPass(8L)).sumOf { it.deletedRows })
            assertEquals(stable, rawRows())
        }
    }

    @Test fun guestTwoOwnersPhotosOutboxesLayoutsPricePreferencesAndOtherTablesAreByteExact() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedBusinessState()
            dao.insertRawPrices(listOf(price("owned", 1L, 50L), price("owned", 2L, null), price("owned", 100L, 80L)))
            val before = otherTables()
            assertTrue(before.values.all { it.isNotEmpty() }) // Every protected table has real fixture data.
            val schema = rows("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name")
            val photos = PHOTO_NAMES.associateWith { File(photoRoot, it).readBytes().toList() }
            assertEquals(1, drain(retention.beginPass(100L), 2).sumOf { it.deletedRows })
            assertEquals(before, otherTables())
            assertEquals(schema, rows("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name"))
            assertEquals(photos, PHOTO_NAMES.associateWith { File(photoRoot, it).readBytes().toList() })
            assertEquals(listOf(2L, 100L), rawRows().map { it.at })
            assertEquals(listOf(GUEST_OWNER_UID, OWNER, OTHER).sorted(), rowsOwners())
        }
    }

    @Test fun outerTransactionFailureRollsBackDeletesAndInputCursorRemainsReusable() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedSimple()
            val before = rawRows()
            val cursor = retention.beginPass(100L)
            try {
                database.withTransaction {
                    assertEquals(2, retention.pruneBatch(cursor).deletedRows)
                    throw ExpectedRollback()
                }
                fail("Expected rollback")
            } catch (_: ExpectedRollback) { }
            assertEquals(before, rawRows())
            assertEquals(2, retention.pruneBatch(cursor).deletedRows)
        }
    }

    @Test fun cancellationAtRealDeleteRollsBackAndCancelledInputCanBeRetried() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedSimple()
            val before = rawRows()
            val cursor = retention.beginPass(100L)
            val child = launch {
                // Passive Room query callback cancels this job at the real DELETE boundary.
                // No SQL/result replacement, no retention hook, and the test parent stays active.
                cancelAtDelete.set(currentCoroutineContext()[Job]!!)
                retention.pruneBatch(cursor)
                error("Cancelled retention must not return a successful batch")
            }
            child.join()
            assertTrue(child.isCancelled)
            assertNull(cancelAtDelete.get())
            assertEquals(before, rawRows())
            assertEquals(2, retention.pruneBatch(cursor).deletedRows)
        }
    }

    @Test fun cancellationAfterNestedBatchBeforeOuterCommitAlsoRollsBack() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedSimple()
            val before = rawRows()
            val cursor = retention.beginPass(100L)
            val deletedButUncommitted = CompletableDeferred<Unit>()
            val child = launch {
                database.withTransaction {
                    assertEquals(2, retention.pruneBatch(cursor).deletedRows)
                    deletedButUncommitted.complete(Unit)
                    awaitCancellation()
                }
            }
            deletedButUncommitted.await()
            child.cancelAndJoin()
            assertEquals(before, rawRows())
            assertEquals(2, retention.pruneBatch(cursor).deletedRows)
        }
    }

    @Test fun activeRoomHistoryFlowAndTrackerObserveCommittedPruningWithoutManualRefresh() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedBusinessState()
            dao.insertRawPrices(listOf(price("owned", 1L, 5L), price("owned", 2L, null), price("owned", 100L, 9L)))
            val feed = Channel<List<OwnedPriceHistoryProjection>>(Channel.UNLIMITED)
            val invalidated = CompletableDeferred<Unit>()
            val observer = object : InvalidationTracker.Observer("raw_price_snapshots") {
                override fun onInvalidated(tables: Set<String>) {
                    if ("raw_price_snapshots" in tables) invalidated.complete(Unit)
                }
            }
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                dao.observeOwnedRawPriceHistory(OWNER, SOURCE).collect { feed.send(it) }
            }
            try {
                awaitHistory(feed, listOf(1L, 2L, 100L))
                database.invalidationTracker.addObserver(observer)
                assertEquals(1, retention.pruneBatch(retention.beginPass(100L)).deletedRows)
                invalidated.await()
                awaitHistory(feed, listOf(2L, 100L))
            } finally {
                database.invalidationTracker.removeObserver(observer)
                collector.cancelAndJoin()
                feed.close()
            }
        }
    }

    @Test fun concurrentReplaceOnEitherSideOfRetentionPreservesCorrectedAnchorAndCurrentRow() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedSimple()
            val cursor = retention.beginPass(100L)
            val anchor = price("simple", 3L, null).copy(holoTrendValueMinor = -900L, average30DayValueMinor = 654L)
            val current = price("simple", 100L, 0L).copy(holoAverageValueMinor = 999L, fetchedAtEpochMillis = 876L)
            coroutineScope {
                val writerHolding = CompletableDeferred<Unit>()
                val releaseWriter = CompletableDeferred<Unit>()
                val pruneRequested = CompletableDeferred<Unit>()
                val writer = async {
                    database.withTransaction {
                        dao.insertRawPricesReplacing(listOf(anchor, current))
                        writerHolding.complete(Unit)
                        releaseWriter.await()
                    }
                }
                try {
                    writerHolding.await()
                    val prune = async {
                        pruneRequested.complete(Unit)
                        retention.pruneBatch(cursor)
                    }
                    pruneRequested.await()
                    releaseWriter.complete(Unit)
                    writer.await()
                    assertEquals(2, prune.await().deletedRows)
                } finally { releaseWriter.complete(Unit) }
            }
            assertEquals(listOf(3L, 100L), rawRows().map { it.at })
            assertTrue(rawRows().all { it.id > cursor.upperId!! }) // REPLACE got new keys; no stale-ID deletion.
            val corrected = rawRows()
            assertEquals(0, drain(retention.beginPass(100L)).sumOf { it.deletedRows })
            assertEquals(corrected, rawRows())

            dao.insertRawPrices(listOf(price("simple", 1L, 7L)))
            val nextPass = retention.beginPass(100L)
            coroutineScope {
                val pruningHolding = CompletableDeferred<Unit>()
                val releasePruning = CompletableDeferred<Unit>()
                val writerRequested = CompletableDeferred<Unit>()
                val prune = async {
                    database.withTransaction {
                        assertEquals(1, retention.pruneBatch(nextPass).deletedRows)
                        pruningHolding.complete(Unit)
                        releasePruning.await()
                    }
                }
                try {
                    pruningHolding.await()
                    val writer = async {
                        writerRequested.complete(Unit)
                        dao.insertRawPricesReplacing(listOf(anchor.copy(trendValueMinor = -1L)))
                    }
                    writerRequested.await()
                    releasePruning.complete(Unit)
                    prune.await()
                    writer.await()
                } finally { releasePruning.complete(Unit) }
            }
            val afterReplace = rawRows()
            assertEquals(setOf(3L, 100L), afterReplace.map { it.at }.toSet())
            assertEquals(-1L, afterReplace.single { it.at == 3L }.trend)
            assertEquals(corrected.single { it.at == 100L }, afterReplace.single { it.at == 100L })
            assertEquals(0, drain(retention.beginPass(100L)).sumOf { it.deletedRows })
            assertEquals(afterReplace, rawRows())
        }
    }

    @Test fun emptyAndSignedPrimaryKeysExtremeCutoffsAndInvalidInputsFailSafely() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            val empty = retention.beginPass(Long.MIN_VALUE)
            assertTrue(empty.complete)
            assertNull(empty.upperId)
            assertEquals(0, retention.pruneBatch(empty).examinedRows)
            dao.insertCatalogCard(catalog("extreme"))
            dao.insertRawPrices(listOf(
                price("extreme", Long.MIN_VALUE, 1L).copy(id = Long.MIN_VALUE),
                price("extreme", -1L, null).copy(id = -7L),
                price("extreme", Long.MAX_VALUE, 8L).copy(id = Long.MAX_VALUE),
            ))
            val before = rawRows()
            assertEquals(0, drain(retention.beginPass(Long.MIN_VALUE), 1).sumOf { it.deletedRows })
            assertEquals(before, rawRows())
            val cursor = retention.beginPass(Long.MAX_VALUE)
            for (invalid in listOf(-1, 0, 1025, Int.MAX_VALUE)) {
                try { retention.pruneBatch(cursor, invalid); fail("Invalid limit accepted") }
                catch (_: IllegalArgumentException) { }
            }
            try { PriceHistoryRetention(database).pruneBatch(cursor); fail("Foreign cursor accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(before, rawRows())
            assertEquals(1, drain(cursor, 1).sumOf { it.deletedRows })
            assertEquals(listOf(-7L, Long.MAX_VALUE), rawRows().map { it.id })
        }
    }

    @Test fun collectionOnlyIsOptInAndUnownedLatestNullZeroNegativeStatesRemainByteExact() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            listOf<Long?>(null, 0L, -99L).forEachIndexed { index, latest ->
                val card = "unowned-latest-$index"
                dao.insertCatalogCard(catalog(card))
                dao.insertRawPrices(listOf(price(card, 10L, 7L), price(card, 50L, 8L),
                    price(card, 100L, 9L), price(card, 200L, latest).copy(holoTrendValueMinor = latest),
                    price(card, 10L, 17L).copy(source = OTHER_SOURCE),
                    price(card, 200L, 18L).copy(source = OTHER_SOURCE)))
            }
            val before = rawRows()
            val defaultPass = retention.beginPass(100L)
            assertFalse(defaultPass.collectionHistoryOnly)
            assertEquals(3, drain(defaultPass, 2).sumOf { it.deletedRows })
            assertEquals(expectedRetained(before, 100L), rawRows())
            val explicitFalse = retention.beginPass(100L, collectionHistoryOnly = false)
            assertFalse(explicitFalse.collectionHistoryOnly)
            assertEquals(0, drain(explicitFalse).sumOf { it.deletedRows })
            val first = retention.pruneBatch(retention.beginPass(100L, collectionHistoryOnly = true), 2)
            assertTrue(first.next.collectionHistoryOnly)
            assertEquals(6, first.deletedRows + drain(first.next, 2).sumOf { it.deletedRows })
            assertEquals(before.filter { it.source == OTHER_SOURCE || it.at == 200L }, rawRows())
            assertEquals(listOf(null, 0L, -99L), rawRows().filter { it.source == SOURCE }.map { it.trend })
            assertEquals(0, drain(retention.beginPass(100L, collectionHistoryOnly = true)).sumOf { it.deletedRows })
        }
    }

    @Test fun collectionOnlyProtectsEveryOwnerTombstonesUnsyncedExactLinksAndStoredSelections() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedBusinessState() // Also retains nonempty outboxes, photos, layout and preferences.
            val protected = setOf("owned", "policy-guest", "policy-soft", "policy-unsynced", "policy-visual",
                "policy-linked", "policy-selected", "policy-other-visual", "policy-other-linked")
            val unowned = setOf("policy-wrong-link-source", "policy-wrong-selection-source", "policy-same-name")
            (protected - "owned" + unowned).forEach { id ->
                val external = when (id) {
                    "policy-linked" -> SOURCE to 7001L
                    "policy-wrong-link-source" -> OTHER_SOURCE to 7001L
                    "policy-selected" -> SOURCE to 7002L
                    "policy-wrong-selection-source" -> OTHER_SOURCE to 7002L
                    "policy-other-linked" -> OTHER_SOURCE to 7003L
                    else -> null
                }
                dao.insertCatalogCard(catalog(id).copy(name = "Identical names must never protect history",
                    externalSource = external?.first, externalProductId = external?.second))
            }
            dao.insertCollectionEntry(policyEntry("policy-guest", GUEST_OWNER_UID, "guest"))
            dao.insertCollectionEntry(policyEntry("policy-soft", OWNER, "soft").copy(deletedAtEpochMillis = 5L, syncState = SYNC_PENDING))
            dao.insertCollectionEntry(policyEntry("policy-unsynced", OTHER, "unsynced").copy(syncState = SYNC_ERROR))
            val visual = policyEntry("policy-visual", OWNER, "linked").copy(externalProductSource = SOURCE, externalProductId = 7001L)
            val visualId = dao.insertCollectionEntry(visual)
            dao.insertCollectionEntry(policyEntry("policy-other-visual", OTHER, "other-linked")
                .copy(externalProductSource = OTHER_SOURCE, externalProductId = 7003L))
            // Even a cleared sidecar is conservatively protected; no metadata/value is rewritten.
            dao.upsertPriceSelection(CollectionPriceSelectionEntity(visualId, OWNER, visual.syncId, true,
                productId = 7002L, updatedAtEpochMillis = 7L, syncState = SYNC_CONFLICT))
            (protected + unowned).forEach { card -> dao.insertRawPrices(listOf(
                price(card, 10L, 7L), price(card, 50L, null), price(card, 100L, 0L), price(card, 200L, -1L))) }
            val before = rawRows()
            val business = otherTables()
            val photos = PHOTO_NAMES.associateWith { File(photoRoot, it).readBytes().toList() }
            val results = drain(retention.beginPass(100L, collectionHistoryOnly = true), 7)
            assertEquals(protected.size + unowned.size * 3, results.sumOf { it.deletedRows })
            assertEquals(before.filter { if (it.card in protected) it.at >= 50L else it.at == 200L }, rawRows())
            assertEquals(business, otherTables())
            assertEquals(photos, PHOTO_NAMES.associateWith { File(photoRoot, it).readBytes().toList() })
        }
    }

    @Test fun collectionProtectionIsReevaluatedAfterCommittedEntryChangesBetweenBatches() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            val names = listOf("policy-head", "policy-newly-owned", "policy-formerly-owned", "policy-parallel-owned")
            names.forEach { dao.insertCatalogCard(catalog(it)) }
            dao.insertRawPrices(listOf(10L, 100L, 200L).map { price(names[0], it, 7L) })
            names.drop(1).forEach { card -> dao.insertRawPrices(listOf(10L, 50L, 100L, 200L).map { price(card, it, null) }) }
            val original = policyEntry(names[2], OWNER, "moving-entry")
            val entry = original.copy(id = dao.insertCollectionEntry(original))
            val pass = retention.beginPass(100L, collectionHistoryOnly = true)
            val first = retention.pruneBatch(pass, 3)
            assertEquals(3, first.examinedRows)
            assertEquals(2, first.deletedRows)
            val beforeNext = rawRows()
            val updated = entry.copy(cardId = names[1], updatedAtEpochMillis = 8L)
            coroutineScope {
                val writerHolding = CompletableDeferred<Unit>()
                val releaseWriter = CompletableDeferred<Unit>()
                val pruneRequested = CompletableDeferred<Unit>()
                val writer = async {
                    database.withTransaction {
                        dao.updateCollectionEntry(updated)
                        dao.insertCollectionEntry(policyEntry(names[3], GUEST_OWNER_UID, "added-guest"))
                        writerHolding.complete(Unit)
                        releaseWriter.await()
                    }
                }
                try {
                    writerHolding.await()
                    val prune = async { pruneRequested.complete(Unit); retention.pruneBatch(first.next) }
                    pruneRequested.await()
                    releaseWriter.complete(Unit)
                    writer.await()
                    val result = prune.await()
                    assertEquals(12, result.examinedRows)
                    assertEquals(5, result.deletedRows)
                    assertTrue(result.next.complete)
                    assertTrue(result.next.collectionHistoryOnly)
                } finally { releaseWriter.complete(Unit) }
            }
            assertEquals(beforeNext.filter { row -> when (row.card) {
                names[1], names[3] -> row.at >= 50L
                else -> row.at == 200L
            } }, rawRows())
            assertEquals(updated, dao.getCollectionEntry(updated.id, OWNER))
            assertNotNull(dao.getCollectionEntryBySyncId(GUEST_OWNER_UID, "added-guest"))
            assertEquals(0, drain(retention.beginPass(100L, collectionHistoryOnly = true)).sumOf { it.deletedRows })
        }
    }

    @Test fun collectionPolicyUsesOneDeleteWithAnUncorrelatedProtectionListAndIndexedPriceProbes(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedSimple()
            dao.insertCollectionEntry(policyEntry("simple", OWNER, "plan"))
            val cursor = retention.beginPass(100L, collectionHistoryOnly = true)
            statements.clear()
            assertEquals(2, retention.pruneBatch(cursor).deletedRows)
            val deletes = statementSnapshot().filter { it.first.startsWith("WITH protected_cards(cardId) AS (") }
            assertEquals("One real DELETE statement, no per-candidate owner lookups", 1, deletes.size)
            val (sql, args) = deletes.single()
            assertEquals(listOf(1L, 4L, SOURCE, 100L, 100L), args)
            val details = database.openHelper.readableDatabase.query(androidx.sqlite.db.SimpleSQLiteQuery(
                "EXPLAIN QUERY PLAN $sql", args.toTypedArray(),
            )).use { plan -> buildList {
                while (plan.moveToNext()) add(plan.getString(plan.columnCount - 1))
            } }
            // API26's legacy wording and modern SQLite IDs differ. Assert structural witnesses,
            // not an entire text plan, node count, engine version or preferred cost estimate.
            assertTrue(details.any { it.contains("LIST SUBQUERY", ignoreCase = true) })
            assertTrue(details.any { it.contains("index_raw_price_snapshots_cardId_source_effectiveAtEpochMillis") })
            assertTrue(details.any { it.contains("index_catalog_cards_externalSource_externalProductId") })
            android.util.Log.i("PriceRetentionPlan", details.joinToString(" | "))
        }
    }

    private fun policyEntry(card: String, owner: String, sync: String) = CollectionEntryEntity(
        cardId = card, quantity = 1, condition = "Near Mint", acquisitionPriceMinor = null,
        acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "protected policy fixture",
        frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = 1L, updatedAtEpochMillis = 2L,
        ownerUid = owner, syncId = sync, syncState = SYNC_LOCAL_ONLY,
    )

    @Test fun collectionOnlyCancellationAndOuterRollbackPreserveOwnershipAndAllRows() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) {
            seedBusinessState()
            dao.insertCatalogCard(catalog("policy-rollback-unowned"))
            listOf("owned", "policy-rollback-unowned").forEach { card ->
                dao.insertRawPrices(listOf(10L, 50L, 100L, 200L).map { price(card, it, null) })
            }
            val before = rawRows()
            val business = otherTables()
            val photos = PHOTO_NAMES.associateWith { File(photoRoot, it).readBytes().toList() }
            val cursor = retention.beginPass(100L, collectionHistoryOnly = true)
            try {
                database.withTransaction {
                    assertEquals(4, retention.pruneBatch(cursor).deletedRows)
                    throw ExpectedRollback()
                }
                fail("Expected collection-only rollback")
            } catch (_: ExpectedRollback) { }
            assertEquals(before, rawRows())
            assertEquals(business, otherTables())
            assertEquals(photos, PHOTO_NAMES.associateWith { File(photoRoot, it).readBytes().toList() })
            val child = launch {
                cancelAtDelete.set(currentCoroutineContext()[Job]!!)
                retention.pruneBatch(cursor)
                error("Cancelled collection-only retention must not report success")
            }
            child.join()
            assertTrue(child.isCancelled)
            assertNull(cancelAtDelete.get()) // The real WITH/DELETE boundary was reached.
            assertEquals(before, rawRows())
            assertEquals(business, otherTables())
            assertEquals(photos, PHOTO_NAMES.associateWith { File(photoRoot, it).readBytes().toList() })
            assertEquals(4, retention.pruneBatch(cursor).deletedRows)
            assertEquals(before.filter { if (it.card == "owned") it.at >= 50L else it.at == 200L }, rawRows())
            assertEquals(business, otherTables())
            assertEquals(photos, PHOTO_NAMES.associateWith { File(photoRoot, it).readBytes().toList() })
        }
    }

    private suspend fun drain(start: PriceHistoryRetention.Cursor, size: Int = 1024): List<PriceHistoryRetention.Batch> {
        var cursor = start
        val batches = mutableListOf<PriceHistoryRetention.Batch>()
        while (!cursor.complete) {
            check(batches.size < 100) { "Fixture pass did not terminate" }
            val batch = retention.pruneBatch(cursor, size)
            assertTrue(batch.examinedRows in 0..size)
            assertTrue(batch.deletedRows in 0..batch.examinedRows)
            batches += batch
            cursor = batch.next
        }
        return batches
    }

    private suspend fun seedSimple() {
        dao.insertCatalogCard(catalog("simple"))
        dao.insertRawPrices(listOf(1L, 2L, 3L, 100L).map { price("simple", it, it) })
    }

    private suspend fun seedBusinessState() = database.withTransaction {
        dao.insertCatalogCard(catalog("owned"))
        dao.insertBinder(BinderEntity("set-binder", "set-key", "Synthetic binder", "local-cover", 1L, 2L))
        PHOTO_NAMES.forEachIndexed { index, name ->
            val file = File(photoRoot, name)
            check(file.createNewFile())
            file.writeBytes(ByteArray(64) { (it + index).toByte() })
        }
        listOf(GUEST_OWNER_UID, OWNER, OTHER).forEachIndexed { index, owner ->
            val state = listOf(SYNC_LOCAL_ONLY, SYNC_PENDING, SYNC_CONFLICT)[index]
            val syncId = "entry-$index"
            val id = dao.insertCollectionEntry(CollectionEntryEntity(
                cardId = "owned", quantity = index + 1, condition = "Near Mint", acquisitionPriceMinor = 999L,
                acquisitionCurrency = "EUR", acquisitionDateEpochDay = 19_000L, notes = "retained-$owner",
                frontPhotoUri = File(photoRoot, "front.bin").toURI().toString(),
                backPhotoUri = File(photoRoot, "back.bin").toURI().toString(),
                createdAtEpochMillis = 12L, updatedAtEpochMillis = 13L, binderId = "set-binder",
                ownerUid = owner, syncId = syncId, syncState = state, remoteRevision = index.toLong(),
                sourceSetId = "fixture", sourceCardId = "fixture-001", finishOverride = "Holo",
            ))
            dao.insertPrivateBinder(PrivateBinderEntity("private-$index", owner, "Private $index",
                minimumPageCount = 7, createdAtEpochMillis = 1L, updatedAtEpochMillis = 2L, syncState = state))
            dao.insertPrivateBinderPlacement(PrivateBinderPlacementEntity("placement-$index", owner,
                "private-$index", id, 99L, "effect", 0.5f, 1L, 2L, state))
            dao.upsertCollectionLayout(CollectionLayoutEntity(id, owner, false, 19L, state, 3L))
            dao.upsertPriceSelection(CollectionPriceSelectionEntity(id, owner, syncId, false,
                sourceSetId = "fixture", sourceCardId = "fixture-001", collectorNumber = "001",
                finish = "Holo", variant = "Standard", language = "GERMAN", productId = 42L,
                metric = "TREND", amountMinor = 777L, currency = "EUR", sourceAt = 1L,
                selectedAt = 2L, updatedAtEpochMillis = 3L, syncState = state, remoteRevision = 4L))
            dao.upsertSyncOutbox(CollectionSyncOutboxEntity("entry-key-$index", "mutation-$index", owner,
                syncId, "UPSERT", 2L, state, 21L, "kept-error"))
            dao.upsertPrivateBinderSyncOutbox(PrivateBinderSyncOutboxEntity("price-key-$index", "price-mutation-$index",
                owner, "PRICE_SELECTION", syncId, "UPSERT", 2L, 22L, 21L, "price-selection-conflict:kept"))
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO grading_analyses (collectionEntryId,status,frontPhotoUri,backPhotoUri,createdAtEpochMillis) VALUES (?,?,?,?,?)",
                arrayOf<Any?>(id, "LOCAL_PENDING", File(photoRoot, "front.bin").toURI().toString(),
                    File(photoRoot, "back.bin").toURI().toString(), 25L))
        }
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO graded_market_records (cardId,gradingCompany,grade,recordType,valueMinor,currency,source,recordedAtEpochMillis) VALUES (?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("owned", "SYNTHETIC", 9.5, "FIXTURE", 999L, "EUR", "OTHER", 27L))
        dao.putMetadata(AppMetadataEntity("protected-receipt", "pending-unacknowledged-mutation"))
        dao.putSyncState(CatalogSyncStateEntity(SOURCE, "SUCCESS", 30L, 29L, "original-date", 1, 9, "kept"))
    }

    private data class RawRow(val id: Long, val card: String, val source: String, val at: Long, val trend: Long?, val bytes: String)

    private fun rawRows(): List<RawRow> = database.openHelper.readableDatabase.query(
        "SELECT * FROM raw_price_snapshots ORDER BY id",
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(RawRow(cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            cursor.getString(cursor.getColumnIndexOrThrow("cardId")), cursor.getString(cursor.getColumnIndexOrThrow("source")),
            cursor.getLong(cursor.getColumnIndexOrThrow("effectiveAtEpochMillis")),
            cursor.getColumnIndexOrThrow("trendValueMinor").let { if (cursor.isNull(it)) null else cursor.getLong(it) },
            encodedRow(cursor)))
    } }

    private fun expectedRetained(input: List<RawRow>, cutoff: Long): List<RawRow> {
        val anchors = input.filter { it.source == SOURCE && it.at < cutoff }
            .groupBy { it.card to it.source }.values.map { group -> group.maxBy { it.at }.id }.toSet()
        return input.filter { it.source != SOURCE || it.at >= cutoff || it.id in anchors }
    }

    private fun otherTables(): Map<String, List<String>> {
        val names = database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('raw_price_snapshots','android_metadata','room_master_table') ORDER BY name",
        ).use { buildList { while (it.moveToNext()) add(it.getString(0)) } }
        return names.associateWith { name ->
            check(name.matches(Regex("[a-z_]+")))
            rows("SELECT * FROM `$name` ORDER BY rowid")
        }
    }

    private fun rowsOwners(): List<String> = database.openHelper.readableDatabase.query(
        "SELECT ownerUid FROM collection_entries ORDER BY ownerUid",
    ).use { buildList { while (it.moveToNext()) add(it.getString(0)) } }

    private fun rows(sql: String): List<String> = database.openHelper.readableDatabase.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add(encodedRow(cursor)) }
    }

    private fun encodedRow(cursor: Cursor): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(cursor.columnCount)
            repeat(cursor.columnCount) { column ->
                out.writeUTF(cursor.getColumnName(column))
                val type = cursor.getType(column)
                out.writeInt(type)
                when (type) {
                    Cursor.FIELD_TYPE_NULL -> Unit
                    Cursor.FIELD_TYPE_INTEGER -> out.writeLong(cursor.getLong(column))
                    Cursor.FIELD_TYPE_FLOAT -> out.writeLong(java.lang.Double.doubleToRawLongBits(cursor.getDouble(column)))
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(column).toByteArray(Charsets.UTF_8).let { out.writeInt(it.size); out.write(it) }
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).let { out.writeInt(it.size); out.write(it) }
                    else -> error("Unknown SQLite cell type")
                }
            }
        }
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun awaitHistory(feed: Channel<List<OwnedPriceHistoryProjection>>, times: List<Long>) {
        while (true) if (feed.receive().map { it.effectiveAtEpochMillis } == times) return
    }

    private fun statementSnapshot() = synchronized(statements) { statements.toList() }
    private fun catalog(id: String) = CatalogCardEntity(id, "POKEMON", "fixture", "Synthetic", "Synthetic $id",
        "001", "GERMAN", "Normal", "Standard", 1L)
    private fun price(card: String, at: Long, trend: Long?) = RawPriceSnapshotEntity(
        cardId = card, source = SOURCE, currency = "EUR", lowValueMinor = 11L, averageValueMinor = 12L,
        trendValueMinor = trend, marketValueMinor = 13L, average1DayValueMinor = null, average7DayValueMinor = 0L,
        average30DayValueMinor = -14L, holoAverageValueMinor = 15L, holoLowValueMinor = 16L,
        holoTrendValueMinor = -17L, fetchedAtEpochMillis = 9_999L, effectiveAtEpochMillis = at,
    )
    private fun epoch(value: String) = Instant.parse(value).toEpochMilli()
    private class ExpectedRollback : RuntimeException()

    companion object {
        private const val SOURCE = CardmarketSyncContract.SOURCE
        private const val OTHER_SOURCE = "OTHER_PRICE_SOURCE"
        private const val OWNER = "retention-owner-a"
        private const val OTHER = "retention-owner-b"
        private val PHOTO_NAMES = setOf("front.bin", "back.bin")
    }
}
