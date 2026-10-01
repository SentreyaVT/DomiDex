package com.dominik.cardvault.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.AppMetadataEntity
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.PrivateBinderEntity
import com.dominik.cardvault.data.local.PrivateBinderSyncOutboxEntity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual local Room only. No Firebase, worker scheduling, ACK or simulated network success. */
@RunWith(AndroidJUnit4::class)
class PrivatePullDeferredScheduleInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val owner = "deferred-owner-A"
    private var lease = PrivatePullSessionLease(owner, 1)
    private val dao get() = database.cardVaultDao()
    private fun scope(group: PrivatePullCollection = PrivatePullCollection.BINDERS) = PrivatePullScope(owner, group)
    private fun store() = PrivatePullRoomStore(database, { it == lease })

    @Before fun setup() { database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build() }
    @After fun teardown() { database.close() }

    @Test fun noBudgetDoesNotReadWriteOrAskTheSessionGuard() = runBlocking {
        val guarded = PrivatePullRoomStore(database, { error("No work may start at zero budget") })
        assertEquals(PrivatePullDeferredBatch.NoBudget, guarded.reserveNextDeferredBatch(lease, 0))
        assertNull(dao.metadata(PrivatePullDeferredScheduleCodec.key(owner)))
    }

    @Test fun invalidLimitsFailBeforeAnyMetadataWrite() = runBlocking {
        for (limit in listOf(-1, 51)) assertTrue(runCatching { store().reserveNextDeferredBatch(lease, limit) }.exceptionOrNull() is IllegalArgumentException)
        assertNull(dao.metadata(PrivatePullDeferredScheduleCodec.key(owner)))
    }

    @Test fun blockedFirstFiftyDoNotStarveLaterMarkersAndNextSweepRevisits() = runBlocking {
        val original = seed(scope(), (1..120).map { "id-${it.toString().padStart(3, '0')}" })
        val selected = (1..3).flatMap { batch(store().reserveNextDeferredBatch(lease)).markers }
        assertEquals(original, selected)
        assertEquals(original.take(50), batch(store().reserveNextDeferredBatch(lease)).markers)
        assertEquals(original, store().deferredPage(scope(), limit = 200))
    }

    @Test fun fourGroupsRotateEvenWhenFirstGroupHasMoreWork() = runBlocking {
        for (group in PrivatePullCollection.entries) seed(scope(group), listOf("a", "b", "c"))
        val selections = (1..4).map { batch(store().reserveNextDeferredBatch(lease, 1)) }
        assertEquals(PrivatePullCollection.entries, selections.map { it.scope.collection })
        assertTrue(selections.all { it.markers.single().documentId == "a" })
        assertEquals("b", batch(store().reserveNextDeferredBatch(lease, 1)).markers.single().documentId)
    }

    @Test fun newLexicographicTailCannotExtendAnAlreadyCapturedSweep() = runBlocking {
        seed(scope(), listOf("a", "b", "c"))
        assertEquals("a", nextId())
        seed(scope(), listOf("d", "e"))
        assertEquals("b", nextId())
        seed(scope(), listOf("f", "g"))
        assertEquals("c", nextId())
        // The next visit starts a new sweep at a, not by chasing the newly inserted tail.
        assertEquals("a", nextId())
    }

    @Test fun removedUpperAndCursorDoNotPreventWrapOrInventDeletions() = runBlocking {
        seed(scope(), listOf("a", "b", "c"))
        assertEquals("a", nextId())
        dao.deleteMetadata(PrivatePullRoomStore.markerKey(scope(), "a"))
        dao.deleteMetadata(PrivatePullRoomStore.markerKey(scope(), "c"))
        assertEquals("b", nextId())
        assertEquals(PrivatePullDeferredBatch.Empty, store().reserveNextDeferredBatch(lease, 1))
        assertEquals("b", nextId())
    }

    @Test fun completelyEmptyOwnerTerminatesAndDoesNotReadForeignMarkers() = runBlocking {
        val foreign = scope().copy(ownerUid = "deferred-owner-B")
        seed(foreign, listOf("a"))
        assertEquals(PrivatePullDeferredBatch.Empty, store().reserveNextDeferredBatch(lease))
        assertEquals(1, store().deferredPage(foreign).size)
        assertNull(dao.metadata(PrivatePullDeferredScheduleCodec.key(foreign.ownerUid)))
    }

    @Test fun reservationChangesNeitherMarkerNorApplicationCheckpoint() = runBlocking {
        val marker = seed(scope(), listOf("a")).single()
        val binder = PrivateBinderEntity("a", owner, "Unchanged local binder", createdAtEpochMillis = 1, updatedAtEpochMillis = 2)
        val mutation = PrivateBinderSyncOutboxEntity("$owner:BINDER:a", "mutation-before-reservation", owner,
            PRIVATE_SYNC_BINDER, "a", PRIVATE_SYNC_UPSERT, expectedRemoteRevision = 7,
            updatedAtEpochMillis = 2, createdAtEpochMillis = 1, lastError = "retained conflict")
        dao.upsertPrivateBinder(binder)
        dao.upsertPrivateBinderSyncOutbox(mutation)
        val checkpoint = PrivatePullCodec.encode(PrivatePullState(scope(), revision = 17, epoch = "app-epoch"))
        dao.putMetadata(AppMetadataEntity(PrivatePullRoomStore.checkpointKey(scope()), checkpoint))
        assertEquals(marker, batch(store().reserveNextDeferredBatch(lease)).markers.single())
        assertEquals(checkpoint, dao.metadata(PrivatePullRoomStore.checkpointKey(scope())))
        assertEquals(listOf(marker), store().deferredPage(scope()))
        assertEquals(binder, dao.privateBinder("a", owner))
        assertEquals(mutation, dao.privateBinderSyncOutbox(mutation.entryKey))
        // A later genuine outbox ACK is still not an apply/recheck of the retained marker.
        dao.deletePrivateBinderSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
        assertEquals(listOf(marker), batch(store().reserveNextDeferredBatch(lease)).markers)
        assertEquals(checkpoint, dao.metadata(PrivatePullRoomStore.checkpointKey(scope())))
    }

    @Test fun replacementMarkerIsReturnedExactlyRatherThanAsAnOldReservation() = runBlocking {
        seed(scope(), listOf("a"))
        val old = batch(store().reserveNextDeferredBatch(lease)).markers.single()
        val replacement = old.copy(version = 7, epoch = "replacement")
        dao.putMetadata(AppMetadataEntity(PrivatePullRoomStore.markerKey(scope(), "a"), PrivatePullCodec.encodeMarker(replacement)))
        assertEquals(replacement, batch(store().reserveNextDeferredBatch(lease)).markers.single())
        assertNotEquals(old, replacement)
    }

    @Test fun corruptScheduleResetsOnlySchedulingAndPreservesMarkers() = runBlocking {
        val original = seed(scope(), listOf("a", "b"))
        dao.putMetadata(AppMetadataEntity(PrivatePullDeferredScheduleCodec.key(owner), "corrupt scheduling state"))
        assertEquals(original.take(1), batch(store().reserveNextDeferredBatch(lease, 1)).markers)
        assertEquals(original, store().deferredPage(scope()))
        assertEquals("b", nextId())
    }

    @Test fun corruptMarkerIsAnExplicitFailureWithoutProgressOrDeletion() = runBlocking {
        seed(scope(), listOf("a"))
        val key = PrivatePullRoomStore.markerKey(scope(), "b")
        dao.putMetadata(AppMetadataEntity(key, "corrupt marker"))
        assertTrue(runCatching { store().reserveNextDeferredBatch(lease) }.exceptionOrNull() is PrivatePullMetadataException)
        assertEquals("corrupt marker", dao.metadata(key))
        assertNull(dao.metadata(PrivatePullDeferredScheduleCodec.key(owner)))
    }

    @Test fun staleLeaseCannotReserveAnything() = runBlocking {
        seed(scope(), listOf("a"))
        assertEquals(PrivatePullDeferredBatch.StaleSession, store().reserveNextDeferredBatch(lease.copy(generation = 0)))
        assertNull(dao.metadata(PrivatePullDeferredScheduleCodec.key(owner)))
    }

    @Test fun accountAbaAfterTheScheduleWriteRollsItBackAndKeepsMarkers() = runBlocking {
        val original = seed(scope(), listOf("a", "b"))
        val captured = lease
        var sawWrittenSchedule = false
        val guarded = PrivatePullRoomStore(database, { given ->
            if (database.inTransaction() && scheduleExistsDirectly()) {
                sawWrittenSchedule = true
                lease = PrivatePullSessionLease("deferred-owner-B", 2)
                lease = PrivatePullSessionLease(owner, 3)
            }
            given == lease
        })
        assertEquals(PrivatePullDeferredBatch.StaleSession, guarded.reserveNextDeferredBatch(captured, 1))
        assertTrue(sawWrittenSchedule)
        assertNull(dao.metadata(PrivatePullDeferredScheduleCodec.key(owner)))
        assertEquals(original, store().deferredPage(scope()))
        assertEquals("a", nextId())
    }

    @Test fun cancellationAfterTheScheduleWritePropagatesAndRollsBack() = runBlocking {
        seed(scope(), listOf("a"))
        val guarded = PrivatePullRoomStore(database, {
            if (database.inTransaction() && scheduleExistsDirectly()) throw CancellationException("local synthetic cancellation")
            true
        })
        assertTrue(runCatching { guarded.reserveNextDeferredBatch(lease, 1) }.exceptionOrNull() is CancellationException)
        assertNull(dao.metadata(PrivatePullDeferredScheduleCodec.key(owner)))
        assertEquals(1, store().deferredPage(scope()).size)
    }

    @Test fun ownerChangeAfterCommittedReservationDoesNotPublishItsBatch() = runBlocking {
        seed(scope(), listOf("a"))
        var sawWrite = false
        val guarded = PrivatePullRoomStore(database, { given ->
            if (database.inTransaction() && scheduleExistsDirectly()) sawWrite = true
            if (sawWrite && !database.inTransaction()) lease = lease.copy(generation = lease.generation + 1)
            given == lease
        })
        assertEquals(PrivatePullDeferredBatch.StaleSession, guarded.reserveNextDeferredBatch(lease, 1))
        assertNotNull("Already committed scheduling is not falsely claimed to roll back", dao.metadata(PrivatePullDeferredScheduleCodec.key(owner)))
        assertEquals(1, store().deferredPage(scope()).size)
    }

    @Test fun databaseReopenContinuesSelectionAndCrashBeforeAnyApplyLosesNoMarker() = runBlocking {
        database.close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "private-deferred-${UUID.randomUUID()}"
        try {
            database = Room.databaseBuilder(context, CardVaultDatabase::class.java, name).build()
            val original = seed(scope(), listOf("a", "b", "c"))
            assertEquals("a", nextId()) // Simulate process ending before any network/apply.
            database.close()
            database = Room.databaseBuilder(context, CardVaultDatabase::class.java, name).build()
            assertEquals("b", nextId())
            assertEquals("c", nextId())
            assertEquals("a", nextId())
            assertEquals(original, store().deferredPage(scope()))
        } finally {
            database.close()
            context.deleteDatabase(name)
            database = Room.inMemoryDatabaseBuilder(context, CardVaultDatabase::class.java).build()
        }
    }

    @Test fun concurrentLocalReservationsSerializeTheirPositionsWithoutAckingMarkers() = runBlocking {
        val original = seed(scope(), listOf("a", "b", "c"))
        val captured = lease
        val first = async(Dispatchers.IO) { store().reserveNextDeferredBatch(captured, 1) }
        val second = async(Dispatchers.IO) { store().reserveNextDeferredBatch(captured, 1) }
        assertEquals(setOf("a", "b"), listOf(first.await(), second.await()).map { batch(it).markers.single().documentId }.toSet())
        assertEquals(original, store().deferredPage(scope()))
    }

    @Test fun exactUpperSqlBoundDoesNotIncludeLongerIdsWithTheSamePrefix() = runBlocking {
        seed(scope(), listOf("a", "a-", "a.", "a0"))
        val schedule = PrivatePullDeferredSchedule.empty(owner).copy(binders = PrivatePullDeferredSweep("a"))
        dao.putMetadata(AppMetadataEntity(PrivatePullDeferredScheduleCodec.key(owner), PrivatePullDeferredScheduleCodec.encode(schedule)))
        assertEquals(listOf("a"), batch(store().reserveNextDeferredBatch(lease)).markers.map { it.documentId })
        assertEquals(4, store().deferredPage(scope()).size)
    }

    private fun scheduleExistsDirectly(): Boolean = database.openHelper.readableDatabase.query(
        "SELECT 1 FROM app_metadata WHERE `key` = ?", arrayOf(PrivatePullDeferredScheduleCodec.key(owner)),
    ).use { it.moveToFirst() }

    private suspend fun nextId() = batch(store().reserveNextDeferredBatch(lease, 1)).markers.single().documentId
    private fun batch(result: PrivatePullDeferredBatch) = result as PrivatePullDeferredBatch.Batch
    private suspend fun seed(scope: PrivatePullScope, ids: List<String>): List<PrivatePullDeferredMarker> = ids.map { id ->
        val marker = PrivatePullDeferredMarker(scope, id, 1, PrivatePullDeferReason.LOCAL_MUTATION, epoch = "marker-epoch")
        dao.putMetadata(AppMetadataEntity(PrivatePullRoomStore.markerKey(scope, id), PrivatePullCodec.encodeMarker(marker)))
        marker
    }
}
