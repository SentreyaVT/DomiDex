package com.dominik.cardvault.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production-helper regressions for overlapping normal/sharing CollectionSyncWorker ACKs.
 * Synthetic in-memory Room only: no Worker, Firebase instance, authentication or network.
 *
 * Calls commitCollectionAck, the same production transaction used by CollectionSyncWorker,
 * with real Room DAOs/sharing-readiness queries. No copied ACK implementation remains here.
 * This covers local ACK effects, not a Firebase/Worker end-to-end exchange.
 *
 * The interleavings are ordered explicitly; no sleeps, scheduling luck or concurrent network
 * requests are required. An ACK is a simulated already-successful remote commit, not a new push.
 */
@RunWith(AndroidJUnit4::class)
class CollectionAckRaceInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val dao get() = database.cardVaultDao()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = database.close()

    @Test fun lateDuplicateAckAfterOutboxRemovalMustKeepCleanAndSharingReady() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        // Both workers captured M before either ACK; worker A finishes ACK + successful reread.
        assertTrue(commitAck(sent, 8L))
        dao.deleteSyncOutboxIfCurrent(sent.entryKey, sent.mutationId)
        assertEquals(1, sharingState())
        val completedEntry = entry()

        // Worker B now receives the same idempotent ACK for M. There is nothing left to commit.
        assertFalse(commitAck(sent, 8L))
        assertNull(dao.syncOutbox(sent.entryKey))
        assertEquals("A duplicate ACK must not manufacture PENDING without an outbox", completedEntry, entry())
        assertEquals("A completed binder must remain shareable", 1, sharingState())
    }

    @Test fun lateSameMutationAckMustNotRollBackAnAlreadyAppliedNewerRemoteSnapshot() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        assertTrue(commitAck(sent, 8L))
        // CLEAN + M is the persisted reread checkpoint. A newer server snapshot arrives first.
        val newer = entry().copy(remoteRevision = 9L, quantity = 7, notes = "synthetic remote revision 9")
        dao.updateCollectionEntry(newer)
        commitAck(sent, 8L)
        assertEquals("Late ACK N+1 cannot lower already applied revision N+2", newer, entry())
        assertEquals(sent, dao.syncOutbox(sent.entryKey))
    }

    @Test fun staleAckMustNotRollBackRevisionOfANewerPendingSuccessor() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        val successor = sent.copy(mutationId = "mutation-C", expectedRemoteRevision = 9L, createdAtEpochMillis = 3L)
        val newerLocal = entry().copy(remoteRevision = 9L, quantity = 11, notes = "synthetic edit after revision 9")
        dao.updateCollectionEntry(newerLocal)
        dao.upsertSyncOutbox(successor)
        assertFalse(commitAck(sent, 8L))
        assertEquals("An older ACK cannot lower the pending entry's confirmed revision", newerLocal, entry())
        assertEquals("Its newer successor and base revision must survive", successor, dao.syncOutbox(sent.entryKey))
    }

    @Test fun lateAckMustNotResetConflictingSuccessorToPending() = runBlocking {
        assertTerminalSuccessorPreserved(SYNC_CONFLICT)
    }

    @Test fun lateAckMustNotResetErroredSuccessorToPending() = runBlocking {
        assertTerminalSuccessorPreserved(SYNC_ERROR)
    }

    @Test fun currentPendingAckCreatesDurableCleanPlusOutboxRereadCheckpoint() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        assertTrue(commitAck(sent, 8L))
        assertEquals(SYNC_CLEAN, entry().syncState)
        assertEquals(8L, entry().remoteRevision)
        assertEquals(sent, dao.syncOutbox(sent.entryKey))
        assertTrue(shouldResumeServerReread(entry().syncState, sent.state))
        assertEquals("Do not report sharing ready before the server reread finishes", 0, sharingState())
        dao.deleteSyncOutboxIfCurrent(sent.entryKey, sent.mutationId)
        assertEquals(1, sharingState())
    }

    @Test fun currentPendingSuccessorKeepsLocalEditAndRebasesAfterOlderAck() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        val successor = sent.copy(mutationId = "mutation-B", createdAtEpochMillis = 2L)
        val localEdit = entry().copy(quantity = 4, notes = "synthetic newer local edit")
        dao.updateCollectionEntry(localEdit)
        dao.upsertSyncOutbox(successor)
        assertFalse(commitAck(sent, 8L))
        assertEquals(localEdit.copy(remoteRevision = 8L), entry())
        assertEquals(successor.copy(expectedRemoteRevision = 8L), dao.syncOutbox(sent.entryKey))
        assertEquals(0, sharingState())
    }

    @Test fun lateAckKeepsCleanSuccessorInItsOwnServerRereadCheckpoint() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        val successor = sent.copy(mutationId = "mutation-B", expectedRemoteRevision = 8L)
        val acknowledgedSuccessor = entry().copy(syncState = SYNC_CLEAN, remoteRevision = 9L, quantity = 4)
        dao.upsertSyncOutbox(successor)
        dao.updateCollectionEntry(acknowledgedSuccessor)
        assertFalse(commitAck(sent, 8L))
        assertEquals(acknowledgedSuccessor, entry())
        assertEquals(successor, dao.syncOutbox(sent.entryKey))
        assertTrue(shouldResumeServerReread(entry().syncState, successor.state))
    }

    @Test fun ownerSwitchBeforeCommitLeavesEntryAndOutboxUnchanged() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        val before = entry()
        var checks = 0
        assertFalse(commitCollectionAck(database, sent, 8L) { owner ->
            checks++
            owner == OWNER && checks == 1 // Owner changes while the transaction loads its rows.
        })
        assertEquals(2, checks)
        assertEquals(before, entry())
        assertEquals(sent, dao.syncOutbox(sent.entryKey))
    }

    @Test fun mismatchedOwnerOrSyncIdCannotUseAnotherEntriesOutboxKey() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        val before = entry()
        // Even a permissive test account predicate cannot bypass the persisted row identity.
        assertFalse(commitCollectionAck(database, sent.copy(ownerUid = "another-fixture-owner"), 8L) { true })
        assertFalse(commitCollectionAck(database, sent.copy(syncId = "another-fixture-entry"), 8L) { true })
        assertEquals(before, entry())
        assertEquals(sent, dao.syncOutbox(sent.entryKey))
    }

    @Test fun pendingSuccessorWithHigherBaseRevisionIsNeverDowngraded() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        val successor = sent.copy(mutationId = "mutation-B", expectedRemoteRevision = 10L)
        dao.upsertSyncOutbox(successor)
        assertFalse(commitAck(sent, 8L))
        assertEquals(8L, entry().remoteRevision)
        assertEquals(SYNC_PENDING, entry().syncState)
        assertEquals(successor, dao.syncOutbox(sent.entryKey))
    }

    @Test fun staleErrorForM1CannotChangePendingM2OrItsEntry() = runBlocking {
        assertStaleFailurePreservesSuccessor(SYNC_ERROR)
    }

    @Test fun staleConflictForM1CannotChangePendingM2OrItsEntry() = runBlocking {
        assertStaleFailurePreservesSuccessor(SYNC_CONFLICT)
    }

    @Test fun failureAfterSuccessfulAckCannotUndoDurableRereadCheckpoint() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        assertTrue(commitAck(sent, 8L))
        val before = entry()
        assertFalse(commitCollectionFailure(database, sent, SYNC_ERROR, "synthetic-late-error") { it == OWNER })
        assertFalse(commitCollectionFailure(database, sent, SYNC_CONFLICT, "synthetic-late-conflict") { it == OWNER })
        assertEquals(before, entry())
        assertEquals(sent, dao.syncOutbox(sent.entryKey))
    }

    @Test fun currentFailureChangesBothStatusesButNotPayloadRevisionOrMutationIdentity() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        val before = entry()
        assertTrue(commitCollectionFailure(database, sent, SYNC_ERROR, "synthetic-error") { it == OWNER })
        assertEquals(before.copy(syncState = SYNC_ERROR), entry())
        assertEquals(sent.copy(state = SYNC_ERROR, lastError = "synthetic-error"), dao.syncOutbox(sent.entryKey))
    }

    @Test fun historicalErrorNormalizationPreservesDataAndOnlyExplicitRepositoryRetryRequeuesIt() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        val terminal = entry().copy(syncState = SYNC_ERROR, notes = "synthetic historical mismatch")
        dao.updateCollectionEntry(terminal)
        assertFalse(commitAck(sent, 8L))
        assertTrue(normalizePendingOutboxForTerminalEntry(database, sent) { it == OWNER })
        assertEquals(terminal, entry())
        assertEquals(sent.copy(state = SYNC_ERROR, lastError = COLLECTION_SYNC_STATE_MISMATCH), dao.syncOutbox(sent.entryKey))
        assertTrue(dao.pendingSyncOutbox(OWNER).isEmpty())
        assertEquals(1, dao.observeSyncErrorCount(OWNER).first())
        assertEquals(0, sharingState())

        var requested = 0
        val repository = RoomCollectionRepository(database, MutableStateFlow(OWNER), onSyncNeeded = { requested++ })
        assertEquals(1, repository.retrySyncErrors())
        assertEquals(1, requested)
        assertEquals(terminal.copy(syncState = SYNC_PENDING), entry())
        val retry = requireNotNull(dao.syncOutbox(sent.entryKey))
        assertNotEquals(sent.mutationId, retry.mutationId)
        assertEquals(sent.ownerUid, retry.ownerUid)
        assertEquals(sent.syncId, retry.syncId)
        assertEquals(sent.expectedRemoteRevision, retry.expectedRemoteRevision)
        assertEquals(SYNC_PENDING, retry.state)
        assertNull(retry.lastError)
    }

    @Test fun historicalConflictNormalizationIsVisibleButNeverInventsALocalWinsRevision() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        val terminal = entry().copy(syncState = SYNC_CONFLICT)
        dao.updateCollectionEntry(terminal)
        assertTrue(normalizePendingOutboxForTerminalEntry(database, sent) { it == OWNER })
        val normalized = sent.copy(state = SYNC_CONFLICT, lastError = COLLECTION_SYNC_STATE_MISMATCH)
        assertEquals(normalized, dao.syncOutbox(sent.entryKey))
        assertEquals(listOf(normalized), dao.conflictedSyncOutbox(OWNER))
        assertEquals(1, dao.observeSyncConflictCount(OWNER).first())
        assertTrue(dao.pendingSyncOutbox(OWNER).isEmpty())
        val repository = RoomCollectionRepository(database, MutableStateFlow(OWNER))
        // Explicit cloud-wins consumes conflictedSyncOutbox with server rereads. No cloud in this test.
        assertEquals(0, repository.retryConflictsKeepingLocal())
        assertEquals(terminal, entry())
        assertEquals(normalized, dao.syncOutbox(sent.entryKey))
        assertEquals(0, sharingState())
    }

    @Test fun normalizationCannotTouchASuccessorOrDifferentOwner() = runBlocking {
        val sent = seedPendingCardInAcknowledgedBinder()
        val terminal = entry().copy(syncState = SYNC_ERROR)
        val successor = sent.copy(mutationId = "mutation-B")
        dao.updateCollectionEntry(terminal)
        dao.upsertSyncOutbox(successor)
        assertFalse(normalizePendingOutboxForTerminalEntry(database, sent) { it == OWNER })
        assertFalse(normalizePendingOutboxForTerminalEntry(database, successor) { false })
        assertEquals(terminal, entry())
        assertEquals(successor, dao.syncOutbox(sent.entryKey))
    }

    private suspend fun assertStaleFailurePreservesSuccessor(failureState: String) {
        val sent = seedPendingCardInAcknowledgedBinder()
        val successor = sent.copy(mutationId = "mutation-B", createdAtEpochMillis = 2L)
        val nextEntry = entry().copy(quantity = 6, notes = "synthetic newer pending mutation")
        dao.upsertSyncOutbox(successor)
        dao.updateCollectionEntry(nextEntry)
        assertFalse(commitCollectionFailure(database, sent, failureState, "synthetic-stale-result") { it == OWNER })
        assertEquals(successor, dao.syncOutbox(sent.entryKey))
        assertEquals(nextEntry, entry())
    }

    private suspend fun assertTerminalSuccessorPreserved(terminalState: String) {
        val sent = seedPendingCardInAcknowledgedBinder()
        val terminal = sent.copy(mutationId = "mutation-B", state = terminalState, lastError = "synthetic-$terminalState")
        val localEdit = entry().copy(syncState = terminalState, quantity = 5, notes = "synthetic terminal successor")
        dao.updateCollectionEntry(localEdit)
        dao.upsertSyncOutbox(terminal)
        assertFalse(commitAck(sent, 8L))
        assertEquals("A stale ACK may not turn a terminal successor into an orphan pending entry", localEdit, entry())
        assertEquals("A late ACK must not rewrite the successor's explicit conflict/error decision", terminal, dao.syncOutbox(sent.entryKey))
    }

    private suspend fun commitAck(mutation: CollectionSyncOutboxEntity, revision: Long): Boolean =
        commitCollectionAck(database, mutation, revision) { it == OWNER }

    private suspend fun seedPendingCardInAcknowledgedBinder(): CollectionSyncOutboxEntity {
        dao.insertCatalogCard(CatalogCardEntity(
            "synthetic-card", "POKEMON", "synthetic-set", "Synthetic set", "Synthetic card", "1/1",
            "GERMAN", "Normal", "Standard", 1L,
        ))
        val row = CollectionEntryEntity(
            cardId = "synthetic-card", quantity = 1, condition = "Near Mint", acquisitionPriceMinor = null,
            acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "synthetic original",
            frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
            ownerUid = OWNER, syncId = ENTRY, syncState = SYNC_PENDING, remoteRevision = 7L,
        )
        val entryId = dao.insertCollectionEntry(row)
        dao.insertPrivateBinder(PrivateBinderEntity(
            id = BINDER, ownerUid = OWNER, name = "Synthetic private binder", createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L, syncState = SYNC_CLEAN, remoteRevision = 1L,
        ))
        dao.insertPrivateBinderPlacement(PrivateBinderPlacementEntity(
            id = "synthetic-placement", ownerUid = OWNER, binderId = BINDER, collectionEntryId = entryId,
            slotPosition = 0, createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
            syncState = SYNC_CLEAN, remoteRevision = 1L,
        ))
        return CollectionSyncOutboxEntity(
            entryKey = "$OWNER:$ENTRY", mutationId = "mutation-A", ownerUid = OWNER, syncId = ENTRY,
            operation = "UPSERT", expectedRemoteRevision = 7L, state = SYNC_PENDING, createdAtEpochMillis = 1L,
        ).also { dao.upsertSyncOutbox(it) }
    }

    private suspend fun entry() = requireNotNull(dao.getCollectionEntryBySyncId(OWNER, ENTRY))
    private suspend fun sharingState() = dao.observePrivateBinderSharingSyncState(OWNER, BINDER).first()

    companion object {
        private const val OWNER = "ack-race-fixture-owner"
        private const val ENTRY = "synthetic-entry"
        private const val BINDER = "synthetic-binder"
    }
}
