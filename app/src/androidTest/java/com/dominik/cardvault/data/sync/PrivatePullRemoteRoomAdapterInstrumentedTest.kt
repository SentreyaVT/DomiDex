package com.dominik.cardvault.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual production apply + Room + checkpoint integration; no Firebase, network or worker wiring. */
@RunWith(AndroidJUnit4::class)
class PrivatePullRemoteRoomAdapterInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val dao get() = database.cardVaultDao()
    private var currentLease = PrivatePullSessionLease(OWNER, 1)

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
    }

    @After fun teardown() = database.close()

    @Test fun allFourRecordKindsCommitTheirActualRowsAndCheckpoint() = runBlocking {
        val entry = seedOwner()
        val adapter = adapter()
        for (group in PrivatePullCollection.entries) {
            val expected = begin(adapter, group)
            val document = document(group)
            assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitPage(expected, currentLease, page(expected, document)))
            assertNotNull(row(group, document.documentId))
            assertEquals(3L, revision(group, document.documentId))
            assertEquals(expected.state.revision + 1, adapter.checkpoint(expected.state.scope).state.revision)
            assertTrue(adapter.deferredPage(expected.state.scope).isEmpty())
        }
        assertEquals(entry, dao.getCollectionEntryBySyncId(OWNER, ENTRY))
        assertEquals(false, dao.collectionLayout(entry.id, OWNER)?.visibleInSetBinder)
        assertEquals(0.5f, dao.privateBinderPlacement(PLACEMENT, OWNER)?.holoIntensity)
        assertTrue(requireNotNull(dao.priceSelection(OWNER, ENTRY)).cleared)
    }

    @Test fun equalAndOlderRevisionsLeaveAllFourBusinessRowsUnchanged() = runBlocking {
        seedOwner()
        val adapter = adapter()
        for (group in PrivatePullCollection.entries) {
            commit(adapter, group, document(group))
            val before = row(group, id(group))
            for (remoteRevision in listOf(3L, 2L)) {
                commit(adapter, group, document(group, extra = mapOf("revision" to remoteRevision, "updatedAtClient" to 999L)))
                assertEquals(before, row(group, id(group)))
            }
        }
    }

    @Test fun allFourOwnOutboxesProduceDurableMarkersWithoutAnAckOrPayloadChange() = runBlocking {
        seedOwner()
        val adapter = adapter()
        for (group in PrivatePullCollection.entries) {
            val mutation = outbox(group, id(group))
            dao.upsertPrivateBinderSyncOutbox(mutation)
            val before = row(group, id(group))
            val expected = begin(adapter, group)
            assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitPage(expected, currentLease, page(expected, document(group))))
            assertEquals(before, row(group, id(group)))
            assertEquals(mutation, dao.privateBinderSyncOutbox(mutation.entryKey))
            val marker = adapter.deferredPage(expected.state.scope).single()
            assertEquals(PrivatePullDeferReason.LOCAL_MUTATION, marker.reason)
            assertNull(marker.dependencyId)
            assertEquals(id(group), marker.documentId)
            assertEquals(expected.state.revision + 1, marker.version)
            assertEquals(expected.state.epoch, marker.epoch)
        }
    }

    @Test fun missingBinderAndCollectionParentsHaveTypedMarkersAndNoInventedRows() = runBlocking {
        val adapter = adapter()
        for (group in listOf(PrivatePullCollection.PLACEMENTS, PrivatePullCollection.LAYOUTS, PrivatePullCollection.PRICE_SELECTIONS)) {
            commit(adapter, group, document(group))
            val marker = adapter.deferredPage(scope(group)).single()
            assertEquals(if (group == PrivatePullCollection.PLACEMENTS) PrivatePullDeferReason.BINDER_DEPENDENCY
                else PrivatePullDeferReason.COLLECTION_DEPENDENCY, marker.reason)
            assertEquals(if (group == PrivatePullCollection.PLACEMENTS) BINDER else ENTRY, marker.dependencyId)
            assertNull(row(group, id(group)))
        }
        assertNull(dao.privateBinder(BINDER, OWNER))
        assertNull(dao.getCollectionEntryBySyncId(OWNER, ENTRY))
    }

    @Test fun placementRecheckChangesItsMissingParentMarkerThenAppliesWithoutMovingCursor() = runBlocking {
        val adapter = adapter()
        val group = PrivatePullCollection.PLACEMENTS
        val document = document(group)
        commit(adapter, group, document)
        val first = adapter.deferredPage(scope(group)).single()
        dao.upsertPrivateBinder(binder())
        val expected = adapter.checkpoint(scope(group))
        assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitRecheck(expected, currentLease, first, document, TRUSTED))
        val second = adapter.deferredPage(scope(group)).single()
        assertEquals(PrivatePullDeferReason.COLLECTION_DEPENDENCY, second.reason)
        assertEquals(ENTRY, second.dependencyId)
        assertEquals(expected.state.phase, adapter.checkpoint(scope(group)).state.phase)
        newEntry(ENTRY)
        val beforeApply = adapter.checkpoint(scope(group))
        assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitRecheck(beforeApply, currentLease, second, document, TRUSTED))
        assertNotNull(dao.privateBinderPlacement(PLACEMENT, OWNER))
        assertTrue(adapter.deferredPage(scope(group)).isEmpty())
        assertEquals(beforeApply.state.phase, adapter.checkpoint(scope(group)).state.phase)
    }

    @Test fun outboxAckAloneKeepsMarkerAndFreshRecheckAppliesEvenWithoutNewTimestamp() = runBlocking {
        seedOwner()
        val adapter = adapter()
        val group = PrivatePullCollection.BINDERS
        val mutation = outbox(group, BINDER)
        dao.upsertPrivateBinderSyncOutbox(mutation)
        commit(adapter, group, document(group))
        val marker = adapter.deferredPage(scope(group)).single()
        dao.deletePrivateBinderSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
        assertEquals(listOf(marker), adapter.deferredPage(scope(group)))
        val expected = adapter.checkpoint(scope(group))
        val fresh = document(group, extra = mapOf("revision" to 4L, "name" to "latest server body"))
        assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitRecheck(expected, currentLease, marker, fresh, TRUSTED))
        assertEquals("latest server body", dao.privateBinder(BINDER, OWNER)?.name)
        assertEquals(4L, dao.privateBinder(BINDER, OWNER)?.remoteRevision)
        assertTrue(adapter.deferredPage(scope(group)).isEmpty())
        assertEquals(expected.state.phase, adapter.checkpoint(scope(group)).state.phase)
    }

    @Test fun terminalCollectionStatesDeferAllChildrenUntilExplicitResolution() = runBlocking {
        val entry = seedOwner()
        val adapter = adapter()
        val groups = listOf(PrivatePullCollection.PLACEMENTS, PrivatePullCollection.LAYOUTS, PrivatePullCollection.PRICE_SELECTIONS)
        for (state in listOf(SYNC_ERROR, SYNC_CONFLICT)) {
            dao.updateCollectionEntry(entry.copy(syncState = state))
            for (group in groups) {
                commit(adapter, group, document(group))
                assertEquals(PrivatePullDeferReason.LOCAL_MUTATION, adapter.deferredPage(scope(group)).single().reason)
                assertNull(row(group, id(group)))
            }
        }
        dao.updateCollectionEntry(entry)
        for (group in groups) {
            val expected = adapter.checkpoint(scope(group))
            val marker = adapter.deferredPage(scope(group)).single()
            assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitRecheck(expected, currentLease, marker, document(group), TRUSTED))
            assertNotNull(row(group, id(group)))
            assertTrue(adapter.deferredPage(scope(group)).isEmpty())
        }
    }

    @Test fun pendingDeletedParentIsDeferredButConfirmedDeletedParentCompletesWithoutRevival() = runBlocking {
        val entry = seedOwner()
        val adapter = adapter()
        val group = PrivatePullCollection.LAYOUTS
        dao.updateCollectionEntry(entry.copy(syncState = SYNC_PENDING, deletedAtEpochMillis = 50))
        commit(adapter, group, document(group))
        val marker = adapter.deferredPage(scope(group)).single()
        assertEquals(PrivatePullDeferReason.LOCAL_MUTATION, marker.reason)
        val deleted = entry.copy(syncState = SYNC_CLEAN, deletedAtEpochMillis = 50)
        dao.updateCollectionEntry(deleted)
        val expected = adapter.checkpoint(scope(group))
        assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitRecheck(expected, currentLease, marker, document(group), TRUSTED))
        assertTrue(adapter.deferredPage(scope(group)).isEmpty())
        assertNull(dao.collectionLayout(entry.id, OWNER))
        assertEquals(deleted, dao.getCollectionEntryBySyncId(OWNER, ENTRY))
    }

    @Test fun livePendingParentDoesNotAcquireAnUnnecessaryMarker() = runBlocking {
        val entry = seedOwner()
        dao.updateCollectionEntry(entry.copy(syncState = SYNC_PENDING))
        val adapter = adapter()
        for (group in listOf(PrivatePullCollection.PLACEMENTS, PrivatePullCollection.LAYOUTS, PrivatePullCollection.PRICE_SELECTIONS)) {
            commit(adapter, group, document(group))
            assertNotNull(row(group, id(group)))
            assertTrue(adapter.deferredPage(scope(group)).isEmpty())
        }
    }

    @Test fun completedMembershipAndSlotResolutionStillCommitTheirGeneratedOutboxes() = runBlocking {
        val entry = seedOwner()
        dao.upsertPrivateBinderPlacement(placement("z-loser", entry.id))
        val second = newEntry("second")
        val adapter = adapter()
        val group = PrivatePullCollection.PLACEMENTS
        val expected = begin(adapter, group)
        val winner = document(group, "a-winner")
        val moved = document(group, "b-second", mapOf("entrySyncId" to second.syncId, "slotPosition" to 0L))
        assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitPage(expected, currentLease, page(expected, winner, moved)))
        assertNotNull(dao.privateBinderPlacement("z-loser", OWNER)?.deletedAtEpochMillis)
        assertEquals(PRIVATE_SYNC_DELETE, dao.privateBinderSyncOutbox(key(group, "z-loser"))?.operation)
        assertEquals(1L, dao.privateBinderPlacement("b-second", OWNER)?.slotPosition)
        assertEquals(PRIVATE_SYNC_UPSERT, dao.privateBinderSyncOutbox(key(group, "b-second"))?.operation)
        assertEquals(SYNC_PENDING, dao.privateBinderPlacement("b-second", OWNER)?.syncState)
        assertTrue(adapter.deferredPage(scope(group)).isEmpty())
        assertEquals(expected.state.revision + 1, adapter.checkpoint(scope(group)).state.revision)
    }

    @Test fun pendingOtherMembershipBecomesLocalMarkerWithoutReplacingItsMutation() = runBlocking {
        val entry = seedOwner()
        val local = placement("z-pending", entry.id).copy(syncState = SYNC_PENDING)
        dao.upsertPrivateBinderPlacement(local)
        val mutation = outbox(PrivatePullCollection.PLACEMENTS, local.id)
        dao.upsertPrivateBinderSyncOutbox(mutation)
        val adapter = adapter()
        commit(adapter, PrivatePullCollection.PLACEMENTS, document(PrivatePullCollection.PLACEMENTS, "a-remote"))
        assertEquals(local, dao.privateBinderPlacement(local.id, OWNER))
        assertEquals(mutation, dao.privateBinderSyncOutbox(mutation.entryKey))
        assertNull(dao.privateBinderPlacement("a-remote", OWNER))
        assertEquals(PrivatePullDeferReason.LOCAL_MUTATION, adapter.deferredPage(scope(PrivatePullCollection.PLACEMENTS)).single().reason)
    }

    @Test fun unknownTombstonesCommitReceiptsAndPreventOlderLiveResurrection() = runBlocking {
        val adapter = adapter()
        for (group in listOf(PrivatePullCollection.BINDERS, PrivatePullCollection.PLACEMENTS)) {
            val id = "unknown"
            commit(adapter, group, document(group, id, mapOf("deleted" to true, "revision" to 9L)))
            val receiptKey = privateRemoteTombstoneReceiptKey(OWNER, type(group), id)
            assertEquals("1|9", dao.metadata(receiptKey))
            assertNull(row(group, id))
            for (remoteRevision in listOf(8L, 9L)) {
                commit(adapter, group, document(group, id, mapOf("revision" to remoteRevision)))
                assertNull(row(group, id))
                assertEquals("1|9", dao.metadata(receiptKey))
                assertTrue(adapter.deferredPage(scope(group)).isEmpty())
            }
        }
    }

    @Test fun priceClearPreservesRevisionAndIdentityWhileLocalConflictRemainsUntouched() = runBlocking {
        val entry = seedOwner()
        val adapter = adapter()
        val group = PrivatePullCollection.PRICE_SELECTIONS
        commit(adapter, group, document(group, extra = mapOf("revision" to 7L)))
        val cleared = requireNotNull(dao.priceSelection(OWNER, ENTRY))
        assertTrue(cleared.cleared)
        assertEquals(7L, cleared.remoteRevision)
        commit(adapter, group, document(group, extra = mapOf("revision" to 6L)))
        assertEquals(cleared, dao.priceSelection(OWNER, ENTRY))
        val local = cleared.copy(syncState = SYNC_CONFLICT)
        dao.upsertPriceSelection(local)
        val mutation = outbox(group, ENTRY).copy(expectedRemoteRevision = 7, lastError = "synthetic retained conflict")
        dao.upsertPrivateBinderSyncOutbox(mutation)
        commit(adapter, group, document(group, extra = mapOf("revision" to 9L)))
        assertEquals(local, dao.priceSelection(OWNER, ENTRY))
        assertEquals(mutation, dao.privateBinderSyncOutbox(mutation.entryKey))
        assertEquals(PrivatePullDeferReason.LOCAL_MUTATION, adapter.deferredPage(scope(group)).single().reason)
        assertEquals(entry, dao.getCollectionEntryBySyncId(OWNER, ENTRY))
    }

    @Test fun selectedPriceRetainsItsOriginalFieldsWithoutRewritingParentIdentity() = runBlocking {
        val entry = seedOwner()
        val adapter = adapter()
        val group = PrivatePullCollection.PRICE_SELECTIONS
        val values = mapOf("cleared" to false, "sourceSetId" to "synthetic-set", "sourceCardId" to "synthetic-set-1",
            "collectorNumber" to "1", "finish" to "Normal", "variant" to "Standard", "language" to "GERMAN",
            "productId" to 42L, "metric" to "normal_low", "amountMinor" to 999L, "currency" to "EUR",
            "sourceAt" to 10L, "selectedAt" to 15L)
        commit(adapter, group, document(group, extra = values))
        val selection = requireNotNull(dao.priceSelection(OWNER, ENTRY))
        assertFalse(selection.cleared)
        assertEquals(entry.id, selection.collectionEntryId)
        assertEquals(42L, selection.productId)
        assertEquals(999L, selection.amountMinor)
        assertEquals("normal_low", selection.metric)
        assertEquals(10L, selection.sourceAt)
        assertEquals(15L, selection.selectedAt)
        assertEquals("synthetic-set-1", selection.sourceCardId)
        assertEquals(entry, dao.getCollectionEntryBySyncId(OWNER, ENTRY))
        assertTrue(adapter.deferredPage(scope(group)).isEmpty())
    }

    @Test fun trustedInvalidPayloadCompletesWithoutApplyingOrDeletingAnOutbox() = runBlocking {
        seedOwner()
        val adapter = adapter()
        val group = PrivatePullCollection.BINDERS
        val mutation = outbox(group, BINDER)
        dao.upsertPrivateBinderSyncOutbox(mutation)
        commit(adapter, group, document(group))
        val marker = adapter.deferredPage(scope(group)).single()
        val before = dao.privateBinder(BINDER, OWNER)
        val expected = adapter.checkpoint(scope(group))
        val invalid = document(group, extra = mapOf("revision" to 2.5))
        assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitRecheck(expected, currentLease, marker, invalid, TRUSTED))
        assertEquals(before, dao.privateBinder(BINDER, OWNER))
        assertEquals(mutation, dao.privateBinderSyncOutbox(mutation.entryKey))
        assertTrue(adapter.deferredPage(scope(group)).isEmpty())
    }

    @Test fun untrustedOrPendingPageCannotApplyEvenAnInvalidTombstoneOrAdvanceCheckpoint() = runBlocking {
        val adapter = adapter()
        val group = PrivatePullCollection.BINDERS
        val expected = begin(adapter, group)
        val invalid = document(group, "invalid", mapOf("deleted" to true, "revision" to 0L))
        val cases = listOf(
            page(expected, invalid).copy(trust = PrivatePullReadTrust.UNTRUSTED),
            page(expected, invalid.copy(hasPendingWrites = true)),
        )
        for (input in cases) {
            assertTrue(runCatching { adapter.commitPage(expected, currentLease, input) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(expected, adapter.checkpoint(scope(group)))
            assertNull(dao.privateBinder("invalid", OWNER))
            assertNull(dao.metadata(privateRemoteTombstoneReceiptKey(OWNER, type(group), "invalid")))
            assertTrue(adapter.deferredPage(scope(group)).isEmpty())
        }
    }

    @Test fun untrustedOrPendingRecheckKeepsTheExistingMarkerAndPayload() = runBlocking {
        val adapter = adapter()
        val group = PrivatePullCollection.LAYOUTS
        commit(adapter, group, document(group))
        val marker = adapter.deferredPage(scope(group)).single()
        val expected = adapter.checkpoint(scope(group))
        for ((trust, pending) in listOf(PrivatePullReadTrust.UNTRUSTED to false, TRUSTED to true)) {
            assertTrue(runCatching { adapter.commitRecheck(expected, currentLease, marker,
                document(group).copy(hasPendingWrites = pending), trust) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(listOf(marker), adapter.deferredPage(scope(group)))
            assertEquals(expected, adapter.checkpoint(scope(group)))
        }
    }

    @Test fun malformedDeltaPageIsRejectedBeforeAnyEarlierValidBusinessRow() = runBlocking {
        val adapter = adapter()
        val group = PrivatePullCollection.BINDERS
        val bootstrap = begin(adapter, group)
        assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitPage(bootstrap, currentLease, page(bootstrap)))
        val expected = begin(adapter, group)
        assertTrue(expected.state.phase is PrivatePullPhase.Delta)
        val valid = document(group, "a-good")
        val missingStamp = document(group, "b-missing").copy(stamp = null)
        assertTrue(runCatching { adapter.commitPage(expected, currentLease, page(expected, valid, missingStamp)) }
            .exceptionOrNull() is IllegalArgumentException)
        assertNull(dao.privateBinder("a-good", OWNER))
        assertEquals(expected, adapter.checkpoint(scope(group)))
    }

    @Test fun confirmedBootstrapStillAcceptsLegacyMissingTimestamp() = runBlocking {
        val adapter = adapter()
        val group = PrivatePullCollection.BINDERS
        val expected = begin(adapter, group)
        assertEquals(PrivatePullCommitResult.COMMITTED,
            adapter.commitPage(expected, currentLease, page(expected, document(group).copy(stamp = null))))
        assertNotNull(dao.privateBinder(BINDER, OWNER))
        assertEquals(PrivatePullPhase.Idle(STAMP), adapter.checkpoint(scope(group)).state.phase)
    }

    @Test fun identicalDocumentIdsAreOwnerScopedAcrossAllFourTypes() = runBlocking {
        seedOwner()
        seedOwner(OTHER_OWNER)
        val adapter = adapter()
        for (owner in listOf(OWNER, OTHER_OWNER)) {
            currentLease = PrivatePullSessionLease(owner, currentLease.generation + 1)
            for (group in PrivatePullCollection.entries) commit(adapter, group, document(group, owner = owner), owner)
        }
        for (group in PrivatePullCollection.entries) {
            assertNotNull(row(group, id(group), OWNER))
            assertNotNull(row(group, id(group), OTHER_OWNER))
            assertEquals(3L, revision(group, id(group), OWNER))
            assertEquals(3L, revision(group, id(group), OTHER_OWNER))
            assertNotEquals(adapter.checkpoint(scope(group, OWNER)).raw, adapter.checkpoint(scope(group, OTHER_OWNER)).raw)
        }
    }

    @Test fun mismatchedPayloadOwnerCannotWriteEitherOwnersBusinessRows() = runBlocking {
        seedOwner()
        seedOwner(OTHER_OWNER)
        val adapter = adapter()
        val beforeA = dao.privateBinder(BINDER, OWNER)
        val beforeB = dao.privateBinder(BINDER, OTHER_OWNER)
        commit(adapter, PrivatePullCollection.BINDERS, document(PrivatePullCollection.BINDERS, owner = OTHER_OWNER))
        assertEquals(beforeA, dao.privateBinder(BINDER, OWNER))
        assertEquals(beforeB, dao.privateBinder(BINDER, OTHER_OWNER))
        assertNull(dao.metadata(PrivatePullRoomStore.checkpointKey(scope(PrivatePullCollection.BINDERS, OTHER_OWNER))))
    }

    @Test fun wrongLeaseAndObservedAccountRoundTripCannotApplyCapturedPage() = runBlocking {
        val adapter = adapter()
        val group = PrivatePullCollection.BINDERS
        val expected = begin(adapter, group)
        val input = page(expected, document(group))
        val captured = currentLease
        currentLease = PrivatePullSessionLease(OTHER_OWNER, 2)
        currentLease = PrivatePullSessionLease(OWNER, 3)
        assertEquals(PrivatePullCommitResult.STALE_SESSION, adapter.commitPage(expected, captured, input))
        assertTrue(runCatching { adapter.commitPage(expected, PrivatePullSessionLease(OTHER_OWNER, 4), input) }
            .exceptionOrNull() is IllegalArgumentException)
        assertNull(dao.privateBinder(BINDER, OWNER))
        assertEquals(expected, adapter.checkpoint(scope(group)))
    }

    @Test fun staleCheckpointAndReplacementMarkerCannotApplyLaterPayloads() = runBlocking {
        val adapter = adapter()
        val group = PrivatePullCollection.LAYOUTS
        val old = begin(adapter, group)
        val input = page(old, document(group))
        assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitPage(old, currentLease, input))
        val marker = adapter.deferredPage(scope(group)).single()
        val expected = adapter.checkpoint(scope(group))
        seedOwner()
        assertEquals(PrivatePullCommitResult.STALE_CHECKPOINT, adapter.commitPage(old, currentLease, input))
        val replacement = marker.copy(version = marker.version + 1)
        dao.putMetadata(AppMetadataEntity(PrivatePullRoomStore.markerKey(scope(group), ENTRY), PrivatePullCodec.encodeMarker(replacement)))
        assertEquals(PrivatePullCommitResult.STALE_MARKER, adapter.commitRecheck(expected, currentLease, marker, document(group), TRUSTED))
        assertNull(row(group, ENTRY))
        assertEquals(listOf(replacement), adapter.deferredPage(scope(group)))
        assertEquals(expected, adapter.checkpoint(scope(group)))
    }

    @Test fun exceptionAfterActualRowsOutboxReceiptAndMarkerRollsBackTheWholePage() = runBlocking {
        assertAtomicFailure(afterCheckpoint = false, cancellation = false)
    }

    @Test fun cancellationAfterActualRowsOutboxReceiptAndMarkerPropagatesAndRollsBack() = runBlocking {
        assertAtomicFailure(afterCheckpoint = false, cancellation = true)
    }

    @Test fun accountRoundTripAfterActualRowsRollsBackTheWholePage() = runBlocking {
        assertAtomicFailure(afterCheckpoint = false, accountChange = true)
    }

    @Test fun accountRoundTripAfterCheckpointWriteStillRollsBackTheWholePage() = runBlocking {
        assertAtomicFailure(afterCheckpoint = true, accountChange = true)
    }

    @Test fun deferredCapacityFailureRollsBackEarlierActualPayloadAndAllNewMarkers() = runBlocking {
        val adapter = adapter(maximumDeferred = 2)
        val group = PrivatePullCollection.BINDERS
        val expected = begin(adapter, group)
        val mutations = listOf("b-pending", "c-pending", "d-pending").map { outbox(group, it) }
        mutations.forEach { dao.upsertPrivateBinderSyncOutbox(it) }
        val docs = listOf(document(group, "a-good")) + mutations.map { document(group, it.recordId) }
        val failure = runCatching { adapter.commitPage(expected, currentLease, page(expected, *docs.toTypedArray())) }.exceptionOrNull()
        assertTrue(failure is PrivatePullDeferredLimitException)
        assertNull(dao.privateBinder("a-good", OWNER))
        assertTrue(adapter.deferredPage(scope(group)).isEmpty())
        assertEquals(expected, adapter.checkpoint(scope(group)))
        mutations.forEach { assertEquals(it, dao.privateBinderSyncOutbox(it.entryKey)) }
    }

    @Test fun corruptReceiptRollsBackEarlierActualRowWithoutDiscardingEvidence() = runBlocking {
        val adapter = adapter()
        val group = PrivatePullCollection.BINDERS
        val expected = begin(adapter, group)
        val receiptKey = privateRemoteTombstoneReceiptKey(OWNER, type(group), "z-corrupt")
        dao.putMetadata(AppMetadataEntity(receiptKey, "broken receipt"))
        val failure = runCatching { adapter.commitPage(expected, currentLease,
            page(expected, document(group, "a-good"), document(group, "z-corrupt"))) }.exceptionOrNull()
        assertTrue(failure is PrivateRemoteReceiptException)
        assertNull(dao.privateBinder("a-good", OWNER))
        assertEquals("broken receipt", dao.metadata(receiptKey))
        assertEquals(expected, adapter.checkpoint(scope(group)))
    }

    @Test fun databaseReopenRetainsActualPayloadOutboxReceiptMarkerAndExactResumeState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "private-pull-remote-fixture-${UUID.randomUUID()}.db"
        database.close()
        database = Room.databaseBuilder(context, CardVaultDatabase::class.java, name).build()
        try {
            val group = PrivatePullCollection.BINDERS
            val adapter = adapter()
            val expected = begin(adapter, group)
            val mutation = outbox(group, "a-pending")
            dao.upsertPrivateBinderSyncOutbox(mutation)
            val input = PrivatePullPage(PrivatePullPlan.BootstrapPage(expected.state, 3), listOf(
                document(group, "a-pending"), document(group, "b-good"),
                document(group, "c-deleted", mapOf("deleted" to true, "revision" to 9L)),
            ), TRUSTED)
            assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitPage(expected, currentLease, input))
            val persisted = adapter.checkpoint(scope(group))
            val marker = adapter.deferredPage(scope(group)).single()
            assertEquals(PrivatePullPhase.Bootstrap(STAMP, "c-deleted"), persisted.state.phase)
            database.close()
            database = Room.databaseBuilder(context, CardVaultDatabase::class.java, name).build()
            val resumed = this@PrivatePullRemoteRoomAdapterInstrumentedTest.adapter()
            assertEquals(persisted, resumed.checkpoint(scope(group)))
            assertEquals(listOf(marker), resumed.deferredPage(scope(group)))
            assertNotNull(dao.privateBinder("b-good", OWNER))
            assertEquals(mutation, dao.privateBinderSyncOutbox(mutation.entryKey))
            assertEquals("1|9", dao.metadata(privateRemoteTombstoneReceiptKey(OWNER, type(group), "c-deleted")))
            dao.deletePrivateBinderSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
            assertEquals(listOf(marker), resumed.deferredPage(scope(group)))
            assertEquals(PrivatePullCommitResult.COMMITTED, resumed.commitRecheck(
                persisted, currentLease, marker, document(group, "a-pending"), TRUSTED))
            assertNotNull(dao.privateBinder("a-pending", OWNER))
            assertTrue(resumed.deferredPage(scope(group)).isEmpty())
            assertEquals(persisted.state.phase, resumed.checkpoint(scope(group)).state.phase)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun mutablePageListAndFlatPayloadAreCopiedBeforeEnteringTheStore() = runBlocking {
        val observer = adapter()
        val group = PrivatePullCollection.BINDERS
        val expected = begin(observer, group)
        val payload = record(group, BINDER).toMutableMap()
        val docs = mutableListOf(PrivatePullDocument(BINDER, STAMP, payload as Map<String, Any?>))
        var checked = false
        val adapter = PrivatePullRemoteRoomAdapter(database, { lease ->
            if (!checked) {
                checked = true
                payload["name"] = "changed after adapter entry"
                payload["ownerUid"] = OTHER_OWNER
                docs.clear()
            }
            lease == currentLease
        })
        val input = PrivatePullPage(privatePullPlan(expected.state, PRIVATE_PULL_RUN_ROWS) as PrivatePullPlan.Page, docs, TRUSTED)
        assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitPage(expected, currentLease, input))
        assertTrue(checked)
        assertEquals("remote binder", dao.privateBinder(BINDER, OWNER)?.name)
        assertNull(dao.privateBinder(BINDER, OTHER_OWNER))
    }

    @Test fun mutableRecheckPayloadIsCopiedBeforeEnteringTheStore() = runBlocking {
        val observer = adapter()
        val group = PrivatePullCollection.LAYOUTS
        commit(observer, group, document(group))
        seedOwner()
        val expected = observer.checkpoint(scope(group))
        val marker = observer.deferredPage(scope(group)).single()
        val payload = record(group, ENTRY).toMutableMap()
        var checked = false
        val adapter = PrivatePullRemoteRoomAdapter(database, { lease ->
            if (!checked) { checked = true; payload["visibleInSetBinder"] = true }
            lease == currentLease
        })
        assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitRecheck(expected, currentLease, marker,
            PrivatePullDocument(ENTRY, STAMP, payload), TRUSTED))
        val entry = requireNotNull(dao.getCollectionEntryBySyncId(OWNER, ENTRY))
        assertEquals(false, dao.collectionLayout(entry.id, OWNER)?.visibleInSetBinder)
        assertTrue(observer.deferredPage(scope(group)).isEmpty())
    }

    private suspend fun assertAtomicFailure(afterCheckpoint: Boolean, cancellation: Boolean = false, accountChange: Boolean = false) {
        val entry = seedOwner()
        val existing = placement("z-loser", entry.id)
        dao.upsertPrivateBinderPlacement(existing)
        val group = PrivatePullCollection.PLACEMENTS
        val observer = adapter()
        val expected = begin(observer, group)
        val captured = currentLease
        val docs = page(expected,
            document(group, "a-winner"),
            document(group, "b-deleted", mapOf("deleted" to true, "revision" to 9L)),
            document(group, "c-dependency", mapOf("binderId" to "missing-parent")),
        )
        val receiptKey = privateRemoteTombstoneReceiptKey(OWNER, type(group), "b-deleted")
        var checks = 0
        var insideCounts: List<Long>? = null
        var insideCheckpoint: String? = null
        val adapter = PrivatePullRemoteRoomAdapter(database, { lease ->
            checks++
            if (checks == (if (afterCheckpoint) 4 else 3)) {
                // Both guards execute on the transaction thread, after real production writes.
                val sql = database.openHelper.readableDatabase
                insideCounts = listOf(
                    "SELECT COUNT(*) FROM private_binder_placements WHERE id = 'a-winner'",
                    "SELECT COUNT(*) FROM private_binder_sync_outbox WHERE recordId = 'z-loser'",
                    "SELECT COUNT(*) FROM app_metadata WHERE `key` = '$receiptKey'",
                    "SELECT COUNT(*) FROM app_metadata WHERE `key` = '${PrivatePullRoomStore.markerKey(scope(group), "c-dependency")}'",
                ).map { query -> sql.query(query).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) } }
                sql.query("SELECT value FROM app_metadata WHERE `key` = ?", arrayOf(PrivatePullRoomStore.checkpointKey(scope(group))))
                    .use { cursor -> check(cursor.moveToFirst()); insideCheckpoint = cursor.getString(0) }
                if (accountChange) {
                    currentLease = PrivatePullSessionLease(OTHER_OWNER, captured.generation + 1)
                    currentLease = captured.copy(generation = captured.generation + 2)
                } else if (cancellation) throw CancellationException("synthetic cancellation at real Room boundary")
                else throw IllegalStateException("synthetic failure at real Room boundary")
            }
            lease == currentLease
        })
        val result = runCatching { adapter.commitPage(expected, captured, docs) }
        if (accountChange) assertEquals(PrivatePullCommitResult.STALE_SESSION, result.getOrThrow())
        else if (cancellation) assertTrue(result.exceptionOrNull() is CancellationException)
        else assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf(1L, 1L, 1L, 1L), insideCounts)
        if (afterCheckpoint) assertNotEquals(expected.raw, insideCheckpoint) else assertEquals(expected.raw, insideCheckpoint)
        assertNull(dao.privateBinderPlacement("a-winner", OWNER))
        assertEquals(existing, dao.privateBinderPlacement("z-loser", OWNER))
        assertNull(dao.privateBinderSyncOutbox(key(group, "z-loser")))
        assertNull(dao.metadata(receiptKey))
        assertTrue(observer.deferredPage(scope(group)).isEmpty())
        assertEquals(expected, observer.checkpoint(scope(group)))
        // A new, valid session can repeat exactly the rolled-back page without data loss.
        assertEquals(PrivatePullCommitResult.COMMITTED, observer.commitPage(expected, currentLease, docs))
        assertNotNull(dao.privateBinderPlacement("a-winner", OWNER))
        assertNotNull(dao.privateBinderSyncOutbox(key(group, "z-loser")))
        assertEquals("1|9", dao.metadata(receiptKey))
        assertEquals("c-dependency", observer.deferredPage(scope(group)).single().documentId)
    }

    private fun adapter(maximumDeferred: Int = 2_000) = PrivatePullRemoteRoomAdapter(database, { it == currentLease }, maximumDeferred)
    private fun scope(group: PrivatePullCollection, owner: String = OWNER) = PrivatePullScope(owner, group)

    private suspend fun begin(adapter: PrivatePullRemoteRoomAdapter, group: PrivatePullCollection, owner: String = OWNER): PrivatePullCheckpoint {
        val scope = scope(group, owner)
        val expected = adapter.checkpoint(scope)
        if (expected.state.phase == PrivatePullPhase.NeedsBootstrapFence || expected.state.phase is PrivatePullPhase.Idle) {
            assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitFence(expected, currentLease,
                PrivatePullFenceRead(PrivatePullCursor(STAMP, "zzzzzzzz"), TRUSTED)))
        }
        return adapter.checkpoint(scope)
    }

    private suspend fun commit(adapter: PrivatePullRemoteRoomAdapter, group: PrivatePullCollection,
                               document: PrivatePullDocument<Map<String, Any?>>, owner: String = OWNER) {
        val expected = begin(adapter, group, owner)
        assertEquals(PrivatePullCommitResult.COMMITTED, adapter.commitPage(expected, currentLease, page(expected, document)))
    }

    private fun page(expected: PrivatePullCheckpoint, vararg documents: PrivatePullDocument<Map<String, Any?>>) = PrivatePullPage(
        privatePullPlan(expected.state, PRIVATE_PULL_RUN_ROWS) as PrivatePullPlan.Page, documents.toList(), TRUSTED,
    )

    private fun document(group: PrivatePullCollection, id: String = id(group), extra: Map<String, Any?> = emptyMap(), owner: String = OWNER) =
        PrivatePullDocument(id, STAMP, record(group, id, owner) + extra)

    private fun record(group: PrivatePullCollection, id: String, owner: String = OWNER): Map<String, Any?> {
        val common = mapOf("ownerUid" to owner, "revision" to 3L, "updatedAtClient" to 30L,
            "createdAtClient" to 1L, "lastMutationId" to "remote-mutation", "deleted" to false)
        val fields: Map<String, Any?> = when (group) {
            PrivatePullCollection.BINDERS -> mapOf("binderId" to id, "name" to "remote binder", "description" to null,
                "coverStyle" to "DOMIDEX", "minimumPageCount" to 1L, "lastOpenedAtClient" to null)
            PrivatePullCollection.PLACEMENTS -> mapOf("placementId" to id, "binderId" to BINDER, "entrySyncId" to ENTRY,
                "slotPosition" to 0L, "holoEffectId" to "galaxy", "holoIntensity" to 0.5)
            PrivatePullCollection.LAYOUTS -> mapOf("entrySyncId" to id, "visibleInSetBinder" to false)
            PrivatePullCollection.PRICE_SELECTIONS -> mapOf("entrySyncId" to id, "schemaVersion" to 1L, "cleared" to true) +
                PRICE_SELECTION_VALUE_FIELDS.associateWith { null }
        }
        return common + fields
    }

    private suspend fun seedOwner(owner: String = OWNER): CollectionEntryEntity {
        val entry = newEntry(ENTRY, owner)
        dao.upsertPrivateBinder(binder(owner))
        return entry
    }

    private fun binder(owner: String = OWNER) = PrivateBinderEntity(BINDER, owner, "local binder",
        createdAtEpochMillis = 1, updatedAtEpochMillis = 1, syncState = SYNC_CLEAN, remoteRevision = 1)

    private suspend fun newEntry(syncId: String, owner: String = OWNER): CollectionEntryEntity {
        dao.insertCatalogCards(listOf(CatalogCardEntity(CARD, "POKEMON", "synthetic-set", "Synthetic set",
            "Synthetic card", "1", "GERMAN", "Normal", "Standard", 1L)))
        val row = CollectionEntryEntity(cardId = CARD, quantity = 1, condition = "Near Mint", acquisitionPriceMinor = null,
            acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "synthetic", frontPhotoUri = null,
            backPhotoUri = null, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
            ownerUid = owner, syncId = syncId, syncState = SYNC_CLEAN, remoteRevision = 1)
        return row.copy(id = dao.insertCollectionEntry(row))
    }

    private fun placement(id: String, entryId: Long) = PrivateBinderPlacementEntity(id, OWNER, BINDER, entryId, 0,
        createdAtEpochMillis = 1, updatedAtEpochMillis = 1, syncState = SYNC_CLEAN, remoteRevision = 1)

    private fun outbox(group: PrivatePullCollection, id: String) = PrivateBinderSyncOutboxEntity(
        key(group, id), "local-mutation-$id", OWNER, type(group), id, PRIVATE_SYNC_UPSERT,
        expectedRemoteRevision = 1, updatedAtEpochMillis = 1, createdAtEpochMillis = 1,
    )

    private suspend fun row(group: PrivatePullCollection, id: String, owner: String = OWNER): Any? = when (group) {
        PrivatePullCollection.BINDERS -> dao.privateBinder(id, owner)
        PrivatePullCollection.PLACEMENTS -> dao.privateBinderPlacement(id, owner)
        PrivatePullCollection.LAYOUTS -> dao.getCollectionEntryBySyncId(owner, id)?.let { dao.collectionLayout(it.id, owner) }
        PrivatePullCollection.PRICE_SELECTIONS -> dao.priceSelection(owner, id)
    }

    private suspend fun revision(group: PrivatePullCollection, id: String, owner: String = OWNER): Long? = when (val value = row(group, id, owner)) {
        is PrivateBinderEntity -> value.remoteRevision
        is PrivateBinderPlacementEntity -> value.remoteRevision
        is CollectionLayoutEntity -> value.remoteRevision
        is CollectionPriceSelectionEntity -> value.remoteRevision
        else -> null
    }

    private fun key(group: PrivatePullCollection, id: String) = "$OWNER:${type(group)}:$id"
    private fun id(group: PrivatePullCollection) = when (group) {
        PrivatePullCollection.BINDERS -> BINDER
        PrivatePullCollection.PLACEMENTS -> PLACEMENT
        PrivatePullCollection.LAYOUTS, PrivatePullCollection.PRICE_SELECTIONS -> ENTRY
    }
    private fun type(group: PrivatePullCollection) = when (group) {
        PrivatePullCollection.BINDERS -> PRIVATE_SYNC_BINDER
        PrivatePullCollection.PLACEMENTS -> PRIVATE_SYNC_PLACEMENT
        PrivatePullCollection.LAYOUTS -> PRIVATE_SYNC_LAYOUT
        PrivatePullCollection.PRICE_SELECTIONS -> PRIVATE_SYNC_PRICE_SELECTION
    }

    companion object {
        private const val OWNER = "delta-room-fixture-A"
        private const val OTHER_OWNER = "delta-room-fixture-B"
        private const val BINDER = "binder"
        private const val ENTRY = "entry"
        private const val PLACEMENT = "placement"
        private const val CARD = "synthetic-delta-room-card"
        private val STAMP = PrivatePullStamp(10, 1)
        private val TRUSTED = PrivatePullReadTrust.CONFIRMED_SERVER
    }
}
