package com.dominik.cardvault.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Actual in-memory Room and production resolution helpers; no Firebase SDK/Worker E2E claim. */
@RunWith(AndroidJUnit4::class)
class PriceSelectionConflictInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val dao get() = database.cardVaultDao()
    private val generation = AtomicLong(1)
    private val closed = AtomicBoolean(false)
    private var currentOwner: String? = OWNER
    private val lease = PrivatePullAuthSession(PrivatePullSessionLease(OWNER, 1), generation, closed) { currentOwner }

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
    }
    @After fun close() = database.close()

    @Test fun conflictRetainsEveryChoiceFieldMutationAndBaseAndProtectsPull() = runBlocking {
        seed()
        val local = dao.priceSelection(OWNER, ENTRY)!!
        val mutation = mutation()
        mark(mutation)
        assertEquals(local.copy(syncState = SYNC_CONFLICT), dao.priceSelection(OWNER, ENTRY))
        assertEquals(mutation.copy(lastError = "price-selection-conflict:3"), current())
        assertTrue(dao.pendingPrivateBinderSyncOutbox(OWNER).isEmpty())
        assertEquals(1, dao.observePriceSelectionConflictCount(OWNER).first())
        val result = database.withPrivateSyncSession(lease::isCurrent) {
            applyPrivateRemoteRecord(dao, OWNER, PRIVATE_SYNC_PRICE_SELECTION, ENTRY, remote(), true)
        }
        assertEquals(PrivateRemoteApplyOutcome.DeferredLocal, result)
        assertEquals(local.amountMinor, dao.priceSelection(OWNER, ENTRY)?.amountMinor)
    }

    @Test fun onlyMarkedPriceRowsAreExcludedAndOwnersStaySeparated() = runBlocking {
        seed(); mark(mutation())
        for (type in listOf(PRIVATE_SYNC_BINDER, PRIVATE_SYNC_PLACEMENT, PRIVATE_SYNC_LAYOUT)) {
            dao.upsertPrivateBinderSyncOutbox(mutation().copy(entryKey = "$OWNER:$type:other", recordType = type,
                recordId = "other", lastError = "price-selection-conflict:3"))
        }
        dao.upsertPrivateBinderSyncOutbox(mutation().copy(entryKey = "$OWNER:$PRIVATE_SYNC_PRICE_SELECTION:other",
            recordId = "other", lastError = "network-error"))
        dao.upsertPrivateBinderSyncOutbox(mutation().copy(entryKey = "foreign:$PRIVATE_SYNC_PRICE_SELECTION:other",
            ownerUid = "foreign", recordId = "other", lastError = "price-selection-conflict:3"))
        assertEquals(4, dao.pendingPrivateBinderSyncOutbox(OWNER).size)
        assertEquals(1, dao.conflictedPriceSelectionOutbox(OWNER).size)
        val repository = RoomCollectionRepository(database, MutableStateFlow(OWNER))
        assertEquals(1, repository.syncConflictCount.first())
    }

    @Test fun newerMutationCannotBeMarkedResolvedOrRebasedByOldAction() = runBlocking {
        seed()
        val old = mutation()
        val successor = old.copy(mutationId = "successor")
        dao.upsertPrivateBinderSyncOutbox(successor)
        assertFalse(mark(old))
        mark(successor)
        val conflict = current()!!
        dao.upsertPrivateBinderSyncOutbox(successor.copy(mutationId = "another"))
        assertFalse(local(conflict))
        assertFalse(cloud(conflict))
        assertEquals("another", current()?.mutationId)
    }

    @Test fun explicitLocalRebasesOnceWithoutRefreshingMarketDateOrChoice() = runBlocking {
        seed(); mark(mutation())
        val conflict = current()!!
        val before = dao.priceSelection(OWNER, ENTRY)!!
        assertTrue(local(conflict))
        assertEquals(before.copy(syncState = SYNC_PENDING, remoteRevision = 3, updatedAtEpochMillis = 99),
            dao.priceSelection(OWNER, ENTRY))
        assertEquals(conflict.copy(mutationId = "explicit-new", expectedRemoteRevision = 3,
            createdAtEpochMillis = 99, updatedAtEpochMillis = 99, lastError = null), current())
        assertFalse(local(conflict))
        assertEquals(0, dao.observePriceSelectionConflictCount(OWNER).first())
        assertEquals(1, dao.pendingPrivateBinderSyncOutbox(OWNER).size)
    }

    @Test fun localResolutionRequiresStableLiveExactParent() = runBlocking {
        seed(); mark(mutation())
        val conflict = current()!!
        val original = dao.getCollectionEntryBySyncId(OWNER, ENTRY)!!
        for (changed in listOf(original.copy(syncState = SYNC_PENDING), original.copy(syncState = SYNC_ERROR),
            original.copy(syncState = SYNC_CONFLICT), original.copy(deletedAtEpochMillis = 30),
            original.copy(sourceCardId = "other"), original.copy(externalProductId = 43), original.copy(finishOverride = "Reverse Holo"))) {
            dao.updateCollectionEntry(changed)
            assertFalse(local(conflict))
            assertEquals(conflict, current())
        }
        dao.updateCollectionEntry(original)
        dao.upsertSyncOutbox(CollectionSyncOutboxEntity("$OWNER:$ENTRY", "parent", OWNER, ENTRY, "UPSERT", 1, createdAtEpochMillis = 2))
        assertFalse(local(conflict))
        assertEquals(conflict, current())
    }

    @Test fun cloudResolutionRequiresStableParentEvenWhenSnapshotIsValid() = runBlocking {
        seed(); mark(mutation())
        val conflict = current()!!
        val original = dao.getCollectionEntryBySyncId(OWNER, ENTRY)!!
        for (changed in listOf(original.copy(syncState = SYNC_PENDING), original.copy(syncState = SYNC_ERROR),
            original.copy(syncState = SYNC_CONFLICT), original.copy(sourceCardId = "other"),
            original.copy(deletedAtEpochMillis = 30))) {
            dao.updateCollectionEntry(changed)
            assertFalse(cloud(conflict))
            assertEquals(conflict, current())
        }
        dao.updateCollectionEntry(original)
        dao.upsertSyncOutbox(CollectionSyncOutboxEntity("$OWNER:$ENTRY", "parent", OWNER, ENTRY, "UPSERT", 1, createdAtEpochMillis = 2))
        assertFalse(cloud(conflict))
        assertEquals(conflict, current())
    }

    @Test fun explicitCloudAppliesTheConfirmedSnapshotAndAcknowledgesOnlyThisConflict() = runBlocking {
        seed(); mark(mutation())
        val conflict = current()!!
        val parent = dao.getCollectionEntryBySyncId(OWNER, ENTRY)
        assertTrue(cloud(conflict))
        val saved = dao.priceSelection(OWNER, ENTRY)!!
        assertEquals(999L, saved.amountMinor)
        assertEquals(3L, saved.remoteRevision)
        assertEquals(SYNC_CLEAN, saved.syncState)
        assertEquals(10L, saved.sourceAt)
        assertNull(current())
        assertEquals(parent, dao.getCollectionEntryBySyncId(OWNER, ENTRY))
    }

    @Test fun oldMalformedForeignOrUnconfirmedSnapshotsNeverAcknowledgeConflict() = runBlocking {
        seed(); mark(mutation())
        val conflict = current()!!
        val before = dao.priceSelection(OWNER, ENTRY)
        for (data in listOf(emptyMap(), remote() - "metric", remote() + ("revision" to 2L),
            remote() + ("revision" to 3.5), remote() + ("ownerUid" to "foreign"),
            remote() + ("entrySyncId" to "other"), remote() + ("sourceCardId" to "other"),
            remote() + ("amountMinor" to 0L), remote() + ("cleared" to true))) {
            assertFalse(cloud(conflict, data))
            assertEquals(conflict, current())
            assertEquals(before, dao.priceSelection(OWNER, ENTRY))
        }
        assertFalse(keepCloudPriceSelectionConflict(database, conflict, remote(), false, lease::isCurrent))
        assertEquals(conflict, current())
    }

    @Test fun equalRevisionNeverMeansAutomaticConsentButExplicitCloudCanReplaceLocalDraft() = runBlocking {
        seed()
        dao.upsertPriceSelection(dao.priceSelection(OWNER, ENTRY)!!.copy(remoteRevision = 3))
        mark(mutation())
        val conflict = current()!!
        assertTrue(cloud(conflict))
        assertEquals(999L, dao.priceSelection(OWNER, ENTRY)?.amountMinor)
    }

    @Test fun remoteOlderThanLocallyConfirmedBaseDoesNotRollBack() = runBlocking {
        seed(); mark(mutation())
        val conflict = current()!!
        val local = dao.priceSelection(OWNER, ENTRY)!!.copy(remoteRevision = 4)
        dao.upsertPriceSelection(local)
        assertFalse(cloud(conflict))
        assertEquals(local, dao.priceSelection(OWNER, ENTRY))
        assertEquals(conflict, current())
    }

    @Test fun cloudClearWorksForConfirmedDeletedParentButLocalCannotResurrectIt() = runBlocking {
        seed(); mark(mutation())
        val conflict = current()!!
        val parent = dao.getCollectionEntryBySyncId(OWNER, ENTRY)!!
        dao.updateCollectionEntry(parent.copy(deletedAtEpochMillis = 30))
        assertFalse(local(conflict))
        val clear = remote() + ("cleared" to true) + PRICE_SELECTION_VALUE_FIELDS.associateWith { null }
        assertTrue(cloud(conflict, clear))
        assertTrue(dao.priceSelection(OWNER, ENTRY)!!.cleared)
        assertNull(dao.priceSelection(OWNER, ENTRY)!!.amountMinor)
        assertEquals(30L, dao.getCollectionEntryBySyncId(OWNER, ENTRY)?.deletedAtEpochMillis)
    }

    @Test fun malformedConflictMarkerIsVisibleButCannotBeSilentlyRebased() = runBlocking {
        seed(); mark(mutation())
        val bad = current()!!.copy(lastError = "price-selection-conflict:garbage")
        dao.upsertPrivateBinderSyncOutbox(bad)
        assertEquals(1, dao.observePriceSelectionConflictCount(OWNER).first())
        assertFalse(local(bad)); assertFalse(cloud(bad))
        assertTrue(dao.pendingPrivateBinderSyncOutbox(OWNER).isEmpty())
    }

    @Test fun explicitLocalClearRequiresActuallyEmptyEntityFields() = runBlocking {
        seed(); mark(mutation())
        val conflict = current()!!
        val entryId = dao.priceSelection(OWNER, ENTRY)!!.collectionEntryId
        val clear = CollectionPriceSelectionEntity(entryId, OWNER, ENTRY, true,
            updatedAtEpochMillis = 20, syncState = SYNC_CONFLICT, remoteRevision = 1)
        // The wire encoder would normalize these away; the decision helper must still reject them.
        for (malformed in listOf(clear.copy(amountMinor = 99), clear.copy(sourceCardId = "stray"),
            clear.copy(sourceAt = 10), clear.copy(metric = "normal_low"))) {
            dao.upsertPriceSelection(malformed)
            assertFalse(local(conflict))
            assertEquals(malformed, dao.priceSelection(OWNER, ENTRY))
            assertEquals(conflict, current())
        }
        dao.upsertPriceSelection(clear)
        assertTrue(local(conflict))
        assertEquals(clear.copy(syncState = SYNC_PENDING, remoteRevision = 3, updatedAtEpochMillis = 99),
            dao.priceSelection(OWNER, ENTRY))
        assertEquals(3L, current()?.expectedRemoteRevision)
    }

    @Test fun noCrossOwnerMutationOrResolution() = runBlocking {
        seed(); mark(mutation())
        val conflict = current()!!
        assertFalse(mark(conflict.copy(ownerUid = "foreign")))
        assertFalse(local(conflict.copy(ownerUid = "foreign")))
        assertFalse(cloud(conflict.copy(ownerUid = "foreign")))
        assertEquals(conflict, current())
    }

    @Test fun ownerRoundTripBeforeActionPreservesConflict() = runBlocking {
        seed(); mark(mutation())
        val conflict = current()!!
        currentOwner = "other"; generation.incrementAndGet(); currentOwner = OWNER; generation.incrementAndGet()
        assertTrue(runCatching { local(conflict) }.exceptionOrNull() is PrivatePullAccountChangedException)
        assertTrue(runCatching { cloud(conflict) }.exceptionOrNull() is PrivatePullAccountChangedException)
        assertEquals(conflict, current())
    }

    @Test fun lateAccountChangeRollsBackBothResolutionPathsAndMarker() = runBlocking {
        seed()
        val sent = mutation()
        var checks = 0
        assertTrue(runCatching {
            database.withPrivateSyncSession({ ++checks == 1 }) { markPriceSelectionConflict(dao, sent, 3) }
        }.exceptionOrNull() is PrivatePullAccountChangedException)
        assertEquals(sent, current())
        mark(sent)
        val conflict = current()!!
        val before = dao.priceSelection(OWNER, ENTRY)
        checks = 0
        assertTrue(runCatching { keepLocalPriceSelectionConflict(database, conflict, "new", 99) { ++checks == 1 } }
            .exceptionOrNull() is PrivatePullAccountChangedException)
        assertEquals(conflict, current()); assertEquals(before, dao.priceSelection(OWNER, ENTRY))
        checks = 0
        assertTrue(runCatching { keepCloudPriceSelectionConflict(database, conflict, remote(), true) { ++checks == 1 } }
            .exceptionOrNull() is PrivatePullAccountChangedException)
        assertEquals(conflict, current()); assertEquals(before, dao.priceSelection(OWNER, ENTRY))
    }

    @Test fun cancellationAtCommitBoundaryRollsBackPayloadAndOutbox() = runBlocking {
        seed(); mark(mutation())
        val conflict = current()!!
        val before = dao.priceSelection(OWNER, ENTRY)
        for (local in listOf(true, false)) {
            var checks = 0
            val current = { if (++checks > 1) throw CancellationException("synthetic cancellation") else true }
            val failure = runCatching {
                if (local) keepLocalPriceSelectionConflict(database, conflict, "new", 99, current)
                else keepCloudPriceSelectionConflict(database, conflict, remote(), true, current)
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(conflict, this@PriceSelectionConflictInstrumentedTest.current())
            assertEquals(before, dao.priceSelection(OWNER, ENTRY))
        }
    }

    @Test fun combinedSettingsActionDoesNotResumeCollectionRebaseAfterOwnerChangeOrRoundTrip() = runBlocking {
        seed()
        val parent = dao.getCollectionEntryBySyncId(OWNER, ENTRY)!!
        dao.updateCollectionEntry(parent.copy(syncState = SYNC_CONFLICT))
        val collectionConflict = CollectionSyncOutboxEntity("$OWNER:$ENTRY", "collection-conflict", OWNER, ENTRY,
            "UPSERT", 1, SYNC_CONFLICT, 20, "remote-revision-3")
        dao.upsertSyncOutbox(collectionConflict)
        for (roundTrip in listOf(false, true)) {
            val owner = MutableStateFlow(OWNER)
            var generation = 1
            val repository = RoomCollectionRepository(database, owner, onKeepLocalPriceConflicts = { expected ->
                assertEquals(OWNER, expected)
                owner.value = "other"; generation++
                if (roundTrip) { owner.value = OWNER; generation++ }
                0 // e.g. initial entitlement failure must not permit continuation.
            }, captureConflictSession = { expected ->
                val captured = generation
                { owner.value == expected && captured == generation }
            })
            assertTrue(runCatching { repository.retryConflictsKeepingLocal() }.exceptionOrNull() is PrivatePullAccountChangedException)
            assertEquals(collectionConflict, dao.syncOutbox(collectionConflict.entryKey))
            assertEquals(SYNC_CONFLICT, dao.getCollectionEntryBySyncId(OWNER, ENTRY)?.syncState)
        }
    }

    @Test fun combinedConflictCountIncludesCollectionAndPriceWithoutChangingCollectionPolicy() = runBlocking {
        seed(); mark(mutation())
        val parent = dao.getCollectionEntryBySyncId(OWNER, ENTRY)!!
        dao.updateCollectionEntry(parent.copy(syncState = SYNC_CONFLICT))
        dao.upsertSyncOutbox(CollectionSyncOutboxEntity("$OWNER:$ENTRY", "collection-conflict", OWNER, ENTRY,
            "UPSERT", 1, SYNC_CONFLICT, 20, "remote-revision-3"))
        val repository = RoomCollectionRepository(database, MutableStateFlow(OWNER), onKeepLocalPriceConflicts = { expected ->
            assertEquals(OWNER, expected)
            // Price stays blocked by its conflicted parent; existing collection retry can proceed.
            if (local(current()!!)) 1 else 0
        })
        assertEquals(2, repository.syncConflictCount.first())
        assertEquals(1, repository.retryConflictsKeepingLocal())
        assertEquals(SYNC_PENDING, dao.syncOutbox("$OWNER:$ENTRY")?.state)
        assertEquals(1, repository.syncConflictCount.first())
        assertEquals(SYNC_CONFLICT, dao.priceSelection(OWNER, ENTRY)?.syncState)
    }

    private suspend fun local(conflict: PrivateBinderSyncOutboxEntity) =
        keepLocalPriceSelectionConflict(database, conflict, "explicit-new", 99, lease::isCurrent)
    private suspend fun cloud(conflict: PrivateBinderSyncOutboxEntity, data: Map<String, Any?> = remote()) =
        keepCloudPriceSelectionConflict(database, conflict, data, true, lease::isCurrent)
    private suspend fun mark(mutation: PrivateBinderSyncOutboxEntity) =
        database.withPrivateSyncSession(lease::isCurrent) { markPriceSelectionConflict(dao, mutation, 3) }
    private suspend fun current() = dao.privateBinderSyncOutbox(mutation().entryKey)

    private suspend fun seed() {
        dao.insertCatalogCards(listOf(CatalogCardEntity("card", "POKEMON", "synthetic-set", "Synthetic",
            "Synthetic card", "1", "GERMAN", "Normal", "Standard", 1)))
        dao.upsertBinders(listOf(BinderEntity("inbox", "inbox", "Synthetic", createdAtEpochMillis = 1, updatedAtEpochMillis = 1)))
        val parent = CollectionEntryEntity(cardId = "card", quantity = 1, condition = "Near Mint", acquisitionPriceMinor = null,
            acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "", frontPhotoUri = null, backPhotoUri = null,
            createdAtEpochMillis = 1, updatedAtEpochMillis = 1, ownerUid = OWNER, syncId = ENTRY,
            syncState = SYNC_CLEAN, remoteRevision = 1, sourceSetId = "synthetic-set", sourceCardId = "synthetic-set-1",
            externalProductSource = CardmarketSyncContract.SOURCE, externalProductId = 42)
        val id = dao.insertCollectionEntry(parent)
        dao.upsertPriceSelection(parsePriceSelectionSidecar(remote() + ("amountMinor" to 532L) + ("revision" to 1L), OWNER, ENTRY, id)!!
            .copy(syncState = SYNC_PENDING))
        dao.upsertPrivateBinderSyncOutbox(mutation())
    }

    private fun mutation() = PrivateBinderSyncOutboxEntity("$OWNER:$PRIVATE_SYNC_PRICE_SELECTION:$ENTRY", "pending",
        OWNER, PRIVATE_SYNC_PRICE_SELECTION, ENTRY, PRIVATE_SYNC_UPSERT, 1, 20, 20)
    private fun remote(): Map<String, Any?> = mapOf("ownerUid" to OWNER, "entrySyncId" to ENTRY, "schemaVersion" to 1L,
        "revision" to 3L, "updatedAtClient" to 25L, "lastMutationId" to "remote", "cleared" to false,
        "sourceSetId" to "synthetic-set", "sourceCardId" to "synthetic-set-1", "collectorNumber" to "1",
        "finish" to "Normal", "variant" to "Standard", "language" to "GERMAN", "productId" to 42L,
        "metric" to "normal_low", "amountMinor" to 999L, "currency" to "EUR", "sourceAt" to 10L, "selectedAt" to 15L)

    companion object { private const val OWNER = "price-conflict-A"; private const val ENTRY = "entry" }
}
