package com.dominik.cardvault.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.AppMetadataEntity
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.PrivateBinderEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room transactions, synthetic rows, no Firebase/network or physical-device access. */
@RunWith(AndroidJUnit4::class)
class PrivatePullRoomStoreInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val scope = PrivatePullScope("pull-fixture-A", PrivatePullCollection.BINDERS)
    private var currentLease = PrivatePullSessionLease(scope.ownerUid, 1)
    private val dao get() = database.cardVaultDao()
    private fun store(maximumDeferred: Int = 2_000) = PrivatePullRoomStore(database, { it == currentLease }, maximumDeferred)

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
    }
    @After fun teardown() = database.close()

    @Test fun payloadAndCursorCommitTogether() = runBlocking {
        val store = store()
        val checkpoint = bootstrap(store)
        val result = privatePullCommitPage(store, checkpoint, currentLease, page(checkpoint, "one", "two")) {
            writeBinder(it.documentId)
            PrivatePullApplyDecision.Complete
        }
        assertEquals(PrivatePullCommitResult.COMMITTED, result)
        assertNotNull(dao.privateBinder("one", scope.ownerUid))
        assertNotNull(dao.privateBinder("two", scope.ownerUid))
        assertEquals(PrivatePullPhase.Idle(PrivatePullStamp(10, 1)), store.checkpoint(scope).state.phase)
    }

    @Test fun exceptionAfterPayloadAndMarkerRollsEverythingBack() = runBlocking {
        val store = store()
        val checkpoint = bootstrap(store)
        val failure = runCatching {
            privatePullCommitPage(store, checkpoint, currentLease, page(checkpoint, "one", "two")) {
                writeBinder(it.documentId)
                if (it.documentId == "two") error("synthetic interruption")
                PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
            }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(checkpoint, store.checkpoint(scope))
        assertNull(dao.privateBinder("one", scope.ownerUid))
        assertTrue(store.deferredPage(scope).isEmpty())
    }

    @Test fun accountRoundTripDuringApplyRollsBackPayloadAndCursor() = runBlocking {
        val store = store()
        val checkpoint = bootstrap(store)
        val captured = currentLease
        val result = privatePullCommitPage(store, checkpoint, captured, page(checkpoint, "one")) {
            writeBinder(it.documentId)
            currentLease = PrivatePullSessionLease("pull-fixture-B", 2)
            currentLease = PrivatePullSessionLease(scope.ownerUid, 3)
            PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
        }
        assertEquals(PrivatePullCommitResult.STALE_SESSION, result)
        assertNull(dao.privateBinder("one", scope.ownerUid))
        assertTrue(store.deferredPage(scope).isEmpty())
        assertEquals(checkpoint, store.checkpoint(scope))
    }

    @Test fun sessionChangeAfterCheckpointWriteRollsBackPayloadMarkerAndCheckpoint() = runBlocking {
        val observer = store()
        val checkpoint = bootstrap(observer)
        val captured = currentLease
        var checks = 0
        var rawSeenInsideFinalCheck: String? = null
        val guarded = PrivatePullRoomStore(database, { lease ->
            checks++
            if (checks == 4) {
                // This predicate runs on the applying transaction thread, after its checkpoint write.
                database.openHelper.readableDatabase.query(
                    "SELECT value FROM app_metadata WHERE `key` = ?",
                    arrayOf(PrivatePullRoomStore.checkpointKey(scope)),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    rawSeenInsideFinalCheck = cursor.getString(0)
                }
                currentLease = captured.copy(generation = captured.generation + 1)
            }
            lease == currentLease
        })
        val result = privatePullCommitPage(guarded, checkpoint, captured, page(checkpoint, "one")) {
            writeBinder(it.documentId)
            PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
        }
        assertEquals(4, checks)
        assertNotNull("The final guard must see an already-written checkpoint", rawSeenInsideFinalCheck)
        assertNotEquals(checkpoint.raw, rawSeenInsideFinalCheck)
        assertEquals(PrivatePullCommitResult.STALE_SESSION, result)
        assertNull(dao.privateBinder("one", scope.ownerUid))
        assertTrue(observer.deferredPage(scope).isEmpty())
        assertEquals(checkpoint, observer.checkpoint(scope))
    }

    @Test fun staleCheckpointDoesNotInvokeLocalApply() = runBlocking {
        val store = store()
        val captured = bootstrap(store)
        val page = page(captured, "one")
        assertEquals(PrivatePullCommitResult.COMMITTED, privatePullCommitPage(store, captured, currentLease, page) {
            writeBinder(it.documentId)
            PrivatePullApplyDecision.Complete
        })
        var called = false
        assertEquals(PrivatePullCommitResult.STALE_CHECKPOINT, privatePullCommitPage(store, captured, currentLease, page) {
            called = true
            PrivatePullApplyDecision.Complete
        })
        assertFalse(called)
    }

    @Test fun deferredCapacityFailureCannotSkipAnyRemoteRow() = runBlocking {
        val store = store(maximumDeferred = 2)
        val captured = bootstrap(store)
        val failure = runCatching {
            privatePullCommitPage(store, captured, currentLease, page(captured, "one", "three", "two")) {
                writeBinder(it.documentId)
                PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
            }
        }.exceptionOrNull()
        assertTrue(failure is PrivatePullDeferredLimitException)
        assertEquals(captured, store.checkpoint(scope))
        assertTrue(store.deferredPage(scope).isEmpty())
        assertNull(dao.privateBinder("one", scope.ownerUid))
    }

    @Test fun fullDeferredCapacityAllowsRenewingAnExistingMarker() = runBlocking {
        val store = store(maximumDeferred = 2)
        val initial = bootstrap(store)
        assertEquals(PrivatePullCommitResult.COMMITTED,
            privatePullCommitPage(store, initial, currentLease, page(initial, "one", "two")) {
                PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
            })
        val markersBefore = store.deferredPage(scope).associateBy { it.documentId }
        val expected = store.checkpoint(scope)
        assertEquals(PrivatePullCommitResult.COMMITTED, privatePullCommitRecheck(
            store, expected, currentLease, markersBefore.getValue("one"),
            PrivatePullDocument("one", PrivatePullStamp(11, 0), Unit), PrivatePullReadTrust.CONFIRMED_SERVER,
        ) {
            PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
        })
        val markersAfter = store.deferredPage(scope).associateBy { it.documentId }
        assertEquals(setOf("one", "two"), markersAfter.keys)
        assertEquals(markersBefore.getValue("two"), markersAfter.getValue("two"))
        assertEquals(expected.state.revision + 1, markersAfter.getValue("one").version)
        assertEquals(expected.state.phase, store.checkpoint(scope).state.phase)
    }

    @Test fun cachedDeferredCountHandlesInsertRemoveInsertAndMissingRemoval() = runBlocking {
        val store = store(maximumDeferred = 2)
        val initial = bootstrap(store)
        privatePullCommitPage(store, initial, currentLease, page(initial, "one")) {
            PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
        }
        val expected = store.checkpoint(scope)
        val next = expected.state.advance()
        assertEquals(PrivatePullCommitResult.COMMITTED, store.compareAndCommit(expected, currentLease) {
            putDeferred(PrivatePullDeferredMarker(scope, "two", next.revision, PrivatePullDeferReason.LOCAL_MUTATION, epoch = next.epoch))
            removeDeferred("one")
            putDeferred(PrivatePullDeferredMarker(scope, "three", next.revision, PrivatePullDeferReason.LOCAL_MUTATION, epoch = next.epoch))
            removeDeferred("absent")
            // Replacing an existing marker must not charge its slot twice.
            putDeferred(PrivatePullDeferredMarker(scope, "two", next.revision, PrivatePullDeferReason.LOCAL_MUTATION, epoch = next.epoch))
            next
        })
        val markers = store.deferredPage(scope)
        assertEquals(listOf("three", "two"), markers.map { it.documentId })
        assertTrue(markers.all { it.version == next.revision })
        assertEquals(next, store.checkpoint(scope).state)
    }

    @Test fun exceedingExistingCapacityRestoresEarlierMarkerRemovalAndPayload() = runBlocking {
        val store = store(maximumDeferred = 2)
        val initial = bootstrap(store)
        privatePullCommitPage(store, initial, currentLease, page(initial, "one", "two")) {
            PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
        }
        val expected = store.checkpoint(scope)
        val before = store.deferredPage(scope)
        val failure = runCatching {
            store.compareAndCommit(expected, currentLease) {
                writeBinder("uncommitted")
                removeDeferred("one")
                val next = expected.state.advance()
                putDeferred(PrivatePullDeferredMarker(scope, "three", next.revision, PrivatePullDeferReason.LOCAL_MUTATION, epoch = next.epoch))
                putDeferred(PrivatePullDeferredMarker(scope, "four", next.revision, PrivatePullDeferReason.LOCAL_MUTATION, epoch = next.epoch))
                next
            }
        }.exceptionOrNull()
        assertTrue(failure is PrivatePullDeferredLimitException)
        assertNull(dao.privateBinder("uncommitted", scope.ownerUid))
        assertEquals(before, store.deferredPage(scope))
        assertEquals(expected, store.checkpoint(scope))
    }

    @Test fun recheckRequiresCurrentMarkerAndDoesNotAdvanceRemoteCursor() = runBlocking {
        val store = store()
        val initial = bootstrap(store)
        privatePullCommitPage(store, initial, currentLease, page(initial, "one")) {
            PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
        }
        val marker = store.deferredPage(scope).single()
        val expected = store.checkpoint(scope)
        // A different local recheck already replaced this marker, even though cursor was unchanged.
        dao.putMetadata(AppMetadataEntity(PrivatePullRoomStore.markerKey(scope, "one"), PrivatePullCodec.encodeMarker(marker.copy(version = marker.version + 1))))
        var called = false
        assertEquals(PrivatePullCommitResult.STALE_MARKER, privatePullCommitRecheck(store, expected, currentLease, marker,
            PrivatePullDocument("one", PrivatePullStamp(11, 0), Unit), PrivatePullReadTrust.CONFIRMED_SERVER) {
            called = true
            PrivatePullApplyDecision.Complete
        })
        assertFalse(called)
        val currentMarker = store.deferredPage(scope).single()
        assertEquals(PrivatePullCommitResult.COMMITTED, privatePullCommitRecheck(store, expected, currentLease, currentMarker,
            PrivatePullDocument("one", PrivatePullStamp(11, 0), Unit), PrivatePullReadTrust.CONFIRMED_SERVER) {
            writeBinder(it.documentId)
            PrivatePullApplyDecision.Complete
        })
        assertEquals(expected.state.phase, store.checkpoint(scope).state.phase)
        assertTrue(store.deferredPage(scope).isEmpty())
        assertNotNull(dao.privateBinder("one", scope.ownerUid))
    }

    @Test fun markerPagingIsBoundedAndOwnerAndCollectionScoped() = runBlocking {
        val store = store()
        val otherOwner = scope.copy(ownerUid = "pull-fixture-B")
        val otherGroup = scope.copy(collection = PrivatePullCollection.PLACEMENTS)
        for (target in listOf(scope, otherOwner, otherGroup)) {
            for (id in listOf("a", "b", "c")) {
                val marker = PrivatePullDeferredMarker(target, id, 1, PrivatePullDeferReason.LOCAL_MUTATION)
                dao.putMetadata(AppMetadataEntity(PrivatePullRoomStore.markerKey(target, id), PrivatePullCodec.encodeMarker(marker)))
            }
        }
        val first = store.deferredPage(scope, limit = 2)
        val second = store.deferredPage(scope, first.last().documentId, limit = 2)
        assertEquals(listOf("a", "b"), first.map { it.documentId })
        assertEquals(listOf("c"), second.map { it.documentId })
        assertTrue((first + second).all { it.scope == scope })
        assertEquals(3, store.deferredPage(otherOwner).size)
        assertEquals(3, store.deferredPage(otherGroup).size)
    }

    @Test fun corruptMarkerIsNotSilentlyDiscarded() = runBlocking {
        val key = PrivatePullRoomStore.markerKey(scope, "one")
        dao.putMetadata(AppMetadataEntity(key, "broken local metadata"))
        assertTrue(runCatching { store().deferredPage(scope) }.exceptionOrNull() is PrivatePullMetadataException)
        assertEquals("broken local metadata", dao.metadata(key))
    }

    @Test fun wellFormedMarkerWithWrongDocumentIdAtItsKeyIsRejectedAndPreserved() = runBlocking {
        val key = PrivatePullRoomStore.markerKey(scope, "one")
        val raw = PrivatePullCodec.encodeMarker(PrivatePullDeferredMarker(
            scope, "two", 1, PrivatePullDeferReason.LOCAL_MUTATION,
        ))
        dao.putMetadata(AppMetadataEntity(key, raw))
        assertTrue(runCatching { store().deferredPage(scope) }.exceptionOrNull() is PrivatePullMetadataException)
        assertEquals(raw, dao.metadata(key))
        assertNull(dao.metadata(PrivatePullRoomStore.markerKey(scope, "two")))
    }

    @Test fun wellFormedMarkerWithWrongOwnerOrCollectionIsRejectedAndPreserved() = runBlocking {
        val key = PrivatePullRoomStore.markerKey(scope, "one")
        val alienScopes = listOf(
            scope.copy(ownerUid = "pull-fixture-B"),
            scope.copy(collection = PrivatePullCollection.PLACEMENTS),
        )
        for (alien in alienScopes) {
            val raw = PrivatePullCodec.encodeMarker(PrivatePullDeferredMarker(
                alien, "one", 1, PrivatePullDeferReason.LOCAL_MUTATION,
            ))
            dao.putMetadata(AppMetadataEntity(key, raw))
            assertTrue(runCatching { store().deferredPage(scope) }.exceptionOrNull() is PrivatePullMetadataException)
            assertEquals(raw, dao.metadata(key))
            assertTrue(store().deferredPage(alien).isEmpty())
        }
    }

    @Test fun invalidReturnedScopeRollsBackCallbackPayloadAndMarker() = runBlocking {
        assertInvalidReturnedStateRollsBack { next ->
            next.copy(scope = scope.copy(ownerUid = "pull-fixture-B"))
        }
    }

    @Test fun invalidReturnedRevisionRollsBackCallbackPayloadAndMarker() = runBlocking {
        assertInvalidReturnedStateRollsBack { next -> next.copy(revision = next.revision + 1) }
    }

    @Test fun corruptCheckpointRecoveryWithSameFenceCannotReviveAnOldCasToken() = runBlocking {
        assertRecoveryRejectsOldCheckpoint("synthetic corrupted checkpoint")
    }

    @Test fun missingCheckpointRecoveryWithSameFenceCannotReviveAnOldCasToken() = runBlocking {
        assertRecoveryRejectsOldCheckpoint(null)
    }

    @Test fun scopeOrLeaseMismatchCannotWrite() = runBlocking {
        val store = store()
        val checkpoint = bootstrap(store)
        var called = false
        assertEquals(PrivatePullCommitResult.STALE_SESSION, store.compareAndCommit(checkpoint,
            PrivatePullSessionLease("pull-fixture-B", currentLease.generation), changes = {
                called = true
                checkpoint.state.advance()
            }))
        assertFalse(called)
        assertEquals(checkpoint, store.checkpoint(scope))
    }

    private suspend fun bootstrap(store: PrivatePullRoomStore): PrivatePullCheckpoint {
        val initial = store.checkpoint(scope)
        assertEquals(PrivatePullCommitResult.COMMITTED, privatePullCommitFence(store, initial, currentLease,
            PrivatePullFenceRead(PrivatePullCursor(PrivatePullStamp(10, 1), "zzz"), PrivatePullReadTrust.CONFIRMED_SERVER)))
        return store.checkpoint(scope)
    }

    private suspend fun assertInvalidReturnedStateRollsBack(
        invalidState: (PrivatePullState) -> PrivatePullState,
    ) {
        val store = store()
        val expected = bootstrap(store)
        val failure = runCatching {
            store.compareAndCommit(expected, currentLease) {
                writeBinder("uncommitted")
                val next = expected.state.advance()
                putDeferred(PrivatePullDeferredMarker(scope, "uncommitted", next.revision, PrivatePullDeferReason.LOCAL_MUTATION, epoch = next.epoch))
                invalidState(next)
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertNull(dao.privateBinder("uncommitted", scope.ownerUid))
        assertTrue(store.deferredPage(scope).isEmpty())
        assertEquals(expected, store.checkpoint(scope))
        assertNull(dao.metadata(PrivatePullRoomStore.checkpointKey(scope.copy(ownerUid = "pull-fixture-B"))))
    }

    private suspend fun assertRecoveryRejectsOldCheckpoint(corruptRaw: String?) {
        val store = store()
        val old = bootstrap(store)
        val stalePage = page(old, "stale-result")
        val key = PrivatePullRoomStore.checkpointKey(scope)
        if (corruptRaw == null) dao.deleteMetadata(key)
        else dao.putMetadata(AppMetadataEntity(key, corruptRaw))

        val recovered = store.checkpoint(scope)
        assertEquals(corruptRaw, recovered.raw)
        assertEquals(corruptRaw != null, recovered.recovered)
        assertNotEquals(old.state.epoch, recovered.state.epoch)
        assertEquals("Decode must reuse the captured recovery epoch for exact CAS validation",
            recovered, PrivatePullCodec.decode(recovered.raw, scope, recovered.state.epoch))
        assertEquals(PrivatePullCommitResult.COMMITTED, privatePullCommitFence(
            store, recovered, currentLease,
            PrivatePullFenceRead(PrivatePullCursor(PrivatePullStamp(10, 1), "zzz"), PrivatePullReadTrust.CONFIRMED_SERVER),
        ))
        val repaired = store.checkpoint(scope)
        assertEquals(old.state.revision, repaired.state.revision)
        assertEquals(old.state.phase, repaired.state.phase)
        assertEquals(recovered.state.epoch, repaired.state.epoch)
        assertNotEquals("Same phase/revision/fence must not recreate the old raw CAS token", old.raw, repaired.raw)

        var staleApplied = false
        assertEquals(PrivatePullCommitResult.STALE_CHECKPOINT,
            privatePullCommitPage(store, old, currentLease, stalePage) {
                staleApplied = true
                writeBinder(it.documentId)
                PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
            })
        assertFalse(staleApplied)
        assertNull(dao.privateBinder("stale-result", scope.ownerUid))
        assertTrue(store.deferredPage(scope).isEmpty())
        assertEquals(repaired, store.checkpoint(scope))

        // Recovery still permits a newly fetched response and durable marker in the new epoch.
        assertEquals(PrivatePullCommitResult.COMMITTED,
            privatePullCommitPage(store, repaired, currentLease, page(repaired, "fresh-result")) {
                writeBinder(it.documentId)
                PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
            })
        assertNotNull(dao.privateBinder("fresh-result", scope.ownerUid))
        assertEquals(repaired.state.epoch, store.deferredPage(scope).single().epoch)
    }

    private fun page(checkpoint: PrivatePullCheckpoint, vararg ids: String) = PrivatePullPage(
        privatePullPlan(checkpoint.state, PRIVATE_PULL_RUN_ROWS) as PrivatePullPlan.Page,
        ids.map { PrivatePullDocument(it, PrivatePullStamp(9, 999_999_999), Unit) },
        PrivatePullReadTrust.CONFIRMED_SERVER,
    )

    private suspend fun writeBinder(id: String) = dao.upsertPrivateBinder(PrivateBinderEntity(
        id = id, ownerUid = scope.ownerUid, name = "Synthetic binder", createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
    ))
}
