package com.dominik.cardvault.data.sync

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.AppMetadataEntity
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.CollectionEntryEntity
import com.dominik.cardvault.data.local.CollectionSyncOutboxEntity
import com.dominik.cardvault.data.local.SYNC_CLEAN
import com.dominik.cardvault.data.local.SYNC_CONFLICT
import com.dominik.cardvault.data.local.SYNC_ERROR
import com.dominik.cardvault.data.local.SYNC_PENDING
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual worker commit sections, Room writer observations and real lease invalidation. No SDK/network/default DB. */
@RunWith(AndroidJUnit4::class)
class CollectionSyncSessionCommitsInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val dao get() = database.cardVaultDao()
    private val generation = AtomicLong(1)
    private val closed = AtomicBoolean(false)
    private var currentOwner: String? = OWNER
    private lateinit var original: CollectionEntryEntity
    private lateinit var foreign: CollectionEntryEntity
    private val mutation = CollectionSyncOutboxEntity(KEY, "mutation-1", OWNER, ENTRY, "UPSERT", 4,
        SYNC_PENDING, 100, null)
    private val foreignMutation = mutation.copy(entryKey = "$OTHER:$ENTRY", mutationId = "foreign-mutation", ownerUid = OTHER)

    @Before fun setup() = runBlocking(Dispatchers.IO) {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        dao.insertCatalogCard(CatalogCardEntity("synthetic-card", "POKEMON", "set", "Synthetic set",
            "Synthetic card", "001/001", "GERMAN", "Normal", "Standard", 10))
        val row = CollectionEntryEntity(cardId = "synthetic-card", quantity = 2, condition = "Near Mint",
            acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null,
            notes = "local payload", frontPhotoUri = null, backPhotoUri = null,
            createdAtEpochMillis = 10, updatedAtEpochMillis = 100, ownerUid = OWNER,
            syncId = ENTRY, syncState = SYNC_PENDING, remoteRevision = 4)
        original = row.copy(id = dao.insertCollectionEntry(row))
        val other = row.copy(ownerUid = OTHER, quantity = 9, notes = "foreign owner untouched")
        foreign = other.copy(id = dao.insertCollectionEntry(other))
        dao.upsertSyncOutbox(mutation)
        dao.upsertSyncOutbox(foreignMutation)
        dao.putMetadata(AppMetadataEntity("collection-session-sentinel", "unchanged"))
    }

    @After fun close() { closed.set(true); database.close() }

    @Test fun acknowledgedEntryWriteRollsBackOnObservedAccountRoundTripThenFreshAckSucceeds() = runBlocking(Dispatchers.IO) {
        assertRoundTripRollback({ entryIs(SYNC_CLEAN, 5) && outboxIs(SYNC_PENDING, null, "mutation-1", 4) }) {
            it.acknowledge(mutation, 5)
        }
        assertTrue(active().acknowledge(mutation, 5))
        assertEquals(original.copy(syncState = SYNC_CLEAN, remoteRevision = 5), row())
        assertEquals("ACK retains the exact durable reread checkpoint", mutation, dao.syncOutbox(KEY))
        assertForeignUnchanged()
    }

    @Test fun terminalErrorEntryAndOutboxWritesRollBackTogetherThenFreshFailureSucceeds() = runBlocking(Dispatchers.IO) {
        failureRollbackAndControl(SYNC_ERROR, "permission-denied")
    }

    @Test fun conflictEntryAndOutboxWritesRollBackTogetherThenFreshFailureSucceeds() = runBlocking(Dispatchers.IO) {
        failureRollbackAndControl(SYNC_CONFLICT, "remote-revision-7")
    }

    private suspend fun failureRollbackAndControl(state: String, reason: String) {
        assertRoundTripRollback({ entryIs(state, 4) && outboxIs(state, reason, "mutation-1", 4) }) {
            it.fail(mutation, state, reason)
        }
        assertTrue(active().fail(mutation, state, reason))
        assertEquals(original.copy(syncState = state), row())
        assertEquals(mutation.copy(state = state, lastError = reason), dao.syncOutbox(KEY))
        assertForeignUnchanged()
    }

    @Test fun normalizationOfBothTerminalStatesRollsBackOnlyItsNewOutboxWrite() = runBlocking(Dispatchers.IO) {
        for (state in listOf(SYNC_ERROR, SYNC_CONFLICT)) {
            val terminal = original.copy(syncState = state)
            dao.updateCollectionEntry(terminal)
            dao.upsertSyncOutbox(mutation)
            assertRoundTripRollback({ entryIs(state, 4) && outboxIs(state, COLLECTION_SYNC_STATE_MISMATCH, "mutation-1", 4) }) {
                it.normalize(mutation)
            }
            assertTrue(active().normalize(mutation))
            assertEquals("Normalization must never rewrite the terminal entry", terminal, row())
            assertEquals(mutation.copy(state = state, lastError = COLLECTION_SYNC_STATE_MISMATCH), dao.syncOutbox(KEY))
            assertForeignUnchanged()
        }
    }

    @Test fun orphanDiscardRollsBackDeletedOutboxThenFreshDiscardSucceeds() = runBlocking(Dispatchers.IO) {
        dao.deleteCollectionEntry(original.id)
        assertRoundTripRollback({ !entryExists() && !outboxExists() }) { it.discard(mutation) }
        assertTrue(active().discard(mutation))
        assertNull(dao.syncOutbox(KEY))
        assertNull(dao.getCollectionEntryBySyncId(OWNER, ENTRY))
        assertFalse(active().discard(mutation))
        assertForeignUnchanged()
    }

    @Test fun realRemotePayloadAndOutboxDeletionRollBackTogetherThenFreshRereadSucceeds() = runBlocking(Dispatchers.IO) {
        seedRereadCheckpoint()
        assertRoundTripRollback(::rereadWritesVisible) { it.finishReread(mutation, remote()) }
        active().finishReread(mutation, remote())
        assertEquals(expectedRemoteRow(), row())
        assertNull(dao.syncOutbox(KEY))
        assertForeignUnchanged()
    }

    @Test fun lateAckRebaseOfSuccessorRollsBackAndFreshAckKeepsSuccessorIdentity() = runBlocking(Dispatchers.IO) {
        val successor = mutation.copy(mutationId = "mutation-2", createdAtEpochMillis = 110)
        dao.upsertSyncOutbox(successor)
        assertRoundTripRollback({ entryIs(SYNC_PENDING, 5) && outboxIs(SYNC_PENDING, null, "mutation-2", 5) }) {
            it.acknowledge(mutation, 5)
        }
        assertFalse("Late M1 ACK rebases M2 but cannot claim its reread", active().acknowledge(mutation, 5))
        assertEquals(original.copy(remoteRevision = 5), row())
        assertEquals(successor.copy(expectedRemoteRevision = 5), dao.syncOutbox(KEY))
        assertForeignUnchanged()
    }

    @Test fun oldFailureNormalizationDiscardAndRereadCannotRemovePendingSuccessor() = runBlocking(Dispatchers.IO) {
        val successor = mutation.copy(mutationId = "mutation-2", createdAtEpochMillis = 110)
        val edited = original.copy(notes = "newer unsent local edit", updatedAtEpochMillis = 110)
        dao.upsertSyncOutbox(successor)
        dao.updateCollectionEntry(edited)
        val before = snapshot()
        assertFalse(active().fail(mutation, SYNC_ERROR, "old-error"))
        assertFalse(active().fail(mutation, SYNC_CONFLICT, "old-conflict"))
        assertFalse(active().normalize(mutation))
        assertFalse(active().discard(mutation))
        active().finishReread(mutation, remote())
        assertEquals(before, snapshot())
        assertEquals(edited, row())
        assertEquals(successor, dao.syncOutbox(KEY))
    }

    @Test fun existingTerminalDecisionsRejectLateAckAndFailureWithoutAnyWrite() = runBlocking(Dispatchers.IO) {
        for (state in listOf(SYNC_ERROR, SYNC_CONFLICT)) {
            dao.updateCollectionEntry(original.copy(syncState = state))
            dao.upsertSyncOutbox(mutation.copy(state = state, lastError = "explicit-decision"))
            val before = snapshot()
            assertFalse(active().acknowledge(mutation, 5))
            assertFalse(active().fail(mutation, SYNC_ERROR, "late-error"))
            assertFalse(active().fail(mutation, SYNC_CONFLICT, "late-conflict"))
            assertFalse(active().normalize(mutation))
            assertEquals(before, snapshot())
        }
    }

    @Test fun alreadyInvalidatedLeaseCannotEnterAnyCommitSection() = runBlocking(Dispatchers.IO) {
        val captured = lease()
        val commits = CollectionSyncSessionCommits(database, OWNER, captured::isCurrent)
        switchOwner(OTHER); switchOwner(OWNER)
        val before = snapshot()
        for (action in listOf<suspend () -> Unit>(
            { commits.acknowledge(mutation, 5) },
            { commits.fail(mutation, SYNC_ERROR, "error") },
            { commits.normalize(mutation) },
            { commits.discard(mutation) },
            { commits.finishReread(mutation, remote()) },
        )) {
            expectFailure<PrivatePullAccountChangedException>(action)
            assertEquals(before, snapshot())
        }
    }

    @Test fun cancellingActualCoroutineAfterRereadWritesRollsBackWithoutInvalidatingOwner() = runBlocking(Dispatchers.IO) {
        withTimeout(20_000) {
            seedRereadCheckpoint()
            val before = snapshot()
            val captured = lease()
            var observed: Map<String, List<List<String?>>>? = null
            supervisorScope {
                val child = async {
                    val runningJob = currentCoroutineContext().job
                    val commits = CollectionSyncSessionCommits(database, OWNER) {
                        if (observed == null && rereadWritesVisible()) {
                            assertTrue(database.openHelper.writableDatabase.inTransaction())
                            observed = snapshot()
                            runningJob.cancel(CancellationException("cancel-after-actual-reread-writes"))
                        }
                        captured.isCurrent() // Still true: cancellation, not account mismatch, must roll back.
                    }
                    commits.finishReread(mutation, remote())
                }
                expectFailure<CancellationException> { child.await() }
                child.join()
            }
            assertNotNull("Cancellation must occur only after both real writes are visible", observed)
            assertNotEquals(before, observed)
            assertTrue(captured.isCurrent())
            assertEquals(before, snapshot())
            active().finishReread(mutation, remote())
            assertEquals(expectedRemoteRow(), row())
            assertNull(dao.syncOutbox(KEY))
            assertForeignUnchanged()
        }
    }

    /** Trigger solely on the actual uncommitted state, not a guessed number of guard invocations. */
    private suspend fun assertRoundTripRollback(
        writesVisible: () -> Boolean,
        action: suspend (CollectionSyncSessionCommits) -> Unit,
    ) {
        val before = snapshot()
        val captured = lease()
        var observed: Map<String, List<List<String?>>>? = null
        val commits = CollectionSyncSessionCommits(database, OWNER) {
            if (observed == null && writesVisible()) {
                assertTrue("Probe must run inside the actual Room writer transaction", database.openHelper.writableDatabase.inTransaction())
                observed = snapshot()
                switchOwner(OTHER); switchOwner(OWNER)
            }
            captured.isCurrent()
        }
        expectFailure<PrivatePullAccountChangedException> { action(commits) }
        assertNotNull("The target production writes must have happened before lease invalidation", observed)
        assertNotEquals("The inside-transaction snapshot must contain the new writes", before, observed)
        assertEquals(OWNER, currentOwner)
        assertFalse(captured.isCurrent())
        assertEquals("The entire relevant Room snapshot must roll back, including the foreign owner", before, snapshot())
    }

    private suspend fun seedRereadCheckpoint() {
        dao.updateCollectionEntry(original.copy(syncState = SYNC_CLEAN, remoteRevision = 5))
        assertEquals(mutation, dao.syncOutbox(KEY))
    }

    private fun remote() = RemoteCollectionEntry(
        syncId = ENTRY, revision = 6, updatedAtEpochMillis = 600, deletedAtEpochMillis = null,
        name = "Synthetic cloud card", setId = "cloud-set", setName = "Synthetic cloud set",
        collectorNumber = "002/002", language = "GERMAN", finish = "Holo", variant = "Reverse", rarity = "RARE",
        quantity = 7, condition = "Good", notes = "confirmed cloud payload", acquisitionPriceMinor = 432,
        acquisitionCurrency = "EUR", acquisitionDateEpochDay = 19000, externalProductSource = "CARDMARKET_PUBLIC_DOWNLOAD",
        externalProductId = 42, imageUrl = null, effectOverride = "galaxy", sourceSetId = "cloud-set",
        sourceCardId = "cloud-set-002", binderId = "cloud-binder", binderSetKey = "tcgdex-cloud-set",
        binderName = "Synthetic cloud set", binderHeroAsset = null,
    )

    private fun expectedRemoteRow() = original.copy(
        quantity = 7, condition = "Good", notes = "confirmed cloud payload", acquisitionPriceMinor = 432,
        acquisitionCurrency = "EUR", acquisitionDateEpochDay = 19000, updatedAtEpochMillis = 600,
        externalProductSource = "CARDMARKET_PUBLIC_DOWNLOAD", externalProductId = 42,
        binderId = "cloud-binder", syncState = SYNC_CLEAN, remoteRevision = 6,
        finishOverride = "Holo", variantOverride = "Reverse", effectOverride = "galaxy",
        sourceSetId = "cloud-set", sourceCardId = "cloud-set-002",
    )

    private fun rereadWritesVisible() = entryIs(SYNC_CLEAN, 6, "confirmed cloud payload") && !outboxExists()
    private fun entryIs(state: String, revision: Long, notes: String? = null): Boolean =
        database.openHelper.writableDatabase.query(
            "SELECT syncState, remoteRevision, notes FROM collection_entries WHERE ownerUid = ? AND syncId = ?",
            arrayOf(OWNER, ENTRY),
        ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == state && cursor.getLong(1) == revision &&
            (notes == null || cursor.getString(2) == notes) }
    private fun outboxIs(state: String, reason: String?, id: String, revision: Long): Boolean =
        database.openHelper.writableDatabase.query(
            "SELECT state, lastError, mutationId, expectedRemoteRevision FROM collection_sync_outbox WHERE entryKey = ?",
            arrayOf(KEY),
        ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == state &&
            (if (cursor.isNull(1)) null else cursor.getString(1)) == reason && cursor.getString(2) == id && cursor.getLong(3) == revision }
    private fun entryExists() = database.openHelper.writableDatabase.query(
        "SELECT 1 FROM collection_entries WHERE ownerUid = ? AND syncId = ?", arrayOf(OWNER, ENTRY),
    ).use { it.moveToFirst() }
    private fun outboxExists() = database.openHelper.writableDatabase.query(
        "SELECT 1 FROM collection_sync_outbox WHERE entryKey = ?", arrayOf(KEY),
    ).use { it.moveToFirst() }

    private fun snapshot(): Map<String, List<List<String?>>> = listOf(
        "catalog_cards", "collection_entries", "collection_sync_outbox", "binders", "collection_layout",
        "collection_price_selections", "private_binders", "private_binder_placements", "private_binder_sync_outbox", "app_metadata",
    ).associateWith { table ->
        // TRUNCATE + writableDatabase keeps probes on the actual writer, including uncommitted changes.
        database.openHelper.writableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }
    }
    private suspend fun row() = checkNotNull(dao.getCollectionEntryBySyncId(OWNER, ENTRY))
    private suspend fun assertForeignUnchanged() {
        assertEquals(foreign, dao.getCollectionEntryBySyncId(OTHER, ENTRY))
        assertEquals(foreignMutation, dao.syncOutbox(foreignMutation.entryKey))
        assertEquals("unchanged", dao.metadata("collection-session-sentinel"))
    }
    private fun switchOwner(owner: String) { currentOwner = owner; generation.incrementAndGet() }
    private fun lease() = PrivatePullAuthSession(PrivatePullSessionLease(OWNER, generation.get()), generation, closed) { currentOwner }
    private fun active(): CollectionSyncSessionCommits {
        val captured = lease()
        return CollectionSyncSessionCommits(database, OWNER, captured::isCurrent)
    }
    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, got $failure", failure is T)
        return failure as T
    }

    companion object {
        private const val OWNER = "collection-session-A"
        private const val OTHER = "collection-session-B"
        private const val ENTRY = "entry"
        private const val KEY = "$OWNER:$ENTRY"
    }
}
